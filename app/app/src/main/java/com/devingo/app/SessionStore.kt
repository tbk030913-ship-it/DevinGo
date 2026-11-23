package com.devingo.app

import android.util.Base64
import com.devingo.app.acp.AcpClient
import com.devingo.app.acp.AcpEvent
import com.devingo.app.acp.AcpMethods
import com.devingo.app.acp.AttachResult
import com.devingo.app.acp.ConfigOption
import com.devingo.app.acp.ContentBlock
import com.devingo.app.acp.InboundEvent
import com.devingo.app.acp.IncomingRequest
import com.devingo.app.acp.JSONRPC_METHOD_NOT_FOUND
import com.devingo.app.acp.PermissionOption
import com.devingo.app.acp.PlanEntry
import com.devingo.app.acp.RequestPermissionParams
import com.devingo.app.acp.RevertStep
import com.devingo.app.acp.SessionMode
import com.devingo.app.acp.SessionModel
import com.devingo.app.acp.SessionNewResult
import com.devingo.app.acp.ToolCallInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One session's live state — transcript, session-scoped [SessionUi],
 * permission queue, prompt queue, composer draft, turn timings and the
 * `_devingo/attach` journal cursor ([lastSeq]). [ConnectionManager] owns one
 * store per subscribed session; a store keeps receiving updates while it is
 * not on screen so switching back is instant and nothing is lost.
 *
 * All mutation flows through the single demux collector plus user actions
 * forwarded by [ChatViewModel]; [scope] children are cancelled on [drop].
 */
