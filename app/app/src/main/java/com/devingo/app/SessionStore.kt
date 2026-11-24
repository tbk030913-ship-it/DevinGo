package com.devingo.app

import android.util.Base64
import com.devingo.app.acp.AcpClient
import com.devingo.app.acp.AcpEvent
import com.devingo.app.acp.AcpException
import com.devingo.app.acp.AcpMethods
import com.devingo.app.acp.AttachResult
import com.devingo.app.acp.ConfigOption
import com.devingo.app.acp.ContentBlock
import com.devingo.app.acp.ElicitationParser
import com.devingo.app.acp.FsSearchItem
import com.devingo.app.acp.GitDiffResult
import com.devingo.app.acp.InboundEvent
import com.devingo.app.acp.IncomingRequest
import com.devingo.app.acp.JSONRPC_METHOD_NOT_FOUND
import com.devingo.app.acp.MentionResource
import com.devingo.app.acp.PermissionOption
import com.devingo.app.acp.PlanEntry
import com.devingo.app.acp.RequestPermissionParams
import com.devingo.app.acp.RevertExecuteResult
import com.devingo.app.acp.RevertPreview
import com.devingo.app.acp.RevertStep
import com.devingo.app.acp.SessionMode
import com.devingo.app.acp.SessionModel
import com.devingo.app.acp.SessionNewResult
import com.devingo.app.acp.ToolCallInfo
import com.devingo.app.acp.strField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
    /**
     * Fired once when a `_cognition.ai/revert/…` call answers
     * method_not_found — the revert surface doesn't exist on this agent.
     * Connection-level capability; the owner hides the menu entry.
     */
    private val onRevertUnsupported: (() -> Unit)? = null,
    /**
     * Same probe-negative hook as [onRevertUnsupported] but for
     * `_cognition.ai/subagent/…` — hides the action chips (A7.4).
     */
    private val onSubagentUnsupported: (() -> Unit)? = null,
    /**
     * Bridge `/fs/write` bound to this session — (relativePath, content).
     * Null when the bridge URL isn't known; 保存到工作区 reports a failure.
     */
    private val workspaceFileWriter: (suspend (String, String) -> Unit)? = null,
    /**
     * Bridge `/git/diff` bound to this session — returns the workspace's
     * uncommitted patch. Null when the bridge URL isn't known (N6.1).
     */
    private val gitDiffFetcher: (suspend () -> GitDiffResult)? = null,
    /**
     * Bridge `/fs/list` bound to this session — the workspace's file paths
     * for the composer's @file picker. Null without a bridge URL (N6.2).
     */
    private val workspaceFileLister: (suspend () -> List<String>)? = null,
    /**
     * Bridge `/fs/search` bound to this session — fuzzy file+dir matches
     * for the @-picker's typed query (A7.3). Null without a bridge URL.
     */
    private val workspaceSearcher: (suspend (String) -> List<FsSearchItem>)? = null,
) {

    private companion object {
        /** Keep at most this many transcript items; oldest beyond the head are dropped. */
        const val MAX_MESSAGES = 1500
        /** Items at the head (session banner, first user prompt) that trimming keeps. */
        const val KEEP_HEAD = 10
        /** Streaming chunks flush to the list at most every this many ms… */
        const val FLUSH_INTERVAL_MS = 60L
        /** …relaxed to this once the target item passes [STREAM_RELAX_CHARS]. */
        const val FLUSH_INTERVAL_SLOW_MS = 120L
        /** ~4KB — the message size that switches to the relaxed flush cadence. */
        const val STREAM_RELAX_CHARS = 4_096
        /** …or immediately once the buffer holds this many chars. */
        const val FLUSH_CHARS = 200
        /** Attach-backlog staging ends on cursor coverage, else this watchdog. */
        const val STAGED_WATCHDOG_MS = 20_000L
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
        /**
         * Draft cap — the text also rides SavedStateHandle (a Bundle) and
         * DataStore, so a paste-bomb must be bounded before it fans out.
         */
        const val MAX_DRAFT_CHARS = 64_000
        /** Per-tool detail cap; exec output keeps its tail within this. */
        const val TOOL_DETAIL_CAP = 4_000
        /** Subagent statuses that must never regress to a live one (A7.4). */
        private val SUBAGENT_TERMINAL = setOf("completed", "failed", "stopped")
        /** Default workspace path the plan card's 保存到工作区 writes. */
        const val PLAN_FILE_NAME = "plan.md"
        /**
         * `@{kind}:` send-time scanner — the same kind vocabulary as the
         * UI chip regex in ContextChips (A7.3). The ref itself is resolved
         * per-match: registered picker refs can contain spaces; a typed
         * mention stays one whitespace-free word.
         */
        val SEND_MENTION_TOKEN = Regex(
            "@(file|folder|directory|rule|skill|plan|session|conversation|git|" +
                "codemap|url|image|doc|symbol|task|terminal|web|issue|" +
                "pull-request|review-comment):",
        )

        /** Bare `@token` candidates inside a text segment (N6.2). */
        val BARE_MENTION_TOKEN = Regex("@\\S+")
        /** ACP `tool_call.kind` values — the vocabulary toolKind is normalized to. */
        val ACP_TOOL_KINDS = setOf(
            "read", "edit", "delete", "move", "search",
            "execute", "think", "fetch", "switch_mode", "other",
        )
    }

    /**
     * Highest `_seq` observed on a journaled frame for this session.
     * `seedSeq` runs on OkHttp's dispatch thread while `noteSeq` runs on
     * Main — @Volatile alone wouldn't make the read-modify-write atomic.
     */
    private val seqCursor = java.util.concurrent.atomic.AtomicLong(0)
    val lastSeq: Long get() = seqCursor.get()

    /** Subscribed at the gateway (attach done, or new/load auto-subscribed).
     *  Mirrored into [SessionUi.attached] so a dead subscription on a live
     *  socket is visible to the UI (ConnBanner), not just to open paths. */
    @Volatile var attached: Boolean = false
        private set(value) {
            field = value
            _ui.update { it.copy(attached = value) }
        }

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

    /**
     * Row model (rows/lookups) rebuilt off the main thread — see
     * [rebuildModel]. Emits only on structural change; the still-growing
     * tail item's text rides [streamingTail].
     */
    private val _transcript = MutableStateFlow(TranscriptModel.EMPTY)
    val transcript = _transcript.asStateFlow()

    /** The tail item while its text grows — the UI overrides the model's
     *  (possibly stale) copy of that one item with it. */
    private val _streamingTail = MutableStateFlow<ChatItem?>(null)
    val streamingTail = _streamingTail.asStateFlow()

    private val _pendingPermission = MutableStateFlow<PendingPermission?>(null)
    val pendingPermission = _pendingPermission.asStateFlow()

    /** Depth of the internal queue — feeds the notification's "N 个审批" count. */
    private val _pendingPermissionCount = MutableStateFlow(0)
    val pendingPermissionCount = _pendingPermissionCount.asStateFlow()

    /** The elicitation card on screen (queue head, like [pendingPermission]). */
    private val _pendingElicitation = MutableStateFlow<PendingElicitation?>(null)
    val pendingElicitation = _pendingElicitation.asStateFlow()

    private val _pendingElicitationCount = MutableStateFlow(0)
    val pendingElicitationCount = _pendingElicitationCount.asStateFlow()

    private val _queuedPrompts = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val queuedPrompts = _queuedPrompts.asStateFlow()

    /**
     * U5.4: the queued prompt pulled back into the composer for editing —
     * keyed on the item's id so queue removals elsewhere can't shift the
     * target. [queuedEditStash] holds the draft it displaced; cancel/save
     * restore it (a drain that sends the edited item instead keeps the
     * typed text as the new draft — the user's edits are never dropped).
     */
    private val _editingQueuedId = MutableStateFlow<Long?>(null)
    val editingQueuedId = _editingQueuedId.asStateFlow()
    private var queuedEditStash: Triple<String, List<ImageAttachment>, CitationAttachment?>? = null
    private val nextQueuedId = java.util.concurrent.atomic.AtomicLong(0)

    /** Steps offered by the revert sheet; null until [loadRevertSteps] runs. */
    private val _revertSteps = MutableStateFlow<List<RevertStep>?>(null)
    val revertSteps = _revertSteps.asStateFlow()

    /** Turn start/end keyed on the user message's item id. */
    private val _turnTimes = MutableStateFlow<Map<Long, TurnTiming>>(emptyMap())
    val turnTimes = _turnTimes.asStateFlow()

    // ---- subagents (A7.4) ------------------------------------------------
    // task/subagent tool calls never become transcript items — they live
    // here and feed the work-log card, the capsule count and the sheets.

    /** Live view of the session's spawned subagents, spawn order kept. */
    private val _subagents = MutableStateFlow<List<Subagent>>(emptyList())
    val subagents = _subagents.asStateFlow()

    /**
     * Wire-reported `cognition.ai/numLiveSubagents`; -1 until first report.
     * The capsule prefers it over the locally-derived live count.
     */
    private val _numLiveSubagents = MutableStateFlow(-1)
    val numLiveSubagents = _numLiveSubagents.asStateFlow()

    /** Composer drafts survive switching away and back. */
    private val _draft = MutableStateFlow("")
    val draft = _draft.asStateFlow()
    private val _draftAttachments = MutableStateFlow<List<ImageAttachment>>(emptyList())
    val draftAttachments = _draftAttachments.asStateFlow()

    /**
     * The quote pinned to the composer — merged into the prompt text as a
     * `>` block (plus its comment) on send/steer, so it survives queueing
     * without the queued prompt needing a separate field.
     */
    private val _draftCitation = MutableStateFlow<CitationAttachment?>(null)
    val draftCitation = _draftCitation.asStateFlow()

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

    // Replay staging (U7): while an attach/load replay runs, transcript
    // mutations land in [staged] — a copy for attach (backlog appends onto
    // history) or a fresh list for session/load — and swap into [_messages]
    // atomically when the replay finishes. `currentMessages` is what
    // transcript logic should read/write. beginReplay runs on the attach
    // coroutine while events mutate on the socket thread — @Volatile so the
    // routing read sees the staged copy the moment it's installed.
    @Volatile private var staged: MutableList<ChatItem>? = null
    private val currentMessages: List<ChatItem> get() = staged ?: _messages.value
    /** Seq the attach backlog must reach before the staged copy commits. */
    @Volatile private var stagedUntilSeq: Long = 0
    @Volatile private var stagedJob: Job? = null
    /** User-item ids of the pre-load transcript — the finishLoad timing remap. */
    private var preLoadUserIds: List<Long> = emptyList()

    // Row-model builder state — only ever touched on the Default dispatcher.
    private var modelMsgs: List<ChatItem> = emptyList()
    private var modelRunning = false
    private var modelQueued: List<QueuedPrompt> = emptyList()

    private var nextItemId = 0L
    private var planItemId: Long? = null
    private var promptJob: Job? = null
    private var steerJob: Job? = null
    /** A remote client's turn observed streaming (no local promptJob). */
    private var remoteTurn = false
    /** True once any turn activity (chunk/tool/plan) arrived for the in-flight prompt. */
    @Volatile private var promptAcked = false

    // Pending agent→client requests queue internally; the UI only ever
    // sees the head of each queue. [requestLock] guards both maps + queues —
    // the peer-resolution path must not interleave with pop/remove.
    private val requestLock = Any()
    private val permissionQueue = ArrayDeque<PendingPermission>()
    /** Wire request id -> open request; attach re-deliveries dedupe on it. */
    private val openRequests = mutableMapOf<Long, PermEntry>()
    private val elicitationQueue = ArrayDeque<PendingElicitation>()
    private val openElicitations = mutableMapOf<Long, ElicEntry>()
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

    private class ElicEntry(
        var req: IncomingRequest,
        val pending: PendingElicitation,
        /** Peer answer or `elicitation/complete` — respond nothing. */
        var resolvedByPeer: Boolean = false,
    )

    init {
        // U2: the transcript row model lives off the main thread. A rebuild
        // runs per messages/running change; [rebuildModel] skips emission
        // entirely when only the tail item's text grew.
        scope.launch(Dispatchers.Default) {
            combine(
                _messages,
                _ui.map { it.running }.distinctUntilChanged(),
                _queuedPrompts,
                // A7.4: a subagent-only turn needs a WorkRow once settled —
                // only the owning-turn set is structural for the model.
                _subagents.map { l -> l.mapTo(HashSet()) { it.turnId } }
                    .distinctUntilChanged(),
            ) { msgs, running, queued, subTurns ->
                ModelInput(msgs, running, queued, subTurns)
            }.collect { (msgs, running, queued, subTurns) ->
                rebuildModel(msgs, running, queued, subTurns)
            }
        }
    }

    /**
     * Maintain [_transcript]/[_streamingTail]. The high-frequency update
     * during streaming is a flush that only appended text to the last item —
     * detected as: same size, identical prefix items, last item differing
     * only in `text`. That path emits the item on [_streamingTail] and
     * leaves the model (and every other row) untouched; all other shapes
     * rebuild the full model.
     */
    private data class ModelInput(
        val msgs: List<ChatItem>,
        val running: Boolean,
        val queued: List<QueuedPrompt>,
        val subagentTurnIds: Set<Long>,
    )

    private fun rebuildModel(
        msgs: List<ChatItem>,
        running: Boolean,
        queued: List<QueuedPrompt>,
        subagentTurnIds: Set<Long> = emptySet(),
    ) {
        val old = modelMsgs
        if (queued == modelQueued && running == modelRunning &&
            msgs.size == old.size && msgs.isNotEmpty()
        ) {
            val nl = msgs.last()
            val ol = old.last()
            if (nl.id == ol.id && nl !== ol && nl.copy(text = ol.text) == ol) {
                var structural = false
                for (i in 0 until msgs.size - 1) {
                    if (msgs[i] !== old[i]) {
                        structural = true
                        break
                    }
                }
                if (!structural) {
                    modelMsgs = msgs
                    _streamingTail.value = nl
                    return
                }
            }
        }
        modelMsgs = msgs
        modelRunning = running
        modelQueued = queued
        _transcript.value = buildTranscriptModel(msgs, running, queued, subagentTurnIds)
        _streamingTail.value = msgs.lastOrNull()
    }

    // ---- journal cursor / attach bookkeeping -----------------------------------

    /**
     * Journaled seqs consumed OFF the ordered demux — agent->client
     * requests held open for an answer, `_devingo/event` frames surfacing
     * nothing — parked here by [seedSeq] until the cursor can cover them
     * contiguously. They are delivered frames, never withheld traffic, so
     * advancing over them is always truthful.
     */
    private val consumedSeqs = java.util.concurrent.ConcurrentSkipListSet<Long>()

    /**
     * Serializes seqCursor read-modify-write against [consumedSeqs] —
     * [noteSeq] runs on the Main demux while [seedSeq] runs on OkHttp's
     * dispatch thread, and a remove-then-advance split across them could
     * overshoot the cursor by one. The coarse rewinds
     * (`seqCursor.set(replayBaseSeq)`/`set(cachedSeq)`) deliberately stay
     * outside: parked seqs above a rewound cursor just drain later.
     */
    private val seqLock = Any()

    /** Record a journaled `_seq`; the skip-ahead check lives in [liveGap]. */
    fun noteSeq(seq: Long) {
        if (seq <= 0) return
        synchronized(seqLock) {
            seqCursor.updateAndGet { maxOf(it, seq) }
            // Entries below the cursor are covered — drop them (they only
            // pile up after a rewind). Strictly-below bound: the entry AT
            // cursor+1 must drain through advanceCursor, not be swept.
            consumedSeqs.headSet(seqCursor.get() + 1).clear()
            advanceCursor()
        }
        commitIfCaughtUp(seq)
    }

    /** Advance over parked seqs the cursor now reaches; adjacency only. */
    private fun advanceCursor() {
        while (consumedSeqs.remove(seqCursor.get() + 1)) {
            seqCursor.incrementAndGet()
        }
    }

    /**
     * The staged copy commits once a frame at/past the backlog end lands.
     * The commit hops onto [scope]: agent requests arrive on AcpClient's
     * Default scope, and [commitStaged]'s read-clear-write on `staged` is
     * not atomic — off-scope it can interleave with [mutateMessages] and
     * resurrect a ghost staged copy. The posted commit re-checks
     * [stagedUntilSeq] so one queued across a new replay can't commit the
     * fresh staged copy early.
     */
    private fun commitIfCaughtUp(seq: Long) {
        val until = stagedUntilSeq
        if (until > 0 && seq >= until) {
            scope.launch { if (stagedUntilSeq == until) commitStaged() }
        }
    }

    /** Swap the staged transcript in atomically; no-op outside staging. */
    private fun commitStaged() {
        val s = staged ?: return
        staged = null
        stagedUntilSeq = 0
        stagedJob?.cancel()
        stagedJob = null
        _messages.value = s
    }

    /**
     * Backlog coverage should end within a handful of seconds; a straggler
     * that never lands must not freeze the transcript on the pre-replay
     * copy — commit whatever staged by then.
     */
    private fun armStagedWatchdog() {
        stagedJob?.cancel()
        stagedJob = scope.launch {
            delay(STAGED_WATCHDOG_MS)
            commitStaged()
        }
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
     *
     * Non-adjacent seqs park in [consumedSeqs] instead of being dropped:
     * a request seq recorded while earlier frames sit deferred (attach
     * window) drains once their routing notes them, so the request never
     * reads as a hole either way.
     */
    fun seedSeq(seq: Long) {
        if (seq <= 0) return
        synchronized(seqLock) {
            if (seq <= seqCursor.get()) return
            consumedSeqs.add(seq)
            advanceCursor()
        }
    }

    /** The store is subscribed and its replay boundary is known. */
    fun markAttached(result: AttachResult) {
        attached = true
        liveSeqFloor = maxOf(liveSeqFloor, result.lastSeq)
        // noteSeq BEFORE stagedUntilSeq is set — a result at the cursor
        // must not trip the coverage check early.
        noteSeq(result.lastSeq)
        result.session?.let { applySessionConfig(it) }
        remoteTurn = result.running
        refreshRunning()
        // The hub answers attach BEFORE streaming the backlog: keep the
        // staged copy until the cursor's frames have all landed (or the
        // watchdog commits what arrived). No backlog → commit right away.
        stagedUntilSeq = result.lastSeq
        if (replayBaseSeq >= result.lastSeq) commitStaged() else armStagedWatchdog()
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
        // Replay appends into a staged copy of the visible transcript; the
        // UI swaps to it atomically when the backlog finishes instead of
        // scrolling item by item.
        stagedJob?.cancel()
        stagedUntilSeq = 0
        staged = currentMessages.toMutableList()
    }

    fun endReplay() {
        replaying = false
        attachDefer = false
        // markAttached/finishLoad flipped attached on success — the staged
        // copy then stays live until the backlog commits it. A still-false
        // flag means the attach aborted mid-stream: discard the partial
        // copy and rewind the cursor so a retry re-covers it.
        if (!attached) {
            staged = null
            stagedUntilSeq = 0
            stagedJob?.cancel()
            seqCursor.set(replayBaseSeq)
        }
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

    /**
     * `session/load` replays the WHOLE history into a fresh staged list —
     * the visible (possibly cache-restored) transcript stays put until
     * [finishLoad] swaps it atomically. Pre-load user ids are captured for
     * the turn-timing remap.
     */
    fun beginLoad() {
        replaying = true
        attachDefer = false
        flushJob?.cancel()
        flushJob = null
        streamBuf.setLength(0)
        streamKind = null
        streamingId = null
        planItemId = null
        // needLoad means the hub can't serve our cursor — a truncated
        // ring, or a journal that restarted wholesale behind our back
        // (idle eviction + lazy re-create, or a bridge restart: seqs go
        // back to 1). Reset the seq machinery either way: replayed frames
        // re-advance the cursor onto the live epoch, and a stale
        // cursor/floor can no longer misread every new-epoch live frame
        // below the old high-water as history.
        synchronized(seqLock) {
            seqCursor.set(0)
            consumedSeqs.clear()
        }
        liveSeqFloor = 0
        // The load replay re-delivers every subagent tool call — start the
        // list fresh so it rebuilds against the new transcript's turn ids.
        _subagents.value = emptyList()
        _numLiveSubagents.value = -1
        preLoadUserIds = currentMessages.filter { it.kind == Kind.User }.map { it.id }
        staged = mutableListOf()
    }

    /** `session/load` finished: timings remapped, staged copy committed.
     *  [running] is the attach result's hub-side turn state — the needLoad
     *  path is the only attach that doesn't pass through [markAttached],
     *  so without it a running turn would wait for the next live frame. */
    fun finishLoad(result: SessionNewResult, running: Boolean) {
        remapLoadTimings()
        commitStaged()
        replaying = false
        attached = true
        remoteTurn = running
        refreshRunning()
        // Replay frames still landing past the response are history — pin the
        // live floor at the cursor so they can't masquerade as a live turn.
        liveSeqFloor = maxOf(liveSeqFloor, lastSeq)
        applySessionConfig(result)
    }

    /**
     * Carry pre-load turn timings onto the fresh replay's new item ids (U9).
     * User messages replay in the same order, so a positional pair is safe
     * when the histories agree in count; timings recorded live during the
     * load window (a mid-load send) always keep their own ids.
     */
    private fun remapLoadTimings() {
        val newUsers = staged.orEmpty().filter { it.kind == Kind.User }
        val old = _turnTimes.value
        val remapped = HashMap<Long, TurnTiming>(newUsers.size)
        newUsers.forEach { n -> old[n.id]?.let { remapped[n.id] = it } }
        if (preLoadUserIds.size == newUsers.size) {
            newUsers.forEachIndexed { i, n ->
                if (n.id !in remapped) {
                    old[preLoadUserIds[i]]?.let { remapped[n.id] = it }
                }
            }
        }
        _turnTimes.value = remapped
        preLoadUserIds = emptyList()
    }

    /** Tear down: queued prompts, pending perms and jobs all die with the store. */
    fun drop() {
        promptJob?.cancel()
        steerJob?.cancel()
        clearPermissions()
        clearElicitations()
        deferredLive.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    // ---- events ---------------------------------------------------------------

    /** True for live traffic — never attach/load replay backlog. A seq-0
     *  frame is by definition unjournaled, so it can't BE backlog: it is
     *  live even when it lands inside a replay window. */
    fun isLive(seq: Long): Boolean = seq == 0L || (!replaying && seq > liveSeqFloor)

    /**
     * A journaled frame [seq] skipped ahead of the cursor while live — the
     * withheld window only returns via `_devingo/attach`. The caller must
     * NOT consume this frame inline: the replay re-delivers it (and the
     * skipped window) in order. Replay backlog never reports a gap.
     */
    fun liveGap(seq: Long): Boolean = attached && isLive(seq) && seq > lastSeq + 1

    private fun remoteTurnActivity() {
        // Our own jobs speak for the turn — remote frames streaming during
        // a prompt/steer mustn't latch remoteTurn, or it outlives the job.
        if (!remoteTurn && promptJob?.isActive != true &&
            steerJob?.isActive != true
        ) {
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
                val lastUser = currentMessages.lastOrNull { it.kind == Kind.User }
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
                if (_subagents.value.any { it.toolCallId == ev.call.toolCallId }) {
                    // Known subagent call re-delivered without its meta —
                    // fold into the subagent list, never the transcript.
                    upsertSubagent(ev.call)
                } else {
                    val detail = toolDetailOf(ev.call)
                    append(
                        Kind.Tool,
                        toolTitleOf(ev.call) ?: ev.call.kind ?: "tool call",
                        status = ev.call.status ?: "pending",
                        toolCallId = ev.call.toolCallId,
                        toolKind = toolKindOf(ev.call),
                        detail = detail?.text,
                        detailTruncated = detail?.headTruncated == true,
                        exitCode = exitCodeOf(ev.call),
                    )
                }
            }
            is AcpEvent.ToolCallUpdate -> {
                promptAcked = true
                if (isLive(seq)) remoteTurnActivity()
                updateTool(ev.call)
            }
            is AcpEvent.SubagentUpdate -> {
                promptAcked = true
                if (isLive(seq)) remoteTurnActivity()
                // A call first classified as a plain tool left a transcript
                // row behind — pull it now that it's known to be a subagent.
                removeToolRow(ev.call.toolCallId)
                upsertSubagent(ev.call)
            }
            is AcpEvent.SubagentCount -> _numLiveSubagents.value = ev.count
            is AcpEvent.SubagentCtxFrame -> {
                // A descendant agent's frame multiplexed onto our stream —
                // append to the matching subagent's live output. Unknown
                // ctx ids drop here rather than land as parent rows (t3
                // routes them out of the parent's view entirely).
                _subagents.update { list ->
                    val idx = list.indexOfFirst {
                        it.id == ev.agentId || it.toolCallId == ev.agentId ||
                            it.childSessionId == ev.agentId
                    }
                    if (idx < 0) {
                        list
                    } else {
                        val a = list[idx]
                        val merged = (if (ev.newLine && a.detail != null) {
                            a.detail + "\n" + ev.text
                        } else {
                            (a.detail ?: "") + ev.text
                        }).takeLast(TOOL_DETAIL_CAP)
                        list.toMutableList().apply { set(idx, a.copy(detail = merged)) }
                    }
                }
            }
            is AcpEvent.ToolContentChunk -> {
                if (_subagents.value.any { it.toolCallId == ev.call.toolCallId }) {
                    upsertSubagent(ev.call)
                } else {
                    val txt = toolDetailOf(ev.call)?.text
                    if (txt != null) {
                        mutateMessages { list ->
                            val idx = list.indexOfLast {
                                it.toolCallId == ev.call.toolCallId
                            }
                            if (idx < 0) {
                                list
                            } else {
                                list.toMutableList().apply {
                                    set(idx, this[idx].copy(
                                        detail = ((this[idx].detail ?: "") + txt)
                                            .takeLast(TOOL_DETAIL_CAP),
                                    ))
                                }
                            }
                        }
                    }
                }
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
            is AcpEvent.ElicitationComplete -> {
                // A journaled complete must not resolve a live pending card.
                if (isLive(seq)) dismissElicitation(ev.elicitationId)
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
        clearElicitations()
        remoteTurn = false
        // A mid-replay drop — or a drop while a committed attach's backlog
        // is still staged — leaves the cursor past frames that only ever
        // reached the staged copy: rewind so the reconnect attach re-covers
        // them, and discard the staged copy (the same rewind re-covers it).
        if (replaying || staged != null) {
            seqCursor.set(replayBaseSeq)
            replaying = false
        }
        staged = null
        stagedUntilSeq = 0
        stagedJob?.cancel()
        failInflightTools()
        refreshRunning()
        append(Kind.System, "$reason，重连后自动补齐")
    }

    private fun failInflightTools() {
        mutateMessages { list ->
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

    // ---- subagents (A7.4) ------------------------------------------------------

    /**
     * Upsert a `subagent`/`task` tool call into [_subagents]. Spawn order is
     * kept; terminal updates freeze [Subagent.result] and [Subagent.endedAt].
     */
    private fun upsertSubagent(call: ToolCallInfo) {
        val meta = call._meta
        // Documented Devin shape (t3 devinAcp.ts): spawn/completion ride as
        // _meta OBJECTS on the call — `subagent_started`/`subagent_completed`
        // — whose agentId IS the child session id. The flat keys below are
        // fallbacks for builds that predate that shape.
        val started = meta?.get("cognition.ai/subagent_started") as? JsonObject
        val completed = meta?.get("cognition.ai/subagent_completed") as? JsonObject
        val wire = completed ?: started
        val agentId = wire?.get("agentId").strOrNull()
        // A late frame may finally name the wire id — upgrade to it so
        // control calls (cancel/background) address the agent, not the
        // toolCallId.
        val wireId = agentId
            ?: subagentMetaStr(call, "cognition.ai/sharedSubagentId")
        val status = when {
            completed != null ->
                if ((completed["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                    "failed"
                } else {
                    "completed"
                }
            started != null ->
                if ((started["isBackground"] as? JsonPrimitive)?.booleanOrNull == true) {
                    "background"
                } else {
                    "working"
                }
            else -> normalizeSubagentStatus(
                subagentMetaStr(call, "cognition.ai/agentStatus") ?: call.status,
            )
        }
        val prompt = started?.get("task").strOrNull()
            ?.takeIf { it.isNotBlank() } ?: subagentPromptOf(call)
        // "Tool"/"Ran …" placeholders mustn't overwrite a real title.
        val title = (started?.get("title").strOrNull() ?: call.title)
            ?.takeIf { it.isNotBlank() && it != "Tool" }
            ?.subagentDisplayTitle()
        val wireResult = completed?.get("summary").strOrNull()
            ?.takeIf { it.isNotBlank() }
        // Progress rides _meta when the wire sends it, else the call's
        // content blocks carry the output text.
        val detail = wireResult ?: subagentMetaStr(call, "cognition.ai/progress")
            ?: toolDetailOf(call)?.text
        _subagents.update { list ->
            // Frames disagree on which id they carry — match the stable
            // toolCallId too or the entry would fork in two.
            val idx = list.indexOfFirst {
                it.toolCallId == call.toolCallId ||
                    (wireId != null && it.id == wireId) ||
                    it.id == call.toolCallId ||
                    (agentId != null && it.childSessionId == agentId)
            }
            val old = list.getOrNull(idx)
            // Terminal states never regress — a straggler in_progress frame
            // after completion is wire noise, not a resurrected agent.
            val effStatus = when {
                old?.status in SUBAGENT_TERMINAL &&
                    status !in SUBAGENT_TERMINAL -> old!!.status
                else -> status ?: old?.status ?: "pending"
            }
            val terminal = effStatus in SUBAGENT_TERMINAL
            val now = System.currentTimeMillis()
            val next = Subagent(
                id = wireId ?: old?.id ?: call.toolCallId,
                toolCallId = call.toolCallId,
                title = title ?: old?.title
                    ?: prompt?.lineSequence()?.firstOrNull()?.take(80)
                    ?: "子代理",
                status = effStatus,
                prompt = prompt ?: old?.prompt,
                detail = detail ?: old?.detail,
                result = wireResult
                    ?: if (terminal) detail ?: old?.detail ?: old?.result
                    else old?.result,
                startedAt = old?.startedAt ?: now,
                endedAt = if (terminal) old?.endedAt?.takeIf { it > 0 } ?: now
                else 0L,
                childSessionId = agentId ?: subagentChildSessionId(call)
                    ?: old?.childSessionId,
                childSessionUrl = subagentMetaStr(
                    call, "cognition.ai/childSessionUrl",
                ) ?: old?.childSessionUrl,
                turnId = old?.turnId
                    ?: currentMessages.lastOrNull { it.kind == Kind.User }?.id
                    ?: 0L,
            )
            if (idx < 0) list + next
            else list.toMutableList().apply { set(idx, next) }
        }
    }

    /**
     * `_meta[key]` as a string — a bare primitive, or an object whose
     * `status`/`state`/`id`/`url`/`sessionId` member carries the value.
     */
    private fun subagentMetaStr(call: ToolCallInfo, key: String): String? =
        when (val v = call._meta?.get(key)) {
            is JsonPrimitive -> v.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonObject -> v.strField("status", "state", "id", "url", "sessionId")
            else -> null
        }

    /** `cognition.ai/childSessionId`, else the childSessionUrl's tail. */
    private fun subagentChildSessionId(call: ToolCallInfo): String? =
        subagentMetaStr(call, "cognition.ai/childSessionId")
            ?: subagentMetaStr(call, "cognition.ai/childSessionUrl")
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }

    /** The task prompt out of `rawInput` (Task-style calls carry `prompt`). */
    private fun subagentPromptOf(call: ToolCallInfo): String? =
        (call.rawInput as? JsonObject)
            ?.strField("prompt", "instruction", "task", "description", "input")
            ?.takeIf { it.isNotBlank() }

    /** t3's `formatSubagentDisplayTitle`: drop the "Subagent:" prefix. */
    private fun String.subagentDisplayTitle(): String =
        replace(Regex("^Subagent:\\s*", RegexOption.IGNORE_CASE), "")

    /**
     * Wire status onto pending/working/background/completed/failed/stopped —
     * covers both ACP tool_call statuses (pending/in_progress/completed/
     * failed) and Devin's richer `agentStatus` vocabulary.
     */
    private fun normalizeSubagentStatus(raw: String?): String? =
        when (raw?.lowercase()) {
            null -> null
            "in_progress", "running", "working", "active",
            "waiting", "idle", "foreground" -> "working"
            "background", "backgrounded" -> "background"
            "completed", "complete", "done", "finished",
            "success", "succeeded" -> "completed"
            "failed", "error", "errored" -> "failed"
            "cancelled", "canceled", "stopped", "interrupted", "killed" -> "stopped"
            else -> "pending"
        }

    /** `_cognition.ai/subagent/cancel` — stop a live subagent. */
    fun subagentCancel(id: String) =
        subagentAction(id) { client.subagentCancel(sessionId, it) }

    /** `_cognition.ai/subagent/background` — move off the foreground. */
    fun subagentBackground(id: String) =
        subagentAction(id) { client.subagentBackground(sessionId, it) }

    /** `_cognition.ai/subagent/foreground` — bring back to the foreground. */
    fun subagentForeground(id: String) =
        subagentAction(id) { client.subagentForeground(sessionId, it) }

    private fun subagentAction(
        subagentId: String,
        call: suspend (String) -> Unit,
    ) {
        scope.launch {
            try {
                call(subagentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // -32601 answers the capability probe — the surface isn't
                // there on this build; hide the action chips like revert's.
                if ((e as? AcpException)?.code == JSONRPC_METHOD_NOT_FOUND) {
                    onSubagentUnsupported?.invoke()
                }
                append(Kind.Error, "子代理操作失败: ${e.display()}")
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
        _draft.value = text.take(MAX_DRAFT_CHARS)
    }

    /**
     * Pin/replace/clear the composer's quote (null clears). A blank quote is
     * kept — clearing the field mid-edit in the citation sheet must not yank
     * the sheet out from under the user's cursor; [mergedWithCitation]
     * already treats an empty quote as no citation.
     */
    fun setDraftCitation(citation: CitationAttachment?) {
        _draftCitation.value = citation
    }

    /**
     * Add a review comment to the composer. The first pins the citation
     * slot; later ones fold their quote block into the draft text rather
     * than silently overwriting the pinned citation.
     */
    fun addReviewCitation(citation: CitationAttachment) {
        if (_draftCitation.value == null) {
            _draftCitation.value = citation
            return
        }
        val block = mergedWithCitation("", citation).trimEnd()
        val cur = _draft.value
        _draft.value = (if (cur.isBlank() || cur.endsWith("\n")) cur else "$cur\n\n")
            .plus(block).take(MAX_DRAFT_CHARS)
    }

    /** The workspace's uncommitted `git diff` result (Git 未提交变更 scope). */
    suspend fun fetchGitDiff(): GitDiffResult {
        val fetcher = gitDiffFetcher
            ?: throw IllegalStateException("git diff unavailable without a bridge")
        return fetcher()
    }

    /** The workspace's file paths for the composer's @file picker (N6.2). */
    suspend fun fetchWorkspaceFiles(): List<String> {
        val lister = workspaceFileLister
            ?: throw IllegalStateException("file list unavailable without a bridge")
        return lister()
    }

    /**
     * The bridge's fuzzy workspace search — the @-picker's typed-query
     * feed of files AND directories (A7.3). No bridge → empty.
     */
    suspend fun searchWorkspace(query: String): List<FsSearchItem> =
        workspaceSearcher?.invoke(query) ?: emptyList()

    /**
     * The agent's mentionable resources — devin's
     * `_cognition.ai/{rules,skills,plans}/list` behind one call so the
     * picker can fan out per kind. An agent without the surface answers
     * method_not_found; the caller treats that as an empty category (A7.3).
     */
    suspend fun mentionResources(kind: String): List<MentionResource> = when (kind) {
        "rule" -> client.listRules(sessionId)
        "skill" -> client.listSkills(sessionId)
        "plan" -> client.listPlans(sessionId)
        else -> emptyList()
    }

    fun setDraftAttachments(attachments: List<ImageAttachment>) {
        // Single enforcement point — keeps what fits under the send cap so
        // the queue-edit path can't park an over-limit payload either.
        var sum = 0L
        val kept = attachments.filter { a ->
            (sum + a.bytes.size <= MAX_ATTACHMENT_BYTES).also { if (it) sum += a.bytes.size }
        }
        if (kept.size < attachments.size) {
            append(Kind.Error, "图片合计超过 3MB，超出的未添加")
        }
        _draftAttachments.value = kept
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
    fun send(
        text: String,
        attachments: List<ImageAttachment> = emptyList(),
        mergeCitation: Boolean = true,
    ): Boolean {
        val citation = if (mergeCitation) _draftCitation.value else null
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && attachments.isEmpty() && citation == null) return false
        val total = attachments.sumOf { it.bytes.size.toLong() }
        if (total > MAX_ATTACHMENT_BYTES) {
            append(Kind.Error, "图片合计超过 3MB，未发送")
            return false
        }
        val merged = mergedWithCitation(trimmed, citation)
        // A citation whose quote was blanked merges to nothing — don't
        // ship an empty prompt; the draft keeps its state so the user
        // can fix the quote or drop the chip.
        if (merged.isBlank() && attachments.isEmpty()) return false
        if (mergeCitation) _draftCitation.value = null
        if (_ui.value.running || promptJob?.isActive == true) {
            // Queued prompts carry the attachments themselves — the bytes go
            // out when the queue drains, nothing is dropped.
            _queuedPrompts.update {
                it + QueuedPrompt(nextQueuedId.getAndIncrement(), merged, attachments)
            }
            return true
        }
        launchPrompt(merged, attachments)
        return true
    }

    /**
     * Render a draft citation as a leading markdown quote block — the same
     * text-only form t3code's citation sends, readable to the agent without
     * any new content-block type. Comment sits under the quote, then the
     * user's own text.
     */
    private fun mergedWithCitation(text: String, citation: CitationAttachment?): String {
        val quote = citation?.quotedText?.trim().orEmpty()
        if (quote.isEmpty()) return text
        return buildString(quote.length + text.length) {
            quote.lines().forEach { line -> append("> ").appendLine(line.trimEnd()) }
            if (citation != null && citation.comment.isNotBlank()) {
                append(">\n> ").appendLine(citation.comment.trim())
            }
            if (text.isNotEmpty()) appendLine() // blank line ends the quote block
            append(text)
        }
    }

    /**
     * Refs the @-picker inserted — `@{kind}:` + a ref that may contain
     * spaces can't be re-derived from the draft text at send time.
     * Insertion-ordered and bounded: a stale hint must never grow the set
     * unbounded across a long session.
     */
    private val mentionRefHints = LinkedHashSet<String>()

    /** The picker inserted `@{kind}:{ref}` — remember the verbatim ref. */
    fun registerMentionRef(ref: String) {
        if (!mentionRefHints.add(ref) || mentionRefHints.size <= 200) return
        // Over the cap: evict the oldest — a hint from hundreds of picks
        // ago is likelier stale than recent ones.
        mentionRefHints.remove(mentionRefHints.first())
    }

    /** Interrupt the running turn with a new instruction right now. */
    fun steer(text: String) {
        val trimmed = text.trimEnd()
        val merged = mergedWithCitation(trimmed, _draftCitation.value).trimEnd()
        // Blank text + a blanked quote = nothing to steer; keep the draft.
        if (merged.isBlank()) return
        _draftCitation.value = null
        steerMerged(merged)
    }

    /** Send an already-merged steer — a queued item's text carries its own quote block. */
    private fun steerMerged(merged: String) {
        append(Kind.User, merged, steered = true)
        promptAcked = true // the turn is live; a steer failure isn't a lost echo
        steerJob = scope.launch {
            remoteTurn = false // our own call now speaks for the turn
            refreshRunning()
            try {
                // Same block assembly as send — @-mentions steer as
                // resource_links, not literal tokens.
                reportStopReason(
                    client.promptBlocks(
                        sessionId,
                        buildBlocks(merged, emptyList()),
                    ).stopReason,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "打断失败: ${e.display()}")
            } finally {
                // Our own prompt response never returns journaled — this
                // job's end IS the turn's end. Drop any remoteTurn latch a
                // post-job chunk set so running can't wedge on.
                if (steerJob == coroutineContext[Job]) remoteTurn = false
                refreshRunning()
            }
        }
    }

    /**
     * The queue sheet's 立即转向: pull the item out of the queue and get it
     * in front of the agent NOW. A text-only item steers into the running
     * turn via midTurnPrompts (the turn keeps its context); an image
     * prompt can't ride mid-turn blocks, so its send-now is cancel-then-
     * send — the interrupted turn ends, the prompt starts fresh.
     */
    fun steerQueued(id: Long) {
        // A tap landing after the turn ended must not call cancel/steer on
        // it — leave the item queued; it drains normally.
        if (!_ui.value.running) return
        val q = _queuedPrompts.value.firstOrNull { it.id == id } ?: return
        if (q.text.isBlank() && q.attachments.isEmpty()) return
        _queuedPrompts.update { q2 -> q2.filterNot { it.id == id } }
        // The item went out — an open edit on it resolves to "keep the
        // typed text" rather than resurrecting the stashed draft.
        if (_editingQueuedId.value == q.id) endQueuedEdit(keepDraft = true)
        if (q.attachments.isEmpty()) {
            steerMerged(q.text)
            return
        }
        runCatching { client.cancel(sessionId) }
        // cancelTurn's watchdog contract, keyed on the ABOUT-TO-BE-STALE
        // job: launchPrompt reassigns promptJob, so an identity check like
        // cancelTurn's could never reap a hung old request.
        val stale = promptJob
        scope.launch {
            delay(CANCEL_WATCHDOG_MS)
            if (stale?.isActive == true) {
                stale.cancel()
                append(Kind.System, "本轮已强制结束")
            }
        }
        launchPrompt(q.text, q.attachments, steered = true)
    }

    fun cancelQueued(id: Long) {
        var removed: QueuedPrompt? = null
        _queuedPrompts.update { q ->
            removed = q.firstOrNull { it.id == id }
            q.filterNot { it.id == id }
        }
        if (removed != null && removed.id == _editingQueuedId.value) {
            endQueuedEdit(keepDraft = false)
        }
    }

    /**
     * Pull queued[id]'s text + images into the composer as an edit: the
     * item stays queued (a "编辑中" badge marks it) and saving rewrites it in
     * place. The prior draft is stashed and restored on save/cancel.
     */
    fun beginQueuedEdit(id: Long) {
        val q = _queuedPrompts.value.firstOrNull { it.id == id } ?: return
        // Re-tapping the item already being edited must keep the typed
        // text — re-seeding would silently discard it.
        if (_editingQueuedId.value == id) return
        // Switching items commits the in-flight edit to its own item first;
        // the restored draft then becomes this edit's stash.
        if (_editingQueuedId.value != null) saveQueuedEdit()
        if (_editingQueuedId.value == null) {
            queuedEditStash = Triple(
                _draft.value, _draftAttachments.value, _draftCitation.value,
            )
        }
        _editingQueuedId.value = q.id
        _draft.value = q.text
        _draftAttachments.value = q.attachments
        _draftCitation.value = null // the item's quote is already in its text
    }

    fun cancelQueuedEdit() = endQueuedEdit(keepDraft = false)

    /**
     * Write the composer's text + images back into the edited queue item.
     * A blank-text/no-image save removes the item outright — it could only
     * drain as an empty prompt — and an item that already left the queue
     * (drained/cancelled) just ends the edit, keeping the typed text.
     */
    fun saveQueuedEdit() {
        val id = _editingQueuedId.value ?: return
        // A citation added during the edit merges into the item's text —
        // its chip belongs to the edit session, not the stashed draft.
        val text = mergedWithCitation(_draft.value.trimEnd(), _draftCitation.value)
        val attachments = _draftAttachments.value
        var stillQueued = true
        _queuedPrompts.update { q ->
            val i = q.indexOfFirst { it.id == id }
            stillQueued = i >= 0
            when {
                // Blank text + no images: a save means the item is emptied —
                // it could only drain as an empty prompt, so drop it.
                text.isBlank() && attachments.isEmpty() ->
                    q.filterNot { it.id == id }
                i < 0 -> q
                else -> q.toMutableList().apply {
                    set(i, q[i].copy(text = text, attachments = attachments))
                }
            }
        }
        // The citation merged into the saved item is spent; when the item
        // already went out on its own, keepDraft leaves it attached to the
        // live draft alongside the typed text.
        if (stillQueued) _draftCitation.value = null
        // A target that already went out on its own keeps the typed text
        // as the new draft rather than restoring the pre-edit stash.
        endQueuedEdit(keepDraft = !stillQueued)
    }

    /**
     * Exit queue-edit mode. [keepDraft] = the item already went out on its
     * own (drained/steered): the typed text stays as the live draft instead
     * of restoring the stash. Otherwise the pre-edit draft is restored.
     */
    private fun endQueuedEdit(keepDraft: Boolean) {
        _editingQueuedId.value = null
        val stash = queuedEditStash
        queuedEditStash = null
        if (!keepDraft && stash != null) {
            _draft.value = stash.first
            _draftAttachments.value = stash.second
            _draftCitation.value = stash.third
        }
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
        if (currentMessages.none { it.id == item.id }) return
        if (item.attachmentCount > 0 && item.attachments.isEmpty()) {
            // Cache-restored echoes keep the image COUNT but not the bytes —
            // sending the text alone would silently drop the pictures.
            append(Kind.Error, "图片未被缓存，无法随消息重发")
            return
        }
        // The resent text is verbatim — a draft citation pinned now belongs
        // to the next message, not this failed echo.
        if (!send(item.text, item.attachments, mergeCitation = false)) return
        mutateMessages { list -> list.filterNot { it.id == item.id } }
    }

    /**
     * Write the plan card's markdown into the session workspace as
     * [PLAN_FILE_NAME] via the bridge's `/fs/write`. Feedback lands in the
     * transcript as a System/Error line — the card itself stays stateless.
     */
    fun savePlanToWorkspace(markdown: String) {
        val writer = workspaceFileWriter
        scope.launch {
            try {
                if (writer == null) throw AcpException("bridge 不支持文件写入")
                writer(PLAN_FILE_NAME, markdown.trimEnd() + "\n")
                append(Kind.System, "计划已保存到 $PLAN_FILE_NAME")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "计划保存失败: ${e.display()}")
            }
        }
    }

    private fun drainQueue() {
        val next = _queuedPrompts.value.firstOrNull() ?: return
        _queuedPrompts.update { it.drop(1) }
        // It sent with its original text — keep any in-flight edits as draft.
        if (_editingQueuedId.value == next.id) endQueuedEdit(keepDraft = true)
        launchPrompt(next.text, next.attachments)
    }

    private fun launchPrompt(
        text: String,
        attachments: List<ImageAttachment>,
        steered: Boolean = false,
    ) {
        val blocks = buildBlocks(text, attachments)
        // The echo keeps the raw text — UserBubble renders the "[N 张图片]"
        // suffix — so UserChunk dedup matches and resend() replays verbatim.
        val userItem = append(
            Kind.User, text, attachments = attachments, steered = steered,
        )
        promptAcked = false
        promptJob = scope.launch {
            remoteTurn = false // our own job now speaks for the turn
            refreshRunning()
            try {
                val result = client.promptBlocks(sessionId, blocks)
                onSessionReady?.invoke(sessionId, cwd)
                reportStopReason(result.stopReason)
                if (currentMessages.isNotEmpty()) {
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
                    mutateMessages { list ->
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
     * the text — `@{kind}:{ref}` mention tokens lift into `resource_link`
     * blocks carrying devin's `cognition.ai` mention `_meta` (the agent
     * resolves mention-path against the session's advertised mentionTypes;
     * A7.3), while bare `@path` tokens keep their N6.2 file link.
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
        // Bare-@path pass over one text segment (N6.2): a post-whitespace
        // `@token` that looks path-ish becomes a file resource_link. Text
        // between tokens copies verbatim — newlines and indentation in the
        // prompt survive.
        fun bareMentions(seg: String) {
            var cur = 0
            for (match in BARE_MENTION_TOKEN.findAll(seg)) {
                if (match.range.first > 0 && !seg[match.range.first - 1].isWhitespace()) continue
                val ref = match.value.substring(1).trimMentionTail()
                val looksLikePath = ref.isNotEmpty() && !ref.contains(':') &&
                    (ref.contains('/') || ref.contains('\\') || ref.contains('.'))
                if (!looksLikePath) continue
                buf.append(seg, cur, match.range.first)
                flushText()
                blocks += ContentBlock(type = "resource_link", uri = fileUri(ref), name = ref)
                cur = match.range.last + 1
            }
            buf.append(seg, cur, seg.length)
        }
        var cursor = 0
        for (match in SEND_MENTION_TOKEN.findAll(text)) {
            // A hint-matched ref can swallow this token's start (a spaced
            // path containing "@kind:") — it was consumed, not a mention.
            if (match.range.first < cursor) continue
            // A mid-word `@` (emails, handles) is not a mention token.
            if (match.range.first > 0 && !text[match.range.first - 1].isWhitespace()) continue
            val kind = match.groupValues[1]
            val refStart = match.range.last + 1
            // A picker-inserted ref can hold spaces — a registered ref wins;
            // a typed mention stays one whitespace-free word. The hint must
            // end on a whitespace/EOL boundary or a stale hint would eat a
            // longer typed token's tail (foo bar ← foo barista).
            val ref = mentionRefHints.asSequence()
                .filter {
                    text.startsWith(it, refStart) &&
                        text.getOrNull(refStart + it.length)?.isWhitespace() != false
                }
                .maxByOrNull { it.length }
                ?: text.substring(refStart)
                    .takeWhile { !it.isWhitespace() }
                    .trimMentionTail()
            if (ref.isEmpty()) continue
            bareMentions(text.substring(cursor, match.range.first))
            blocks += mentionBlock(kind, ref, "@$kind:$ref")
            cursor = refStart + ref.length
        }
        bareMentions(text.substring(cursor))
        flushText()
        if (blocks.isEmpty()) blocks += ContentBlock(type = "text", text = text)
        return blocks
    }

    /**
     * `@{kind}:{ref}` → the resource_link devin reads: file-ish kinds
     * resolve to file:// URIs, `url`/`web` carry their own URL, and the
     * rest ride a `cognition://<type>/<ref>` URI — the real payload is the
     * `_meta` mention record (resource-link-type / mention-path /
     * mention-text, plus the kind's id field; key names verified in the
     * agent binary, A7.3).
     */
    private fun mentionBlock(kind: String, ref: String, source: String): ContentBlock {
        val linkType = when (kind) {
            "folder" -> "directory"
            "session" -> "conversation"
            "image" -> "file"
            else -> kind
        }
        val uri = when (linkType) {
            "file", "directory" -> fileUri(ref)
            "url", "web" -> ref
            else -> "cognition://$linkType/$ref"
        }
        val meta = buildJsonObject {
            put("cognition.ai/resource-link-type", linkType)
            put("cognition.ai/mention-path", ref)
            put("cognition.ai/mention-text", source)
            when (linkType) {
                "conversation" -> put("cognition.ai/session-id", ref)
                "codemap" -> put("cognition.ai/codemap-id", ref)
            }
        }
        return ContentBlock(
            type = "resource_link",
            uri = uri,
            name = if (linkType == "file" || linkType == "directory") {
                ref.substringAfterLast('/').substringAfterLast('\\').ifEmpty { ref }
            } else {
                ref
            },
            _meta = meta,
        )
    }

    private fun fileUri(rel: String): String {
        val base = cwd.replace('\\', '/').trimEnd('/')
        val path = rel.replace('\\', '/').trimStart('/')
        return "file:///$base/$path"
    }

    /** Trailing sentence punctuation isn't part of a mention ref. */
    private fun String.trimMentionTail(): String =
        trimEnd(*",;:!?)]}'\"".toCharArray())

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
        // No eager null: while the sheet is open (list non-null) a
        // stepsUpdated refresh swaps in place — nulling would unmount it.
        scope.launch {
            try {
                _revertSteps.value = client.revertSteps(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // -32601 is the capability probe's negative answer — the
                // agent build simply has no revert surface (P4).
                if ((e as? AcpException)?.code == JSONRPC_METHOD_NOT_FOUND) {
                    onRevertUnsupported?.invoke()
                }
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

    /**
     * `_cognition.ai/revert/preview` for the sheet's selected step (A7.5).
     * [userMessageId] switches to the revertByUserMessageId target — the
     * caller passes it only when the agent negotiated that capability.
     * Throws: the sheet renders failure inline (non-fatal).
     */
    suspend fun revertPreview(stepId: String, userMessageId: String? = null): RevertPreview =
        client.revertPreview(sessionId, stepId, userMessageId)

    /**
     * In-place revert (A7.5): `revert/execute` rewinds this session's
     * history + workspace files. Null = failed (error row already
     * appended); the caller reloads the transcript on success.
     */
    suspend fun revertExecute(stepId: String, userMessageId: String? = null): RevertExecuteResult? =
        try {
            client.revertExecute(sessionId, stepId, userMessageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            append(Kind.Error, "回退失败: ${e.display()}")
            null
        } finally {
            _revertSteps.value = null
        }

    // ---- agent -> client requests -----------------------------------------------

    suspend fun handleAgentRequest(req: IncomingRequest) {
        // Journaled request seqs are seeded at dispatch (AcpClient), in
        // wire order — the cursor already covers req.seq here; it still
        // counts toward attach-backlog coverage.
        commitIfCaughtUp(req.seq)
        when (req.method) {
            AcpMethods.SESSION_REQUEST_PERMISSION -> handlePermission(req)
            AcpMethods.ELICITATION_CREATE,
            AcpMethods.SESSION_ELICITATION -> handleElicitation(req)
            // The agent closed a pending elicitation itself — drop the card,
            // don't answer the still-open create request.
            AcpMethods.ELICITATION_COMPLETE,
            AcpMethods.SESSION_ELICITATION_COMPLETE -> {
                dismissElicitation(elicitationIdFrom(req.params))
                req.respond(buildJsonObject {})
            }
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
        synchronized(requestLock) {
            openRequests.entries.firstOrNull { it.value.req.rpcId == req.rpcId }
                ?.let { it.value.req = req; return }
        }
        val requestId = req.requestKey ?: syntheticRequestKey.getAndDecrement()
        val choice = CompletableDeferred<String?>()
        val pending = PendingPermission(params.toolCall, params.options, choice, requestId)
        val entry = PermEntry(req, pending)
        // One lock covers map + queue — a teardown between them would
        // orphan the card.
        synchronized(requestLock) {
            openRequests[requestId] = entry
            permissionQueue.addLast(pending)
            _pendingPermission.value = permissionQueue.firstOrNull()
            _pendingPermissionCount.value = permissionQueue.size
        }
        notifier.notifyPermissionPending(
            params.toolCall.title ?: params.toolCall.kind ?: "工具调用",
            sessionId,
        )
        // The gateway owns the pending-approval TTL — a request stays open
        // until the user answers or _devingo/permission_resolved lands.
        val optionId = choice.await()
        popPermission(pending)
        synchronized(requestLock) { openRequests.remove(requestId, entry) }
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

    /**
     * `_devingo/permission_resolved`: a peer answered request [requestId] —
     * the broadcast covers every agent→client request kind, so it resolves
     * both permissions and elicitations.
     */
    fun resolvePeerRequest(requestId: Long) {
        synchronized(requestLock) {
            openRequests[requestId]?.let {
                it.resolvedByPeer = true
                it.pending.choice.complete(RESOLVED_BY_PEER)
                return
            }
            openElicitations[requestId]?.let {
                it.resolvedByPeer = true
                it.pending.choice.complete(null)
            }
        }
    }

    // ---- elicitation (A7.2) -------------------------------------------------

    /**
     * `elicitation/create` (or the `session/elicitation` draft name): parse
     * the question form into the card's queue — the same pending-request
     * machinery permissions use (re-delivery dedupe, peer resolution,
     * teardown without a response). An unaskable form declines rather than
     * leaving the agent's request open.
     */
    private suspend fun handleElicitation(req: IncomingRequest) {
        val form = req.params?.let { ElicitationParser.parse(it) }
        if (form == null || form.questions.isEmpty()) {
            req.respond(buildJsonObject { put("action", "decline") })
            return
        }
        // Same wire id = the same pending card after an attach re-delivery.
        synchronized(requestLock) {
            openElicitations.entries.firstOrNull { it.value.req.rpcId == req.rpcId }
                ?.let { it.value.req = req; return }
        }
        val requestId = req.requestKey ?: syntheticRequestKey.getAndDecrement()
        val choice = CompletableDeferred<ElicitationReply?>()
        val pending = PendingElicitation(
            message = form.message,
            questions = form.questions,
            choice = choice,
            requestId = requestId,
        )
        val entry = ElicEntry(req, pending)
        // One lock covers map + queue — a teardown between them would
        // orphan the card.
        synchronized(requestLock) {
            openElicitations[requestId] = entry
            elicitationQueue.addLast(pending)
            _pendingElicitation.value = elicitationQueue.firstOrNull()
            _pendingElicitationCount.value = elicitationQueue.size
        }
        notifier.notifyPermissionPending(
            "Devin 有问题需要你回答",
            sessionId,
            id = Notifier.ID_ELICITATION,
            headline = "DevinGo 需要回答",
        )
        val reply = choice.await()
        popElicitation(pending)
        synchronized(requestLock) { openElicitations.remove(requestId, entry) }
        // Peer answer / elicitation/complete / store teardown: never respond.
        if (entry.resolvedByPeer) return
        if (reply == null) {
            entry.req.respond(buildJsonObject { put("action", "cancel") })
        } else {
            if (reply.lines.isNotEmpty()) {
                // choice.await() resumed on the client dispatcher — hop
                // onto the store scope before touching the transcript.
                withContext(scope.coroutineContext) {
                    append(
                        Kind.Answered,
                        text = if (reply.lines.size == 1) {
                            "已回答 · ${reply.lines.first()}"
                        } else {
                            "已回答 ${reply.lines.size} 个问题"
                        },
                        detail = reply.lines.joinToString("\n")
                            .takeIf { reply.lines.size > 1 },
                    )
                }
            }
            entry.req.respond(
                buildJsonObject {
                    put("action", "accept")
                    put("content", reply.content)
                },
            )
        }
    }

    /** `elicitationId` / `requestId` / `id` — devin's complete params. */
    private fun elicitationIdFrom(params: JsonElement?): Long? =
        (params as? JsonObject)?.let { o ->
            listOf("elicitationId", "requestId", "id").firstNotNullOfOrNull { k ->
                (o[k] as? JsonPrimitive)
                    ?.let { it.longOrNull ?: it.content.toLongOrNull() }
            }
        }

    private fun popElicitation(p: PendingElicitation) = synchronized(requestLock) {
        elicitationQueue.remove(p)
        _pendingElicitation.value = elicitationQueue.firstOrNull()
        _pendingElicitationCount.value = elicitationQueue.size
    }

    /**
     * Same contract as [clearPermissions]: a teardown never answers — the
     * hub's pending-request TTL re-delivers the elicitation after re-attach.
     */
    private fun clearElicitations() = synchronized(requestLock) {
        openElicitations.values.forEach { it.resolvedByPeer = true }
        elicitationQueue.forEach { it.choice.complete(null) }
        elicitationQueue.clear()
        openElicitations.clear()
        _pendingElicitation.value = null
        _pendingElicitationCount.value = 0
    }

    /**
     * The card's submit/dismiss; null = declined without answers. The
     * rendered card's identity is passed in — the exit-animation ghost
     * must not resolve a newly pushed head.
     */
    fun resolveElicitation(p: PendingElicitation, reply: ElicitationReply?) {
        if (_pendingElicitation.value === p) p.choice.complete(reply)
    }

    /**
     * `elicitation/complete` — the agent withdrew the elicitation (answered
     * elsewhere or dropped). Treated like a peer resolution: the create
     * request gets no response from us. An [elicitationId] that doesn't
     * match an open request is stale — dismiss nothing.
     */
    fun dismissElicitation(elicitationId: Long? = null) {
        synchronized(requestLock) {
            val target = if (elicitationId != null) {
                openElicitations[elicitationId]?.pending ?: return
            } else {
                _pendingElicitation.value ?: return
            }
            openElicitations[target.requestId]?.let {
                it.resolvedByPeer = true
                it.pending.choice.complete(null)
            }
        }
    }

    private fun popPermission(p: PendingPermission) = synchronized(requestLock) {
        permissionQueue.remove(p)
        _pendingPermission.value = permissionQueue.firstOrNull()
        _pendingPermissionCount.value = permissionQueue.size
    }

    /**
     * Abandon every queued request WITHOUT responding — the hub holds the
     * permission for its own TTL and re-delivers it after re-attach, so a
     * teardown here must not resolve it as "cancelled" agent-side.
     */
    private fun clearPermissions() = synchronized(requestLock) {
        openRequests.values.forEach { it.resolvedByPeer = true }
        permissionQueue.forEach { it.choice.complete(null) }
        permissionQueue.clear()
        openRequests.clear()
        _pendingPermission.value = null
        _pendingPermissionCount.value = 0
    }

    /** Called by the permission dialog; null = user dismissed -> "cancelled". */
    fun resolvePermission(optionId: String?) {
        _pendingPermission.value?.choice?.complete(optionId)
    }

    /**
     * The notification-action path: answer the SPECIFIC request named by
     * [requestId] (+ [toolCallId] cross-check — a recycled wire id can't
     * resolve a different tool call), wherever it sits in the queue.
     * 允许 only ever picks `allow_once` — a persistent grant must not be
     * handed out from the shade. `choice.complete` is the atomic
     * check-and-set: it returns false when the request is already answered
     * (in-app, a double tap, or `_devingo/permission_resolved`), so the
     * caller learns the action was stale and never decides twice.
     */
    fun resolvePermissionRequest(
        requestId: Long,
        toolCallId: String?,
        allow: Boolean,
    ): Boolean = synchronized(requestLock) {
        val p = openRequests[requestId]?.pending ?: return@synchronized false
        if (toolCallId != null && p.toolCall.toolCallId != toolCallId) {
            return@synchronized false
        }
        val option = if (allow) {
            p.options.firstOrNull { it.kind == "allow_once" }
        } else {
            p.options.firstOrNull { it.kind == "reject_once" }
                ?: p.options.firstOrNull { it.kind == "reject_always" }
        }
        p.choice.complete(option?.optionId)
    }

    // ---- transcript assembly ---------------------------------------------------

    /**
     * Route a transcript mutation to the staged copy while a replay is in
     * flight, else to [_messages]. Reads use [currentMessages].
     */
    private inline fun mutateMessages(transform: (List<ChatItem>) -> List<ChatItem>) {
        val s = staged
        if (s != null) {
            staged = transform(s).toMutableList()
        } else {
            _messages.update(transform)
        }
    }

    private fun refreshRunning() {
        val running = remoteTurn || promptJob?.isActive == true ||
            steerJob?.isActive == true
        val was = _ui.value.running
        if (running == was) return
        if (!running) {
            // Falling edge: stamp the end time on the latest user's turn.
            val now = System.currentTimeMillis()
            val lastUserId = currentMessages.lastOrNull { it.kind == Kind.User }?.id
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
        // A long message re-parses more markdown per flush — relax the
        // cadence once the target item passes ~4KB (U3; parsing itself
        // stays incremental inside the renderer).
        val committed = currentMessages.lastOrNull()
            ?.takeIf { it.id == streamingId }?.text?.length ?: 0
        val interval = if (committed + streamBuf.length >= STREAM_RELAX_CHARS) {
            FLUSH_INTERVAL_SLOW_MS
        } else {
            FLUSH_INTERVAL_MS
        }
        flushJob = scope.launch {
            delay(interval)
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
        mutateMessages { list ->
            val last = list.lastOrNull()
            if (last != null && last.id == streamingId && last.kind == kind) {
                list.dropLast(1) + last.copy(text = last.text + chunk)
            } else {
                // Non-live chunks are attach/load replay — the item must
                // never run the fresh-entry animation (U5.2).
                val item = ChatItem(nextItemId++, kind, chunk, isReplay = !streamLive)
                streamingId = item.id
                if (kind == Kind.User && streamLive) noteTurnStart(item.id)
                capMessages(list + item)
            }
        }
    }

    /** Cap the transcript at [MAX_MESSAGES], keeping the first [KEEP_HEAD] items. */
    private fun capMessages(list: List<ChatItem>): List<ChatItem> {
        if (list.size <= MAX_MESSAGES) return list
        val head = list.take(KEEP_HEAD)
        val capped = head + list.drop(KEEP_HEAD).drop(list.size - MAX_MESSAGES)
        // Turn timings keyed on evicted items would leak for the store's
        // lifetime — drop them with the items.
        val kept = capped.mapTo(HashSet()) { it.id }
        _turnTimes.update { t -> t.filterKeys { it in kept } }
        return capped
    }

    private fun noteTurnStart(itemId: Long) {
        _turnTimes.update { it + (itemId to TurnTiming(System.currentTimeMillis(), null)) }
    }

    fun append(
        kind: Kind,
        text: String,
        status: String? = null,
        toolCallId: String? = null,
        toolKind: String? = null,
        detail: String? = null,
        detailTruncated: Boolean = false,
        exitCode: Int? = null,
        attachments: List<ImageAttachment> = emptyList(),
        steered: Boolean = false,
    ): ChatItem {
        flushStreaming()
        val item = ChatItem(
            nextItemId++, kind, text, status, toolCallId,
            toolKind = toolKind,
            detail = detail, detailTruncated = detailTruncated,
            exitCode = exitCode, attachments = attachments,
            attachmentCount = attachments.size, steered = steered,
        )
        streamingId = null
        // A Kind.User here is always a live local echo (replayed user text
        // arrives through the streaming path, gated by streamLive).
        if (kind == Kind.User) noteTurnStart(item.id)
        mutateMessages { capMessages(it + item) }
        return item
    }

    private fun markFailed(itemId: Long) {
        mutateMessages { list ->
            list.map { if (it.id == itemId) it.copy(failed = true) else it }
        }
    }

    /** A call reclassified as a subagent mid-flight leaves its row behind. */
    private fun removeToolRow(toolCallId: String) {
        mutateMessages { list ->
            if (list.none { it.toolCallId == toolCallId }) {
                list
            } else {
                list.filterNot { it.toolCallId == toolCallId }
            }
        }
    }

    private fun updateTool(call: ToolCallInfo) {
        // A subagent call can lose its kind/_meta on later updates — keep
        // routing it to the subagent list instead of surfacing a stray row.
        if (_subagents.value.any { it.toolCallId == call.toolCallId }) {
            upsertSubagent(call)
            return
        }
        mutateMessages { list ->
            val idx = list.indexOfLast { it.toolCallId == call.toolCallId }
            if (idx < 0) {
                val detail = toolDetailOf(call)
                capMessages(
                    list + ChatItem(
                        nextItemId++, Kind.Tool,
                        toolTitleOf(call) ?: call.kind ?: "tool call",
                        call.status, call.toolCallId,
                        toolKind = toolKindOf(call),
                        detail = detail?.text,
                        detailTruncated = detail?.headTruncated == true,
                        exitCode = exitCodeOf(call),
                        diffs = toolDiffsOf(call),
                    ),
                )
            } else {
                val old = list[idx]
                val diffs = toolDiffsOf(call)
                val detail = toolDetailOf(call)
                list.toMutableList().apply {
                    set(idx, old.copy(
                        text = toolTitleOf(call) ?: old.text,
                        status = call.status ?: old.status,
                        toolKind = toolKindOf(call) ?: old.toolKind,
                        detail = detail?.text ?: old.detail,
                        detailTruncated = detail?.headTruncated ?: old.detailTruncated,
                        exitCode = exitCodeOf(call) ?: old.exitCode,
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

    /** Detail text + whether its head was dropped (command output tail-keep). */
    private data class ToolDetail(val text: String, val headTruncated: Boolean)

    /**
     * Extract a human-readable preview from a tool call's content/locations.
     * Exec output keeps the TAIL (the newest lines matter — the transcript
     * flags the dropped head à la t3code's "Earlier output not shown");
     * everything else keeps the head.
     */
    private fun toolDetailOf(call: ToolCallInfo): ToolDetail? {
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
                    ?.let { parts.add(it) }
            }
        }
        val joined = parts.joinToString("\n").takeIf { it.isNotBlank() }
            ?: return null
        return if (toolKindOf(call) == "execute") {
            ToolDetail(joined.takeLast(TOOL_DETAIL_CAP), joined.length > TOOL_DETAIL_CAP)
        } else {
            ToolDetail(joined.take(TOOL_DETAIL_CAP), headTruncated = false)
        }
    }

    /** `_meta.terminal_exit.exit_code` — present once an exec call finishes. */
    private fun exitCodeOf(call: ToolCallInfo): Int? =
        ((call._meta?.get("terminal_exit") as? JsonObject)
            ?.get("exit_code") as? JsonPrimitive)
            ?.intOrNull

    /**
     * ACP `kind` normalized onto our vocabulary. Devin's agent omits `kind`
     * and instead sends `_meta.cognition.ai/inferenceToolName` ("exec",
     * "kill_shell", …) — map the observed shell tools onto "execute" and
     * pass through any name already in the ACP set.
     */
    private fun toolKindOf(call: ToolCallInfo): String? {
        call.kind?.takeIf { it.isNotBlank() }?.let { return it }
        val name = (call._meta?.get("cognition.ai/inferenceToolName")
            as? JsonPrimitive)?.strOrNull()
        return when {
            name == null -> null
            // Devin's shell tools — all emit terminal output (ANSI, exit).
            name == "exec" || name == "get_output" || name == "kill_shell" ->
                "execute"
            name in ACP_TOOL_KINDS -> name
            else -> null
        }
    }

    /**
     * Row label: exec prefers the literal command from `rawInput.command`
     * (Devin's title is a summary like "Ran seq"); else the title. Null when
     * the call carries neither, so update paths keep the old text.
     */
    private fun toolTitleOf(call: ToolCallInfo): String? =
        if (toolKindOf(call) == "execute") {
            (call.rawInput as? JsonObject)?.get("command").strOrNull()
                ?: call.title
        } else {
            call.title
        }

    private fun kotlinx.serialization.json.JsonElement?.strOrNull(): String? =
        (this as? JsonPrimitive)
            ?.let { if (it.isString) it.content else null }

    // ---- local transcript cache (U7) -----------------------------------------

    /**
     * Snapshot for the local transcript cache: transcript tail + turn
     * timings + the journal cursor. A mid-replay snapshot takes the
     * COMMITTED list with the replay's base seq — never the partial staged
     * copy with an already-advanced cursor, which would record coverage for
     * frames still in flight and lose them on the next attach.
     */
    fun cacheSnapshot(host: String): TranscriptCache.CachedTranscript {
        val items = _messages.value.takeLast(TranscriptCache.MAX_ITEMS)
        val kept = items.mapTo(HashSet()) { it.id }
        return TranscriptCache.CachedTranscript(
            sessionId = sessionId,
            host = host,
            savedAt = System.currentTimeMillis(),
            lastSeq = if (staged != null) replayBaseSeq else lastSeq,
            nextItemId = nextItemId,
            title = _ui.value.sessionTitle,
            items = items.map(::cacheItemOf),
            turnTimes = _turnTimes.value
                .filterKeys { it in kept }
                .mapValues { TranscriptCache.CachedTiming(it.value.start, it.value.end) },
        )
    }

    /**
     * Render a cached snapshot: transcript, timings, journal cursor and
     * title — the attach replay then appends only what the cache missed.
     */
    fun restoreFromCache(c: TranscriptCache.CachedTranscript) {
        val items = c.items.mapNotNull(::restoreItem)
        nextItemId = maxOf(c.nextItemId, (items.maxOfOrNull { it.id } ?: -1L) + 1)
        planItemId = items.lastOrNull { it.kind == Kind.Plan }?.id
        _messages.value = items
        _turnTimes.value =
            c.turnTimes.mapValues { TurnTiming(it.value.start, it.value.end) }
        seqCursor.set(c.lastSeq)
        c.title?.takeIf { it.isNotBlank() }?.let { t ->
            _ui.update { it.copy(sessionTitle = t) }
        }
    }

    private fun cacheItemOf(m: ChatItem) = TranscriptCache.CachedItem(
        id = m.id,
        kind = m.kind.name,
        text = m.text.take(TranscriptCache.TEXT_CAP),
        status = m.status,
        toolCallId = m.toolCallId,
        toolKind = m.toolKind,
        // Exec output keeps its tail in the cache too — the newest lines.
        detail = if (m.toolKind == "execute") {
            m.detail?.takeLast(TranscriptCache.DETAIL_CAP)
        } else {
            m.detail?.take(TranscriptCache.DETAIL_CAP)
        },
        detailTruncated = m.detailTruncated ||
            (m.toolKind == "execute" && (m.detail?.length ?: 0) > TranscriptCache.DETAIL_CAP),
        exitCode = m.exitCode,
        diffs = m.diffs.map {
            TranscriptCache.CachedDiff(
                it.path,
                it.oldText?.take(TranscriptCache.DIFF_TEXT_CAP),
                it.newText.take(TranscriptCache.DIFF_TEXT_CAP),
            )
        },
        planEntries = m.planEntries.map {
            TranscriptCache.CachedPlanEntry(it.content, it.status, it.priority)
        },
        failed = m.failed,
        attachmentCount = m.attachmentCount,
        steered = m.steered,
    )

    private fun restoreItem(c: TranscriptCache.CachedItem): ChatItem? {
        val kind = runCatching { Kind.valueOf(c.kind) }.getOrNull() ?: return null
        return ChatItem(
            id = c.id, kind = kind, text = c.text, status = c.status,
            toolCallId = c.toolCallId, toolKind = c.toolKind, detail = c.detail,
            detailTruncated = c.detailTruncated, exitCode = c.exitCode,
            diffs = c.diffs.map { ToolDiff(it.path, it.oldText, it.newText) },
            planEntries = c.planEntries.map {
                PlanEntry(it.content, it.status, it.priority)
            },
            failed = c.failed,
            attachmentCount = c.attachmentCount,
            steered = c.steered,
            isReplay = true,
        )
    }

    private fun upsertPlan(entries: List<PlanEntry>) {
        streamingId = null
        mutateMessages { list ->
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