class SessionStore(
    val sessionId: String,
    /** Working directory prompts and file links are resolved against. */
    var cwd: String,
    private val client: AcpClient,
    private val json: Json,
    private val notifier: Notifier,
    internal val scope: CoroutineScope,
    private val onSessionReady: ((sessionId: String, cwd: String) -> Unit)? = null,
) {

    private companion object {
        /** Keep at most this many transcript items; oldest beyond the head are dropped. */
        const val MAX_MESSAGES = 1500
        /** Items at the head (session banner, first user prompt) that trimming keeps. */
        const val KEEP_HEAD = 10
        /** Streaming chunks flush to the list at most every this many ms… */
        const val FLUSH_INTERVAL_MS = 60L
        /** …or immediately once the buffer holds this many chars. */
        const val FLUSH_CHARS = 200
        /** If session/cancel doesn't end the prompt job within this, kill it. */
        const val CANCEL_WATCHDOG_MS = 10_000L
        /**
         * Combined compressed bytes of one send's attachments (pre-Base64).
         * Decimal MB: after 4/3 Base64 inflation + JSON envelope this still
         * lands under the bridge's 4 MiB WS message limit.
         */
        const val MAX_ATTACHMENT_BYTES = 3_000_000L
        /** Sentinel completing a permission whose answer came from a peer client. */
        const val RESOLVED_BY_PEER = "__peer__"
    }

    /**
     * Highest `_seq` observed on a journaled frame for this session.
     * `seedSeq` runs on OkHttp's dispatch thread while `noteSeq` runs on
     * Main — @Volatile alone wouldn't make the read-modify-write atomic.
     */
    private val seqCursor = java.util.concurrent.atomic.AtomicLong(0)
    val lastSeq: Long get() = seqCursor.get()

    /** Subscribed at the gateway (attach done, or new/load auto-subscribed). */
    @Volatile var attached: Boolean = false

    /** Serializes attach replays — a resync reattach and a manual re-open must not interleave. */
    internal val attachMutex = Mutex()

    /** `_seq`s at or below this are attach-replay history, not live traffic. */
    @Volatile private var liveSeqFloor: Long = 0

    /** True while a `session/load` replay is streaming into the transcript. */
    @Volatile var replaying: Boolean = false
        private set

    private val _ui = MutableStateFlow(SessionUi(sessionId = sessionId))
    val ui = _ui.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages = _messages.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PendingPermission?>(null)
    val pendingPermission = _pendingPermission.asStateFlow()

    private val _queuedPrompts = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val queuedPrompts = _queuedPrompts.asStateFlow()

    /** Steps offered by the revert sheet; null until [loadRevertSteps] runs. */
    private val _revertSteps = MutableStateFlow<List<RevertStep>?>(null)
    val revertSteps = _revertSteps.asStateFlow()

    /** Turn start/end keyed on the user message's item id. */
    private val _turnTimes = MutableStateFlow<Map<Long, TurnTiming>>(emptyMap())
    val turnTimes = _turnTimes.asStateFlow()

    /** Composer drafts survive switching away and back. */
    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()
    private val _draftAttachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val draftAttachments = _draftAttachments.asStateFlow()

    /** Output arrived while another session was on screen (drawer badge). */
    val unread = MutableStateFlow(false)

    // Streaming accumulation: chunks land in [streamBuf] and flush to the
    // message list on a timer (or at FLUSH_CHARS), instead of copying the
    // whole transcript per chunk. [streamLive] records whether the buffered
    // text is live traffic (vs attach/load replay) — turn timing must not
    // start on history, and replay frames can outlive the replaying flag
    // (the attach response arrives before the backlog).
    private var streamingId: Long? = null
    private var streamKind: Kind? = null
    private var streamLive = false
    private val streamBuf = StringBuilder()
    private var flushJob: Job? = null

    private var nextItemId = 0L
    private var planItemId: Long? = null
    private var promptJob: Job? = null
    private var steerJob: Job? = null
    /** A remote client's turn observed streaming (no local promptJob). */
    private var remoteTurn = false
    /** True once any turn activity (chunk/tool/plan) arrived for the in-flight prompt. */
    @Volatile private var promptAcked = false

    // Permission requests queue internally; the UI only ever sees the head.
    private val permissionLock = Any()
    private val permissionQueue = ArrayDeque<PendingPermission>()
    /** Wire request id -> open request; attach re-deliveries dedupe on it. */
    private val openRequests = mutableMapOf<Long, PermEntry>()
    /**
     * Unique negative keys for requests whose wire id isn't an integer —
     * a shared -1 would collapse every keyless request onto one entry and
     * the second would be mistaken for a re-delivery of the first.
     */
    private val syntheticRequestKey = java.util.concurrent.atomic.AtomicLong(-1)

    private class PermEntry(
        var req: IncomingRequest,
        val pending: PendingPermission,
        /** Another client already answered — respond nothing ourselves. */
        var resolvedByPeer: Boolean = false,
    )

    // ---- journal cursor / attach bookkeeping -----------------------------------

    /** Record a journaled `_seq`; the skip-ahead check lives in [liveGap]. */
    fun noteSeq(seq: Long) {
        if (seq <= 0) return
        seqCursor.updateAndGet { maxOf(it, seq) }
    }

    /**
     * A `_seq` learned out-of-band — a journaled response to one of our own
     * requests, or a seq stashed before the store existed. It must not vault
     * the cursor: a seq past lastSeq+1 means live frames were withheld (a
     * peer client's load window), and skipping ahead would drop the hole
     * forever. The cursor stays at the last consumed frame; the next live
     * frame's gap check re-attaches and the backlog covers the window. The
     * response itself still completes its request — only the cursor
     * bookkeeping ignores out-of-order seqs.
     */
    fun seedSeq(seq: Long) {
        seqCursor.updateAndGet { if (seq == it + 1) seq else it }
    }

    /** The store is subscribed and its replay boundary is known. */
    fun markAttached(result: AttachResult) {
        attached = true
        liveSeqFloor = maxOf(liveSeqFloor, result.lastSeq)
        noteSeq(result.lastSeq)
        result.session?.let { applySessionConfig(it) }
        remoteTurn = result.running
        refreshRunning()
    }

    /** Auto-subscribed by the hub: no attach call needed (session/new). */
    fun markSubscribed() {
        attached = true
    }

    /** Cursor at replay start — a failed attach rewinds here so a retry
     *  re-covers frames the aborted replay only partially delivered. */
    private var replayBaseSeq = 0L

    /**
     * True only while an `_devingo/attach` call is in flight — NOT during
     * `session/load` replays (in-flight frames there ARE the replay).
     * The hub's attach backlog contains every journaled entry past our
     * cursor, so a live frame delivered in this window can arrive AGAIN
     * inside the backlog — such frames park in [deferredLive] until the
     * attach resolves instead of being consumed twice.
     */
    @Volatile private var attachDefer = false
    private val deferredLive = ArrayDeque<InboundEvent>()

    /**
     * An attach replay is streaming in: suppress live-turn inference and
     * user-message timing bookkeeping for its duration. `attached` clears so
     * a seq jump *inside* the replay can't trigger a re-attach loop.
     */
    fun beginReplay() {
        replaying = true
        attachDefer = true
        attached = false
        replayBaseSeq = lastSeq
    }

    fun endReplay() {
        replaying = false
        attachDefer = false
        // markAttached/finishLoad flipped attached on success; a still-false
        // flag means the attach aborted mid-stream — rewind the cursor.
        if (!attached) seqCursor.set(replayBaseSeq)
    }

    /**
     * Hold [ev] while an `_devingo/attach` is in flight — see [attachDefer].
     * Returns true when the event was deferred: the caller must not consume
     * it (nor advance the cursor); [drainDeferred] replays what the backlog
     * didn't cover.
     */
    fun deferIfReplaying(ev: InboundEvent): Boolean {
        if (!attachDefer || ev.seq <= 0) return false
        deferredLive.addLast(ev)
        return true
    }

    /**
     * The frames deferred during the attach window, minus the ones the
     * backlog covers (seq at/below the replayed cursor — their backlog
     * copies are already in flight). The rest are real live events the
     * caller re-routes. Cleared outright when the attach failed: a later
     * attach re-covers them from the journal, so consuming them now would
     * duplicate.
     */
    fun drainDeferred(): List<InboundEvent> {
        if (deferredLive.isEmpty()) return emptyList()
        val out = if (attached) deferredLive.filter { it.seq > lastSeq } else emptyList()
        deferredLive.clear()
        return out
    }

    /** History is about to be replayed by `session/load`: start clean. */
    fun beginLoad() {
        replaying = true
        attachDefer = false
        resetTranscript()
    }

    /** `session/load` finished: config applied, replay window closed. */
    fun finishLoad(result: SessionNewResult) {
        replaying = false
        attached = true
        // Replay frames still landing past the response are history — pin the
        // live floor at the cursor so they can't masquerade as a live turn.
        liveSeqFloor = maxOf(liveSeqFloor, lastSeq)
        applySessionConfig(result)
    }

    /** Tear down: queued prompts, pending perms and jobs all die with the store. */
    fun drop() {
        promptJob?.cancel()
        steerJob?.cancel()
        clearPermissions()
        deferredLive.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    // ---- events ---------------------------------------------------------------

    /** True for live traffic — never attach/load replay backlog. */
    fun isLive(seq: Long): Boolean = !replaying && (seq == 0L || seq > liveSeqFloor)

    /**
     * A journaled frame [seq] skipped ahead of the cursor while live — the
     * withheld window only returns via `_devingo/attach`. The caller must
     * NOT consume this frame inline: the replay re-delivers it (and the
     * skipped window) in order. Replay backlog never reports a gap.
     */
    fun liveGap(seq: Long): Boolean = attached && isLive(seq) && seq > lastSeq + 1

    private fun remoteTurnActivity() {
        if (!remoteTurn && promptJob?.isActive != true) {
            remoteTurn = true
            refreshRunning()
        }
    }

    fun onEvent(ev: AcpEvent, seq: Long = 0) {
        when (ev) {
            is AcpEvent.AgentChunk -> {
                promptAcked = true
                val live = isLive(seq)
                if (live) remoteTurnActivity()
                appendStreaming(Kind.Agent, ev.text, live)
            }
            is AcpEvent.ThoughtChunk -> {
                promptAcked = true
                val live = isLive(seq)
                if (live) remoteTurnActivity()
                appendStreaming(Kind.Thought, ev.text, live)
            }
            is AcpEvent.UserChunk -> {
                promptAcked = true
                flushStreaming()
                // session/load replays the user's own messages; skip the chunk
                // if it merely echoes the prompt we just appended locally.
                val lastUser = _messages.value.lastOrNull { it.kind == Kind.User }
                if (lastUser == null || lastUser.text != ev.text) {
                    appendStreaming(Kind.User, ev.text, isLive(seq))
                }
            }
            is AcpEvent.RemoteTurnEnd -> {
                if (!isLive(seq)) return
                remoteTurn = false
                refreshRunning()
                reportStopReason(ev.stopReason)
            }
            is AcpEvent.AgentDead -> {
                remoteTurn = false
                refreshRunning()
                if (isLive(seq)) {
                    failInflightTools()
                    append(Kind.Error, "Devin 进程已退出: ${ev.reason}")
                }
            }
            else -> {
                // Chunks are buffered — anything else must not overtake them,
                // so the pending text is committed before handling the event.
                flushStreaming()
                onNonChunkEvent(ev, seq)
            }
        }
    }

    private fun onNonChunkEvent(ev: AcpEvent, seq: Long) {
        when (ev) {
            is AcpEvent.ToolCall -> {
                promptAcked = true
                if (isLive(seq)) remoteTurnActivity()
                append(
                    Kind.Tool,
                    ev.call.title ?: ev.call.kind ?: "tool call",
                    status = ev.call.status ?: "pending",
                    toolCallId = ev.call.toolCallId,
                    detail = toolDetailOf(ev.call),
                )
            }
            is AcpEvent.ToolCallUpdate -> {
                promptAcked = true
                if (isLive(seq)) remoteTurnActivity()
                updateTool(ev.call)
            }
            is AcpEvent.PlanUpdate -> {
                promptAcked = true
                if (isLive(seq)) remoteTurnActivity()
                upsertPlan(ev.entries)
            }
            is AcpEvent.ModeChanged -> _ui.update { it.copy(currentModeId = ev.modeId) }
            is AcpEvent.ConfigOptionsUpdated -> applyConfigOptions(ev.options)
            is AcpEvent.SessionTitle -> _ui.update { it.copy(sessionTitle = ev.title) }
            is AcpEvent.UsageUpdate -> _ui.update {
                it.copy(
                    contextUsed = ev.used,
                    contextSize = ev.size,
                    usageInput = ev.inputTokens ?: 0,
                    usageOutput = ev.outputTokens ?: 0,
                    usageCacheRead = ev.cacheReadTokens ?: 0,
                )
            }
            is AcpEvent.CompactionUpdate -> append(Kind.System, compactionText(ev.status))
            is AcpEvent.RevertStepsUpdated -> {
                // Step list changed underneath us — refresh if the sheet is open.
                if (_revertSteps.value != null) loadRevertSteps()
            }
            is AcpEvent.CommandsAvailable -> _ui.update { it.copy(commands = ev.commands) }
            is AcpEvent.AgentLog -> {
                // MCP server churn is routine diagnostics the phone user can't
                // act on; only surface logs that aren't MCP noise.
                if (!ev.channel.startsWith("MCP")) {
                    append(Kind.System, "[${ev.channel}] ${ev.message}")
                }
            }
            is AcpEvent.Failure -> append(Kind.Error, ev.message)
            else -> Unit // forward-compat with updates added alongside acp/ changes
        }
    }

    /**
     * The socket dropped (the hub still hosts the session — the turn may well
     * continue server-side). [reason] is the wire close detail (e.g.
     * "连接已断开 (1011): devin acp exited") shown in the transcript.
     * Permissions are re-delivered after re-attach, so the stranded cards
     * clear here; in-flight tools flip to failed — the attach replay heals
     * any that actually completed while we were away. `attached` clears so
     * the open path knows this store needs re-attach, not a blind rebind.
     */
    fun onDisconnected(reason: String) {
        attached = false
        attachDefer = false
        deferredLive.clear()
        clearPermissions()
        failInflightTools()
        remoteTurn = false
        // A mid-replay drop leaves the cursor past frames that never landed —
        // rewind so the reconnect attach re-covers them.
        if (replaying) {
            seqCursor.set(replayBaseSeq)
            replaying = false
        }
        refreshRunning()
        append(Kind.System, "$reason，重连后自动补齐")
    }

    private fun failInflightTools() {
        _messages.update { list ->
            list.map {
                if (it.kind == Kind.Tool &&
                    (it.status == "in_progress" || it.status == "pending")
                ) {
                    it.copy(status = "failed")
                } else {
                    it
                }
            }
        }
    }

    // ---- session config --------------------------------------------------------

    /**
     * Devin reports modes/models as `configOptions` (categories "mode" and
     * "model") on both `session/new` and `session/load`; the ACP `modes`/
     * `models` fields are the fallback shape.
     */
    fun applySessionConfig(result: SessionNewResult) {
        val modes = result.resolvedModes()
        val models = result.resolvedModels()
        _ui.update {
            it.copy(
                modes = modes?.availableModes ?: it.modes,
                currentModeId = modes?.currentModeId ?: it.currentModeId,
                models = models?.availableModels ?: it.models,
                currentModelId = models?.currentModelId ?: it.currentModelId,
                extraConfigOptions = result.configOptions.filter { o -> o.isExtra() },
            )
        }
    }

    /** Options surfaced in the model sheet beyond mode/model (effort, …). */
    private fun ConfigOption.isExtra(): Boolean =
        id != "mode" && id != "model" &&
            category != "mode" && category != "model"

    /** `config_option_update` re-sends the options array after a change. */
    private fun applyConfigOptions(options: List<ConfigOption>) {
        options.forEach { opt ->
            val choices = opt.options
            when (opt.id) {
                "mode" -> _ui.update {
                    it.copy(
                        modes = choices.map { c -> SessionMode(c.value, c.name, c.description) },
                        currentModeId = opt.currentValue ?: it.currentModeId,
                    )
                }
                "model" -> _ui.update {
                    it.copy(
                        models = choices.map { c -> SessionModel(c.value, c.name, c.description) },
                        currentModelId = opt.currentValue ?: it.currentModelId,
                    )
                }
            }
        }
        options.filter { it.isExtra() }.forEach { opt ->
            _ui.update { st ->
                val idx = st.extraConfigOptions.indexOfFirst { it.id == opt.id }
                st.copy(
                    extraConfigOptions = if (idx >= 0) {
                        st.extraConfigOptions.toMutableList().apply { set(idx, opt) }
                    } else {
                        st.extraConfigOptions + opt
                    },
                )
            }
        }
    }

    // ---- drafts ----------------------------------------------------------------

    fun setDraft(text: String) {
        _draft.value = text
    }

    fun setDraftAttachments(attachments: List<ImageAttachment>) {
        _draftAttachments.value = attachments
    }

    // ---- prompts ---------------------------------------------------------------

    /**
     * Send while idle. When a turn is running the message joins the queue
     * (Devin-style "Queue" semantics); use [steer] for an immediate
     * mid-turn interrupt, which `midTurnPrompts` support enables.
     *
     * Returns false when nothing was sent — the caller must keep the input
     * and attachments so the user doesn't lose them.
     */
    fun send(text: String, attachments: List<ImageAttachment> = emptyList()): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && attachments.isEmpty()) return false
        val total = attachments.sumOf { it.bytes.size.toLong() }
        if (total > MAX_ATTACHMENT_BYTES) {
            append(Kind.Error, "图片合计超过 3MB，未发送")
            return false
        }
        if (_ui.value.running || promptJob?.isActive == true) {
            // Queued prompts carry the attachments themselves — the bytes go
            // out when the queue drains, nothing is dropped.
            _queuedPrompts.update { it + QueuedPrompt(trimmed, attachments) }
            return true
        }
        launchPrompt(trimmed, attachments)
        return true
    }

    /** Interrupt the running turn with a new instruction right now. */
    fun steer(text: String) {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return
        append(Kind.User, trimmed)
        promptAcked = true // the turn is live; a steer failure isn't a lost echo
        steerJob = scope.launch {
            try {
                reportStopReason(client.promptMidTurn(sessionId, trimmed).stopReason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "打断失败: ${e.display()}")
            }
        }
    }

    fun cancelQueued(index: Int) {
        _queuedPrompts.update { q -> q.filterIndexed { i, _ -> i != index } }
    }

    /**
     * Re-send a message whose echo was marked 未发送: drop the dead item and
     * put the original text + image bytes through the normal send path again
     * (it queues when a turn is running). A no-op when the send can't go out
     * (e.g. session gone) — the failed bubble stays put then.
     */
    fun resend(item: ChatItem) {
        if (item.kind != Kind.User || !item.failed) return
        // Double-tap guard: the captured item still reads failed=true a
        // frame after the removal below, so gate on the echo still being
        // in the transcript instead.
        if (_messages.value.none { it.id == item.id }) return
        if (!send(item.text, item.attachments)) return
        _messages.update { list -> list.filterNot { it.id == item.id } }
    }

    private fun drainQueue() {
        val next = _queuedPrompts.value.firstOrNull() ?: return
        _queuedPrompts.update { it.drop(1) }
        launchPrompt(next.text, next.attachments)
    }

    private fun launchPrompt(
        text: String,
        attachments: List<ImageAttachment>,
    ) {
        val blocks = buildBlocks(text, attachments)
        // The echo keeps the raw text — UserBubble renders the "[N 张图片]"
        // suffix — so UserChunk dedup matches and resend() replays verbatim.
        val userItem = append(Kind.User, text, attachments = attachments)
        promptAcked = false
        promptJob = scope.launch {
            remoteTurn = false // our own job now speaks for the turn
            refreshRunning()
            try {
                val result = client.promptBlocks(sessionId, blocks)
                onSessionReady?.invoke(sessionId, cwd)
                reportStopReason(result.stopReason)
                if (_messages.value.isNotEmpty()) {
                    notifier.notifyTurnDone(
                        _ui.value.sessionTitle?.takeIf { it.isNotBlank() } ?: "DevinGo",
                        sessionId,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!promptAcked) {
                    // No turn activity ever arrived — the agent likely never
                    // received the prompt, so flag the local echo accordingly.
                    markFailed(userItem.id)
                    append(Kind.Error, "${e.display()}（消息可能未送达）")
                } else {
                    append(Kind.Error, e.display())
                }
            } finally {
                // refreshRunning's falling edge releases the next queued
                // prompt — no explicit drain here or it would send two.
                refreshRunning()
                // The echo's image bytes exist only to resend a 未发送
                // message — once it isn't flagged failed they're dead
                // weight (the badge reads attachmentCount instead).
                if (attachments.isNotEmpty()) {
                    _messages.update { list ->
                        list.map {
                            if (it.id == userItem.id && !it.failed) {
                                it.copy(attachments = emptyList())
                            } else {
                                it
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Compose the prompt's content blocks: image attachments first, then
     * the text with `@path` tokens lifted into `resource_link` blocks —
     * Devin advertises file/directory mention types plus embeddedContext.
     */
    private fun buildBlocks(text: String, attachments: List<ImageAttachment>): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        attachments.forEach { a ->
            blocks += ContentBlock(
                type = "image",
                data = Base64.encodeToString(a.bytes, Base64.NO_WRAP),
                mimeType = a.mimeType,
            )
        }
        val buf = StringBuilder()
        fun flushText() {
            if (buf.isNotBlank()) {
                blocks += ContentBlock(type = "text", text = buf.toString().trimEnd())
                buf.clear()
            }
        }
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { tok ->
            val m = tok.removePrefix("@")
            val looksLikePath = tok.startsWith("@") &&
                (m.contains('/') || m.contains('\\') || m.contains('.'))
            if (looksLikePath) {
                flushText()
                blocks += ContentBlock(type = "resource_link", uri = fileUri(m), name = m)
            } else {
                buf.append(tok).append(' ')
            }
        }
        flushText()
        if (blocks.isEmpty()) blocks += ContentBlock(type = "text", text = text)
        return blocks
    }

    private fun fileUri(rel: String): String {
        val base = cwd.replace('\\', '/').trimEnd('/')
        val path = rel.replace('\\', '/').trimStart('/')
        return "file:///$base/$path"
    }

    /** Non-`end_turn` stop reasons get a visible system line. */
    private fun reportStopReason(stopReason: String?) {
        when (stopReason) {
            null, "end_turn" -> {}
            "cancelled" -> append(Kind.System, "本轮已取消")
            "max_tokens" -> append(Kind.System, "达到输出上限")
            "refusal" -> append(Kind.System, "被拒绝")
            else -> append(Kind.System, stopReason)
        }
    }

    fun cancelTurn() {
        runCatching { client.cancel(sessionId) }
        // Watchdog: if the agent never answers session/cancel, the prompt
        // request would hang and `running` would stay true forever.
        val job = promptJob ?: return
        scope.launch {
            delay(CANCEL_WATCHDOG_MS)
            if (promptJob === job && job.isActive) {
                job.cancel()
                append(Kind.System, "本轮已强制结束")
            }
        }
    }

    fun setMode(modeId: String) {
        _ui.update { it.copy(currentModeId = modeId) }
        scope.launch {
            try {
                client.setMode(sessionId, modeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换模式失败: ${e.display()}")
            }
        }
    }

    /** Generic config setter for extraConfigOptions (effort and friends). */
    fun setConfigOption(configId: String, value: String) {
        _ui.update { st ->
            st.copy(
                extraConfigOptions = st.extraConfigOptions.map {
                    if (it.id == configId) it.copy(currentValue = value) else it
                },
            )
        }
        scope.launch {
            try {
                client.setConfigOption(sessionId, configId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换设置失败: ${e.display()}")
            }
        }
    }

    fun setModel(modelId: String) {
        _ui.update { it.copy(currentModelId = modelId) }
        scope.launch {
            try {
                client.setModel(sessionId, modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换模型失败: ${e.display()}")
            }
        }
    }

    // ---- revert / fork ---------------------------------------------------------

    /** Populate the revert sheet's step list for this session. */
    fun loadRevertSteps() {
        _revertSteps.value = null
        scope.launch {
            try {
                _revertSteps.value = client.revertSteps(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _revertSteps.value = emptyList()
                append(Kind.Error, "回退步骤获取失败: ${e.display()}")
            }
        }
    }

    fun dismissRevertSteps() {
        _revertSteps.value = null
    }

    /** Fork a new session off [stepId]; the caller opens the returned id. */
    suspend fun forkFromStep(stepId: String): String? = try {
        client.revertForkFromStep(sessionId, stepId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        append(Kind.Error, "分叉失败: ${e.display()}")
        null
    } finally {
        _revertSteps.value = null
    }

    // ---- agent -> client requests -----------------------------------------------

    suspend fun handleAgentRequest(req: IncomingRequest) {
        when (req.method) {
            AcpMethods.SESSION_REQUEST_PERMISSION -> handlePermission(req)
            else -> req.respondError(JSONRPC_METHOD_NOT_FOUND, "DevinGo 不支持 ${req.method}")
        }
    }

    private suspend fun handlePermission(req: IncomingRequest) {
        val params = req.params?.let {
            runCatching { json.decodeFromJsonElement<RequestPermissionParams>(it) }.getOrNull()
        }
        if (params == null) {
            req.respondError(-32602, "invalid params")
            return
        }
        // Attach re-delivers still-open requests without _seq; the same wire
        // id means the same pending card — keep the newest (livest) responder.
        synchronized(permissionLock) {
            openRequests.entries.firstOrNull { it.value.req.rpcId == req.rpcId }
                ?.let { it.value.req = req; return }
        }
        val requestId = req.requestKey ?: syntheticRequestKey.getAndDecrement()
        val choice = CompletableDeferred<String?>()
        val pending = PendingPermission(params.toolCall, params.options, choice, requestId)
        val entry = PermEntry(req, pending)
        synchronized(permissionLock) { openRequests[requestId] = entry }
        pushPermission(pending)
        notifier.notifyPermissionPending(
            params.toolCall.title ?: params.toolCall.kind ?: "工具调用",
            sessionId,
        )
        // The gateway owns the pending-approval TTL — a request stays open
        // until the user answers or _devingo/permission_resolved lands.
        val optionId = choice.await()
        popPermission(pending)
        synchronized(permissionLock) { openRequests.remove(requestId, entry) }
        if (entry.resolvedByPeer) return // a peer answered; the hub resolved it
        entry.req.respond(
            buildJsonObject {
                putJsonObject("outcome") {
                    if (optionId == null) {
                        put("outcome", "cancelled")
                    } else {
                        put("outcome", "selected")
                        put("optionId", optionId)
                    }
                }
            },
        )
    }

    /** `_devingo/permission_resolved`: a peer answered request [requestId]. */
    fun resolvePeerPermission(requestId: Long) {
        synchronized(permissionLock) {
            openRequests[requestId]?.let {
                it.resolvedByPeer = true
                it.pending.choice.complete(RESOLVED_BY_PEER)
            }
        }
    }

    private fun pushPermission(p: PendingPermission) = synchronized(permissionLock) {
        permissionQueue.addLast(p)
        _pendingPermission.value = permissionQueue.firstOrNull()
    }

    private fun popPermission(p: PendingPermission) = synchronized(permissionLock) {
        permissionQueue.remove(p)
        _pendingPermission.value = permissionQueue.firstOrNull()
    }

    /**
     * Abandon every queued request WITHOUT responding — the hub holds the
     * permission for its own TTL and re-delivers it after re-attach, so a
     * teardown here must not resolve it as "cancelled" agent-side.
     */
    private fun clearPermissions() = synchronized(permissionLock) {
        openRequests.values.forEach { it.resolvedByPeer = true }
        permissionQueue.forEach { it.choice.complete(null) }
        permissionQueue.clear()
        openRequests.clear()
        _pendingPermission.value = null
    }

    /** Called by the permission dialog; null = user dismissed -> "cancelled". */
    fun resolvePermission(optionId: String?) {
        _pendingPermission.value?.choice?.complete(optionId)
    }

    // ---- transcript assembly ---------------------------------------------------

    private fun refreshRunning() {
        val running = remoteTurn || promptJob?.isActive == true
        val was = _ui.value.running
        if (running == was) return
        if (!running) {
            // Falling edge: stamp the end time on the latest user's turn.
            val now = System.currentTimeMillis()
            val lastUserId = _messages.value.lastOrNull { it.kind == Kind.User }?.id
            if (lastUserId != null) {
                _turnTimes.update { map ->
                    map[lastUserId]?.takeIf { it.end == null }
                        ?.let { map + (lastUserId to it.copy(end = now)) } ?: map
                }
            }
        }
        _ui.update { it.copy(running = running) }
        // The turn that just ended may have been a REMOTE one — there's no
        // local promptJob whose finally would release the queue. Drain on
        // every falling edge or those prompts stall out of order.
        if (!running) drainQueue()
    }

    private fun compactionText(status: String?): String {
        val s = status?.lowercase().orEmpty()
        return when {
            s.contains("fail") || s.contains("error") -> "上下文压缩失败"
            s.contains("complete") || s.contains("done") ||
                s.contains("finish") || s.contains("success") -> "上下文压缩完成"
            s.contains("cancel") -> "上下文压缩已取消"
            else -> "上下文压缩中…"
        }
    }

    private fun appendStreaming(kind: Kind, text: String, live: Boolean = true) {
        if (streamKind != null && (streamKind != kind || streamLive != live)) {
            flushStreaming()
        }
        streamKind = kind
        streamLive = live
        streamBuf.append(text)
        if (streamBuf.length >= FLUSH_CHARS) {
            flushStreaming()
        } else {
            scheduleStreamFlush()
        }
    }

    private fun scheduleStreamFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushStreaming()
        }
    }

    /** Commit buffered chunks into their transcript item. */
    private fun flushStreaming() {
        flushJob?.cancel()
        flushJob = null
        val kind = streamKind
        if (kind == null) {
            streamBuf.setLength(0)
            return
        }
        if (streamBuf.isEmpty()) return
        val chunk = streamBuf.toString()
        streamBuf.setLength(0)
        _messages.update { list ->
            val last = list.lastOrNull()
            if (last != null && last.id == streamingId && last.kind == kind) {
                list.dropLast(1) + last.copy(text = last.text + chunk)
            } else {
                val item = ChatItem(nextItemId++, kind, chunk)
                streamingId = item.id
                if (kind == Kind.User && streamLive) noteTurnStart(item.id)
                capMessages(list + item)
            }
        }
    }

    /**
     * Drop the transcript buffer and every item — used before `session/load`
     * replays history and when the store is reset by a needLoad attach.
     */
    fun resetTranscript() {
        flushJob?.cancel()
        flushJob = null
        streamBuf.setLength(0)
        streamKind = null
        streamingId = null
        planItemId = null
        _messages.value = emptyList()
        _turnTimes.value = emptyMap()
    }

    /** Cap the transcript at [MAX_MESSAGES], keeping the first [KEEP_HEAD] items. */
    private fun capMessages(list: List<ChatItem>): List<ChatItem> {
        if (list.size <= MAX_MESSAGES) return list
        val head = list.take(KEEP_HEAD)
        return head + list.drop(KEEP_HEAD).drop(list.size - MAX_MESSAGES)
    }

    private fun noteTurnStart(itemId: Long) {
        _turnTimes.update { it + (itemId to TurnTiming(System.currentTimeMillis(), null)) }
    }

    fun append(
        kind: Kind,
        text: String,
        status: String? = null,
        toolCallId: String? = null,
        detail: String? = null,
        attachments: List<ImageAttachment> = emptyList(),
    ): ChatItem {
        flushStreaming()
        val item = ChatItem(
            nextItemId++, kind, text, status, toolCallId,
            detail = detail, attachments = attachments,
            attachmentCount = attachments.size,
        )
        streamingId = null
        // A Kind.User here is always a live local echo (replayed user text
        // arrives through the streaming path, gated by streamLive).
        if (kind == Kind.User) noteTurnStart(item.id)
        _messages.update { capMessages(it + item) }
        return item
    }

    private fun markFailed(itemId: Long) {
        _messages.update { list ->
            list.map { if (it.id == itemId) it.copy(failed = true) else it }
        }
    }

    private fun updateTool(call: ToolCallInfo) {
        _messages.update { list ->
            val idx = list.indexOfLast { it.toolCallId == call.toolCallId }
            if (idx < 0) {
                capMessages(
                    list + ChatItem(
                        nextItemId++, Kind.Tool,
                        call.title ?: call.kind ?: "tool call",
                        call.status, call.toolCallId,
                        toolKind = call.kind,
                        detail = toolDetailOf(call),
                        diffs = toolDiffsOf(call),
                    ),
                )
            } else {
                val old = list[idx]
                val diffs = toolDiffsOf(call)
                list.toMutableList().apply {
                    set(idx, old.copy(
                        text = call.title ?: old.text,
                        status = call.status ?: old.status,
                        toolKind = call.kind ?: old.toolKind,
                        detail = toolDetailOf(call) ?: old.detail,
                        diffs = if (diffs.isEmpty()) old.diffs else diffs,
                    ))
                }
            }
        }
    }

    /** `diff` content blocks carry the full old/new file text for DiffCards. */
    private fun toolDiffsOf(call: ToolCallInfo): List<ToolDiff> =
        (call.content as? JsonArray)
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                if (o["type"].strOrNull() != "diff") return@mapNotNull null
                val path = o["path"].strOrNull() ?: return@mapNotNull null
                ToolDiff(
                    path = path,
                    oldText = o["oldText"].strOrNull(),
                    newText = o["newText"].strOrNull() ?: "",
                )
            }
            .orEmpty()

    /** Extract a human-readable preview from a tool call's content/locations. */
    private fun toolDetailOf(call: ToolCallInfo): String? {
        val parts = mutableListOf<String>()
        (call.locations as? JsonArray)?.forEach { loc ->
            (loc as? JsonObject)
                ?.get("path").strOrNull()
                ?.let { parts.add("📄 $it") }
        }
        (call.content as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            when (o["type"].strOrNull()) {
                "content" -> (o["content"] as? JsonObject)
                    ?.get("text").strOrNull()?.let { parts.add(it) }
                "diff" -> o["path"].strOrNull()?.let { parts.add("diff: $it") }
                "terminal" -> (o["output"] ?: o["terminal"]).strOrNull()
                    ?.let { parts.add(it.take(1500)) }
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }?.take(4000)
    }

    private fun kotlinx.serialization.json.JsonElement?.strOrNull(): String? =
        (this as? JsonPrimitive)
            ?.let { if (it.isString) it.content else null }

    private fun upsertPlan(entries: List<PlanEntry>) {
        streamingId = null
        _messages.update { list ->
            val existing = list.indexOfLast { it.id == planItemId }
            if (existing >= 0) {
                list.toMutableList().apply {
                    set(existing, this[existing].copy(planEntries = entries))
                }
            } else {
                val item = ChatItem(nextItemId++, Kind.Plan, "", planEntries = entries)
                planItemId = item.id
                capMessages(list + item)
            }
        }
    }
}
