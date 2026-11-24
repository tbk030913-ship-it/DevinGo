package com.devingo.app.acp

import android.util.Log
import com.devingo.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Events pushed by the agent between (and during) turns.
 *
 * Deliberately NOT a sealed interface: Devin's protocol surface grows without
 * warning and consumers should tolerate (and ignore) event types they don't
 * know yet instead of failing to compile.
 */
interface AcpEvent {
    data class AgentChunk(val text: String) : AcpEvent
    data class ThoughtChunk(val text: String) : AcpEvent
    data class UserChunk(val text: String) : AcpEvent
    data class ToolCall(val call: ToolCallInfo) : AcpEvent
    data class ToolCallUpdate(val call: ToolCallInfo) : AcpEvent
    data class PlanUpdate(val entries: List<PlanEntry>) : AcpEvent
    data class ModeChanged(val modeId: String) : AcpEvent
    data class CommandsAvailable(val commands: List<AvailableCommand>) : AcpEvent
    data class ConfigOptionsUpdated(val options: List<ConfigOption>) : AcpEvent
    data class SessionTitle(val title: String, val meta: JsonObject? = null) : AcpEvent
    /**
     * Context window usage reported by Devin: [used] of [size] tokens.
     * Token counters arrive via `update._meta["cognition.ai/…Tokens"]`.
     */
    data class UsageUpdate(
        val used: Long,
        val size: Long,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
    ) : AcpEvent
    /** Devin extension `_cognition.ai/compaction`: started/completed/failed/cancelled. */
    data class CompactionUpdate(val status: String) : AcpEvent
    /** `_cognition.ai/revert/stepsUpdated` — the revert step list changed. */
    data class RevertStepsUpdated(val params: JsonObject) : AcpEvent
    /**
     * `_cognition.ai/revert/historyRewound` — an in-place revert truncated
     * the session's history (ours or a peer client's). The transcript must
     * be rebuilt by `session/load`; the attach journal can't un-append.
     */
    data class RevertHistoryRewound(val firstRemovedUserMessageId: String? = null) : AcpEvent
    data class AgentLog(val channel: String, val message: String, val level: String) : AcpEvent
    data class Failure(val message: String) : AcpEvent
    /** Socket dropped; [reason] is the close/failure detail shown to the user. */
    data class Disconnected(val reason: String) : AcpEvent
    /**
     * Gateway marker `_devingo/session_resync`: another client's `session/load`
     * withheld live events from this subscriber — re-attach with lastSeq.
     */
    data object SessionResync : AcpEvent
    /** Gateway marker `_devingo/session_deleted` — the session is gone. */
    data object SessionDeleted : AcpEvent
    /**
     * Gateway marker `_devingo/agent_dead`: the resident devin process died;
     * the session's running state and pending requests were reset.
     */
    data class AgentDead(val reason: String) : AcpEvent
    /**
     * Gateway `_devingo/permission_resolved`: another client (or the TTL)
     * answered agent request [requestId] — drop the matching local card.
     */
    data class PermissionResolved(val requestId: Long, val outcome: String) : AcpEvent
    /**
     * `_devingo/event` carrying a journaled response owned by another client.
     * Only turn-ending frames are surfaced: a `session/prompt` result means a
     * remote client's turn finished.
     */
    data class RemoteTurnEnd(val stopReason: String?) : AcpEvent
    /**
     * `elicitation/complete` as a notification — the agent closed the
     * pending question card on its own; drop it without answering.
     */
    data class ElicitationComplete(val elicitationId: Long? = null) : AcpEvent
    /**
     * A `tool_call`/`tool_call_update` identified as a Devin subagent (A7.4):
     * kind `subagent`/`task`, or `_meta` carrying
     * `cognition.ai/childSessionUrl` / `cognition.ai/sharedSubagentId`. The
     * call does NOT surface as a transcript tool row — the subagent card
     * renders it instead.
     */
    data class SubagentUpdate(val call: ToolCallInfo) : AcpEvent
    /**
     * `cognition.ai/numLiveSubagents` reported on a session/update frame's
     * `_meta` (params- or update-level).
     */
    data class SubagentCount(val count: Int) : AcpEvent
    /**
     * A descendant-agent frame multiplexed onto this stream — `_meta`
     * `subagent_context.parentAgentId` names the owning agent's session id
     * (t3 devinAcp reroutes these to that agent). [text] folds into the
     * matching subagent's live output: [newLine] separates line items
     * (tool summaries) from streamed text (message/thought chunks).
     */
    data class SubagentCtxFrame(
        val agentId: String,
        val text: String,
        val newLine: Boolean,
    ) : AcpEvent
    /**
     * `tool_call_content_chunk` — Devin streams tool output in pieces
     * (t3's registry fixtures). The store appends it to the row's detail,
     * unlike [ToolCallUpdate] which replaces.
     */
    data class ToolContentChunk(val call: ToolCallInfo) : AcpEvent
}

/**
 * One inbound frame distilled into an event plus its routing metadata:
 * [sessionId] is `params.sessionId` when the frame is session-scoped
 * (null for connection-level events), [seq] is the `_seq` the gateway
 * splices into journaled frames (0 on unjournaled frames such as pending
 * request re-deliveries and gateway request results).
 */
data class InboundEvent(
    val sessionId: String?,
    val seq: Long,
    val event: AcpEvent,
)

data class PlanEntry(
    val content: String,
    val status: String? = null,
    val priority: String? = null,
)

/** A request the agent sent us and is waiting on (permissions, fs, terminal…). */
class IncomingRequest(
    val rpcId: JsonElement,
    val method: String,
    val params: JsonObject?,
    /** `params.sessionId` — the session this request belongs to, when scoped. */
    val sessionId: String? = null,
    /** The journaled `_seq`; 0 on post-attach re-deliveries (id-keyed). */
    val seq: Long = 0,
) {
    internal var responder: ((JsonElement?, JsonObject?) -> Unit)? = null

    /** The request id as an integer — matches `_devingo/permission_resolved`'s requestId. */
    val requestKey: Long? get() = (rpcId as? JsonPrimitive)?.textOrNull?.toLongOrNull()

    fun respond(result: JsonElement) = responder?.invoke(result, null)
    fun respondError(code: Int, message: String) = responder?.invoke(
        null,
        buildJsonObject {
            put("code", code)
            put("message", message)
        },
    )
}

/**
 * ACP client over WebSocket. Speaks one JSON-RPC message per WS text frame,
 * matching the bridge's newline framing for `devin acp` stdio.
 */
class AcpClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var ws: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, PendingCall>()

    /** A client->agent request awaiting its response, plus its session key. */
    private class PendingCall(
        val deferred: CompletableDeferred<JsonObject>,
        /** `params.sessionId`, when the request is session-scoped. */
        val sessionId: String?,
    )

    /**
     * UNLIMITED so a `session/load` replay (thousands of updates) can never
     * overflow the buffer and silently drop events the way a bounded
     * MutableSharedFlow + tryEmit did.
     */
    private val _events = Channel<InboundEvent>(Channel.UNLIMITED)
    val events: Flow<InboundEvent> = _events.receiveAsFlow()

    private fun emit(sessionId: String?, seq: Long, event: AcpEvent) {
        _events.trySend(InboundEvent(sessionId, seq, event))
    }

    /** Called for every agent->client request; must call req.respond/respondError. */
    var requestHandler: (suspend (IncomingRequest) -> Unit)? = null

    /**
     * Called with (sessionId, seq) for every journaled frame that consumes
     * a `_seq` outside the ordered event demux — responses to our own
     * requests, agent->client requests held open for an answer, and
     * `_devingo/event` frames that surface no event. The callback runs on
     * the reader thread, in wire order, so the cursor never trails a frame
     * the demux hasn't seen yet.
     */
    var seqTracker: ((sessionId: String, seq: Long) -> Unit)? = null

    suspend fun connect(url: String, token: String?) {
        val reqBuilder = Request.Builder().url(url)
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        suspendCancellableCoroutine { cont ->
            val socket = http.newWebSocket(reqBuilder.build(), object : WebSocketListener() {
                // Every callback first checks the socket is still `ws`: a
                // dying socket can fire late, after a fresh connect already
                // replaced it, and must not failAll the new connection.
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (webSocket !== ws) {
                        // Superseded handshake — close it or the bridge keeps
                        // a conn slot and a devin process alive for nothing.
                        // Resume too: the loser of the ws-race must not leave
                        // its connect() suspended forever.
                        webSocket.close(1000, "superseded")
                        cont.resumeWithException(AcpException("连接被取代"))
                        return
                    }
                    if (!cont.isActive) {
                        webSocket.close(1000, "cancelled")
                        return
                    }
                    cont.resume(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (webSocket !== ws) return
                    dispatch(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Peer initiated the close: answer the handshake right
                    // away, even on a stale socket.
                    webSocket.close(code, reason)
                    if (webSocket !== ws) return
                    // Fail fast — the socket is already dead (e.g. devin
                    // exited → 1011), so pending requests must not hang
                    // until their own timeouts notice.
                    failAll(closeText(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== ws) return
                    if (cont.isActive) {
                        cont.resumeWithException(
                            AcpException("连接失败: ${t.message ?: t.javaClass.simpleName}"),
                        )
                    } else {
                        failAll("连接断开: ${t.message ?: t.javaClass.simpleName}")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== ws) return
                    failAll(closeText(code, reason))
                }
            })
            ws = socket
            cont.invokeOnCancellation { socket.close(1000, "cancelled") }
        }
    }

    /**
     * Intentional teardown: pending calls fail, but no Disconnected event is
     * emitted — that event exists for *unexpected* drops (onFailure/onClosed),
     * and a stale one racing a fresh socket would clear the new liveness.
     */
    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        failAll("已断开", emitEvent = false)
    }

    /** Tear down everything: socket, request-handler scope and OkHttp threads. */
    fun shutdown() {
        disconnect()
        clientScope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    suspend fun initialize(): InitializeResult = request(
        AcpMethods.INITIALIZE,
        json.encodeToJsonElement(
            InitializeParams(
                protocolVersion = 1,
                clientCapabilities = ClientCapabilities(
                    // Devin negotiates its extension surface off
                    // clientCapabilities._meta — declaring the full set is
                    // what makes it echo the complete agentCapabilities._meta
                    // (probe: acptest/probe-logs/01-init-all.log). fs/terminal
                    // stay false: the app can't host those requests.
                    elicitation = JsonObject(emptyMap()),
                    _meta = CLIENT_CAPS_META,
                ),
                clientInfo = ClientInfo("DevinGo", BuildConfig.VERSION_NAME),
                // Kept from the pre-negotiation declaration — devin still
                // honors the top-level revert flag.
                _meta = buildJsonObject { put("cognition.ai/revert", true) },
            ),
        ),
        timeoutMs = 120_000,
    ).let { json.decodeFromJsonElement(it["result"] ?: it) }

    suspend fun authenticate(methodId: String, apiKey: String? = null) {
        request(
            AcpMethods.AUTHENTICATE,
            json.encodeToJsonElement(
                AuthenticateParams(
                    methodId = methodId,
                    _meta = if (apiKey.isNullOrBlank()) null
                    else buildJsonObject { put("api_key", apiKey) },
                ),
            ),
            timeoutMs = 15 * 60_000, // browser login can take a while
        )
    }

    suspend fun newSession(cwd: String): SessionNewResult {
        val frame = request(
            AcpMethods.SESSION_NEW,
            json.encodeToJsonElement(SessionNewParams(cwd = cwd)),
            timeoutMs = 120_000,
        )
        val result = json.decodeFromJsonElement<SessionNewResult>(frame["result"] ?: frame)
        // The result is journaled as the new session's first entry: seed the
        // caller's cursor so a later attach doesn't replay what we saw live.
        frame.seq()?.let { seq -> seqTracker?.invoke(result.sessionId, seq) }
        return result
    }

    /**
     * Resume an existing session; history replays as session/update events and
     * the result carries `configOptions` (modes/models) like `session/new`.
     */
    suspend fun loadSession(id: String, cwd: String): SessionNewResult {
        val result = request(
            AcpMethods.SESSION_LOAD,
            json.encodeToJsonElement(SessionLoadParams(sessionId = id, cwd = cwd)),
            timeoutMs = 120_000,
        ).let { json.decodeFromJsonElement<SessionNewResult>(it["result"] ?: it) }
        return result.copy(sessionId = result.sessionId.ifEmpty { id })
    }

    /**
     * Prompt with arbitrary content blocks — text, image/audio
     * (`data`+`mimeType`), resource links (`uri`+`name`), …
     */
    suspend fun promptBlocks(sessionId: String, blocks: List<ContentBlock>): PromptResult = request(
        AcpMethods.SESSION_PROMPT,
        json.encodeToJsonElement(PromptParams(sessionId, blocks)),
        timeoutMs = 0, // a turn can run for a very long time
    ).let { json.decodeFromJsonElement(it["result"] ?: it) }

    fun cancel(sessionId: String) = notify(
        AcpMethods.SESSION_CANCEL,
        buildJsonObject { put("sessionId", sessionId) },
    )

    /**
     * Mode/model both route through `session/set_config_option` — probing
     * confirmed `session/set_model` is unimplemented (-32601) on Devin while
     * the generic setter covers every `configOptions` entry by id.
     */
    suspend fun setMode(sessionId: String, modeId: String) =
        setConfigOption(sessionId, "mode", modeId)

    suspend fun setModel(sessionId: String, modelId: String) =
        setConfigOption(sessionId, "model", modelId)

    /** Delete a session agent-side; unsupported agents answer -32601. */
    suspend fun deleteSession(id: String) {
        request(
            AcpMethods.SESSION_DELETE,
            json.encodeToJsonElement(SessionDeleteParams(id)),
        )
    }

    /** Set any `configOptions` entry (mode/model/custom) on a live session. */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String) = request(
        AcpMethods.SESSION_SET_CONFIG_OPTION,
        json.encodeToJsonElement(SetConfigOptionParams(sessionId, configId, value)),
    )

    /**
     * `session/list` — returns the raw result JsonObject so callers can pick
     * out `_meta` fields (external URLs, working dir, …) themselves.
     */
    suspend fun sessionList(cwd: String? = null): JsonObject = request(
        AcpMethods.SESSION_LIST,
        json.encodeToJsonElement(SessionListParams(cwd)),
    ).let { (it["result"] as? JsonObject) ?: it }

    /** `session/list` decoded — lock/plan flags ride flat on each entry. */
    suspend fun listSessions(cwd: String? = null): List<AcpSession> =
        decodeSessions(sessionList(cwd))

    private fun decodeSessions(result: JsonObject): List<AcpSession> {
        val arr = result["sessions"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val meta = o["_meta"] as? JsonObject
            val id = o.strField("sessionId", "id") ?: return@mapNotNull null
            AcpSession(
                sessionId = id,
                title = o.strField("title", "name") ?: meta?.strField("title"),
                cwd = o.strField("cwd", "workingDirectory", "working_directory"),
                isLocked = o.boolField("isLocked") ?: meta?.boolField("isLocked") ?: false,
                lockHolderPid = o.longField("lockHolderPid")
                    ?: meta?.longField("lockHolderPid"),
                lockHolderClient = o.strField("lockHolderClient")
                    ?: meta?.strField("lockHolderClient"),
                planFilePath = o.strField("planFilePath")
                    ?: meta?.strField("planFilePath"),
                hasUserMessages = o.boolField("hasUserMessages")
                    ?: meta?.boolField("hasUserMessages") ?: false,
                lastActivity = o.strField(
                    "lastActivity", "lastActivityAgo", "last_activity_ago",
                    "updatedAt", "updated_at",
                ),
                running = o.boolField("running") ?: false,
                pendingPermission = o.boolField("pendingPermission") ?: false,
            )
        }
    }

    // ---- gateway-private `_devingo/*` surface --------------------------------
    // These are answered by the session-hosting hub itself, never forwarded to
    // devin. The app ships against them — no fallback to plain ACP.

    /**
     * `_devingo/hello` — introduces this client to the gateway and returns its
     * capability set (attach/sessions/journal/permissionResolved/…).
     */
    suspend fun hello(clientId: String): JsonObject = request(
        "_devingo/hello",
        buildJsonObject { put("clientId", clientId) },
    ).let { (it["result"] as? JsonObject) ?: it }

    /**
     * `_devingo/sessions` — devin's `session/list` merged with live hub state
     * (`running`, `pendingPermission`), served by the resident process.
     */
    suspend fun devingoSessions(cwd: String? = null): List<AcpSession> = request(
        "_devingo/sessions",
        buildJsonObject { if (!cwd.isNullOrBlank()) put("cwd", cwd) },
    ).let { decodeSessions((it["result"] as? JsonObject) ?: it) }

    /**
     * `_devingo/attach` — subscribe to [sessionId] and replay journaled
     * events past [afterSeq]. When the journal no longer reaches that far
     * the result's `needLoad` tells the caller to fall back to session/load.
     */
    suspend fun attach(sessionId: String, afterSeq: Long, cwd: String? = null): AttachResult = request(
        "_devingo/attach",
        buildJsonObject {
            put("sessionId", sessionId)
            put("afterSeq", afterSeq)
            // Re-homing the workspace hint: a hub that lost the record (e.g.
            // a lazily-recreated session entry) needs it before /fs/write
            // can resolve paths. The bridge only fills a MISSING cwd —
            // session/new|load stays authoritative.
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        },
    ).let { json.decodeFromJsonElement((it["result"] as? JsonObject) ?: it) }

    /** `_devingo/detach` — unsubscribe; the hub keeps hosting the session. */
    suspend fun detach(sessionId: String) = request(
        "_devingo/detach",
        buildJsonObject { put("sessionId", sessionId) },
    )

    /** `session/close` — idempotent release of the session lock agent-side. */
    suspend fun closeSession(sessionId: String) = request(
        AcpMethods.SESSION_CLOSE,
        buildJsonObject { put("sessionId", sessionId) },
    )

    // ---- revert / fork --------------------------------------------------------
    // Negotiated via clientCapabilities._meta["cognition.ai/revert"]; the
    // agent then accepts the _cognition.ai/revert/* surface below.

    /** Steps available for revert/fork, raw — the step schema is Devin's own. */
    suspend fun revertListSteps(sessionId: String): JsonObject = request(
        AcpMethods.REVERT_LIST_STEPS,
        json.encodeToJsonElement(RevertListStepsParams(sessionId)),
    ).let { (it["result"] as? JsonObject) ?: it }

    /** Loose decode of listSteps — tolerates key-name drift. */
    suspend fun revertSteps(sessionId: String): List<RevertStep> {
        val result = revertListSteps(sessionId)
        val arr = (result["steps"] as? JsonArray)
            ?: (result["revertSteps"] as? JsonArray)
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val stepId = o.strField("stepId", "id", "nodeId")
                ?: return@mapNotNull null
            RevertStep(
                stepId = stepId,
                summary = o.strField("summary", "title", "description", "label"),
                kind = o.strField("kind", "type"),
                userMessageId = o.strField("userMessageId"),
                stepNumber = o.intField("stepNumber"),
            )
        }
    }

    /**
     * Fork: branch a NEW session off [stepId]. The reply carries the new
     * sessionId (best-effort extraction — keys vary across builds).
     */
    suspend fun revertForkFromStep(sessionId: String, stepId: String): String? = request(
        AcpMethods.REVERT_FORK_FROM_STEP,
        json.encodeToJsonElement(RevertForkParams(sessionId, stepId)),
    ).let {
        val r = (it["result"] as? JsonObject) ?: it
        r.strField("sessionId", "newSessionId", "forkedSessionId")
    }

    /**
     * Revert-target params shared by preview/execute: the binary's
     * untagged `RevertTarget` union deserializes `{stepId}` |
     * `{userMessageId}` | `{targetNodeId}` flat next to `sessionId`.
     * A non-null [userMessageId] picks the revertByUserMessageId surface.
     */
    private fun revertTargetParams(
        sessionId: String,
        stepId: String,
        userMessageId: String?,
    ) = buildJsonObject {
        put("sessionId", sessionId)
        if (userMessageId != null) put("userMessageId", userMessageId)
        else put("stepId", stepId)
    }

    /**
     * `_cognition.ai/revert/preview` — which file changes reverting to
     * [stepId] would roll back (`fileActions`/`conflicts`/`irreversibleWarnings`
     * on the wire). Decoded loosely like the other revert payloads.
     */
    suspend fun revertPreview(
        sessionId: String,
        stepId: String,
        userMessageId: String? = null,
    ): RevertPreview {
        val result = request(
            AcpMethods.REVERT_PREVIEW,
            revertTargetParams(sessionId, stepId, userMessageId),
        ).let { (it["result"] as? JsonObject) ?: it }
        val files = listOf("fileActions", "files", "changes", "fileChanges")
            .firstNotNullOfOrNull { result[it] as? JsonArray }
            .orEmpty()
            .mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val path = o.strField("path", "filePath", "file", "relPath", "filename", "name")
                    ?: return@mapNotNull null
                val stats = o["diffStats"] as? JsonObject
                RevertPreviewFile(
                    path = path,
                    action = o.strField("kind", "type", "action", "status"),
                    additions = stats?.intField("insertions", "additions", "added", "addedLines")
                        ?: o.intField("insertions", "additions", "added", "addedLines", "linesAdded")
                        ?: 0,
                    deletions = stats?.intField("deletions", "removed", "removedLines")
                        ?: o.intField("deletions", "removed", "removedLines", "linesRemoved")
                        ?: 0,
                )
            }
        val warnings = (result["irreversibleWarnings"] as? JsonArray)
            .orEmpty()
            .mapNotNull { el ->
                (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (el as? JsonObject)?.let {
                        it.strField("message", "warning", "text")
                            ?: it.strField("path", "filePath", "file")
                    }
            }
        val conflicts = (result["conflicts"] as? JsonArray)
            .orEmpty()
            .mapNotNull { el ->
                (el as? JsonObject)?.strField("path", "filePath", "file", "relPath", "name")
                    ?: (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        return RevertPreview(files, warnings, conflicts)
    }

    /**
     * `_cognition.ai/revert/execute` — in-place revert: rewinds the
     * conversation AND the workspace files. Slow (walks the undo records),
     * so it gets the 2-minute budget. The agent may answer with a forked
     * session instead (rare) — surfaced via [RevertExecuteResult].
     */
    suspend fun revertExecute(
        sessionId: String,
        stepId: String,
        userMessageId: String? = null,
    ): RevertExecuteResult {
        val frame = request(
            AcpMethods.REVERT_EXECUTE,
            revertTargetParams(sessionId, stepId, userMessageId),
            timeoutMs = 120_000,
        )
        val r = (frame["result"] as? JsonObject) ?: frame
        val meta = r["_meta"] as? JsonObject
        return RevertExecuteResult(
            historyRewound = r.boolField("revertHistoryRewound") == true ||
                r.boolField("historyRewound") == true ||
                meta?.boolField("cognition.ai/revertHistoryRewound") == true,
            forkedSessionId = r.strField("forkedSessionId", "newSessionId", "sessionId")
                ?.takeIf { it.isNotBlank() && it != sessionId },
        )
    }

    /** Probe of Devin's `_cognition.ai/session/rename`; failures surface as AcpException. */
    suspend fun renameSession(sessionId: String, title: String) = request(
        AcpMethods.SESSION_RENAME,
        json.encodeToJsonElement(RenameSessionParams(sessionId, title)),
    )

    // ---- @-mention resource lists (A7.3) -------------------------------------
    // Devin advertises rule/skill/plan mention types; these feed the picker's
    // non-file categories. The response shape isn't documented, so decoding
    // is deliberately loose (see mentionList).

    /** `_cognition.ai/rules/list` — rules the workspace exposes to @. */
    suspend fun listRules(sessionId: String): List<MentionResource> =
        mentionList(AcpMethods.RULES_LIST, sessionId)

    /** `_cognition.ai/skills/list` — skills mentionable from the composer. */
    suspend fun listSkills(sessionId: String): List<MentionResource> =
        mentionList(AcpMethods.SKILLS_LIST, sessionId)

    /** `_cognition.ai/plans/list` — plans mentionable from the composer. */
    suspend fun listPlans(sessionId: String): List<MentionResource> =
        mentionList(AcpMethods.PLANS_LIST, sessionId)

    /**
     * Tolerant list decode: the result may be a bare array or an object
     * wrapping one under rules/skills/plans/items/…; each entry is an
     * object whose display name sits under name|title|id|key|…
     */
    private suspend fun mentionList(method: String, sessionId: String): List<MentionResource> {
        val result = request(
            method,
            buildJsonObject { put("sessionId", sessionId) },
        ).let { (it["result"] as? JsonObject) ?: it }
        val arr = listOf("rules", "skills", "plans", "items", "entries", "results")
            .firstNotNullOfOrNull { result[it] as? JsonArray }
            ?: result.values.firstOrNull { it is JsonArray } as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // Prefer the human label for display; the machine id stays ref.
            val name = o.strField("name", "title", "displayName", "label", "id", "key")
                ?: return@mapNotNull null
            MentionResource(
                id = o.strField("id", "key", "name", "title") ?: name,
                name = name,
                description = o.strField("description", "summary", "subtitle"),
            )
        }
    }

    // ---- subagents (A7.4) ----------------------------------------------------
    // Devin's `_cognition.ai/subagent/*` control surface — negotiated via
    // clientCapabilities._meta["cognition.ai/subagentSupport"]. [subagentId]
    // is the sharedSubagentId when the wire carries one, else the toolCallId.

    /** Stop a running subagent. */
    suspend fun subagentCancel(sessionId: String, subagentId: String) =
        subagentAction(AcpMethods.SUBAGENT_CANCEL, sessionId, subagentId)

    /** Move a subagent off the foreground (keeps running). */
    suspend fun subagentBackground(sessionId: String, subagentId: String) =
        subagentAction(AcpMethods.SUBAGENT_BACKGROUND, sessionId, subagentId)

    /** Bring a backgrounded subagent back to the foreground. */
    suspend fun subagentForeground(sessionId: String, subagentId: String) =
        subagentAction(AcpMethods.SUBAGENT_FOREGROUND, sessionId, subagentId)

    private suspend fun subagentAction(method: String, sessionId: String, subagentId: String) =
        request(
            method,
            buildJsonObject {
                put("sessionId", sessionId)
                put("subagentId", subagentId)
            },
        )

    private fun notify(method: String, params: JsonElement) {
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    private suspend fun request(
        method: String,
        params: JsonElement?,
        timeoutMs: Long = 60_000,
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        // Session-scoped requests carry params.sessionId; the journaled
        // response frame's _seq is tracked back to it in handleResponse.
        val sid = (params as? JsonObject)?.get("sessionId")
            ?.let { (it as? JsonPrimitive)?.textOrNull }
        pending[id] = PendingCall(deferred, sid)
        try {
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    if (params != null) put("params", params)
                },
            )
        } catch (e: Throwable) {
            pending.remove(id)
            deferred.completeExceptionally(e)
            throw e
        }
        return try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { deferred.await() }
            } else {
                deferred.await()
            }
        } catch (e: CancellationException) {
            pending.remove(id)
            deferred.cancel()
            throw e
        }
    }

    /** The `_seq` the gateway splices into journaled frames, else null. */
    private fun JsonObject.seq(): Long? =
        (this["_seq"] as? JsonPrimitive)?.longOrNull

    private fun send(obj: JsonObject) {
        val socket = ws ?: throw AcpException("未连接")
        if (!socket.send(obj.toString())) {
            throw AcpException("发送失败（连接已关闭）")
        }
    }

    private fun dispatch(text: String) {
        val obj = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            emit(null, 0, AcpEvent.Failure("无法解析的消息: ${e.message}"))
            return
        }

        val method = obj["method"]?.jsonPrimitive?.textOrNull
        // JSON-RPC uses `"id": null` for "no id"; JsonNull is NOT Kotlin null,
        // so it must be filtered out or a notification carrying a null id would
        // be misrouted to handleIncomingRequest.
        val id = obj["id"]?.takeIf { it !is JsonNull }
        val seq = obj.seq() ?: 0

        when {
            method == null && id != null -> handleResponse(id, obj)      // our response
            method != null && id == null -> handleNotification(method, obj["params"], seq)
            method != null && id != null -> handleIncomingRequest(method, id, obj["params"], seq)
        }
    }

    private fun handleResponse(id: JsonElement, obj: JsonObject) {
        val intId = (id as? JsonPrimitive)?.textOrNull?.toIntOrNull() ?: return
        val call = pending.remove(intId) ?: return
        // Journaled responses carry _seq; keep the session's cursor current.
        val seq = obj.seq()
        if (call.sessionId != null && seq != null) {
            seqTracker?.invoke(call.sessionId, seq)
        }
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.textOrNull?.toIntOrNull() ?: -1
            val message = error["message"]?.jsonPrimitive?.textOrNull ?: "未知错误"
            call.deferred.completeExceptionally(AcpException(message, code))
        } else {
            call.deferred.complete(obj)
        }
    }

    private fun handleIncomingRequest(method: String, id: JsonElement, params: JsonElement?, seq: Long) {
        val p = params as? JsonObject
        val sessionId = (p?.get("sessionId") as? JsonPrimitive)?.textOrNull
        // A journaled agent->client request consumed its _seq: record it
        // NOW, on the reader thread. The handler runs async on clientScope,
        // so seeding any later would let the next frame's liveGap check
        // read this seq as a hole (adjacency-only — a real gap before it
        // still trips and re-attaches).
        if (sessionId != null && seq > 0) seqTracker?.invoke(sessionId, seq)
        val req = IncomingRequest(id, method, p, sessionId, seq)
        // A handler that already answered and then throws would otherwise send
        // a SECOND message with the same id (the catch block's error response).
        val responded = AtomicBoolean(false)
        req.responder = { result, error ->
            if (!responded.compareAndSet(false, true)) {
                Log.w(TAG, "Duplicate response suppressed for $method (id=$id)")
            } else {
                runCatching {
                    send(
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            if (result != null) put("result", result)
                            if (error != null) put("error", error)
                        },
                    )
                }
            }
        }
        val handler = requestHandler ?: run {
            req.respondError(JSONRPC_METHOD_NOT_FOUND, "客户端未实现: $method")
            return
        }
        clientScope.launch {
            try {
                handler(req)
            } catch (e: Exception) {
                req.respondError(-32000, e.message ?: "handler failed")
            }
        }
    }

    private fun handleNotification(method: String, params: JsonElement?, seq: Long) {
        val p = params as? JsonObject ?: return
        val sessionId = (p["sessionId"] as? JsonPrimitive)?.textOrNull
        when (method) {
            AcpMethods.SESSION_UPDATE -> handleSessionUpdate(p, sessionId, seq)
            // Gateway-private markers (journaled, so they carry _seq).
            "_devingo/session_resync" ->
                emit(sessionId, seq, AcpEvent.SessionResync)
            "_devingo/session_deleted" ->
                emit(sessionId, seq, AcpEvent.SessionDeleted)
            "_devingo/agent_dead" -> emit(
                sessionId, seq,
                AcpEvent.AgentDead(
                    (p["reason"] as? JsonPrimitive)?.textOrNull
                        ?: "devin 进程已退出",
                ),
            )
            AcpMethods.ELICITATION_COMPLETE, AcpMethods.SESSION_ELICITATION_COMPLETE -> {
                val eid = listOf("elicitationId", "requestId", "id")
                    .firstNotNullOfOrNull { k ->
                        (p[k] as? JsonPrimitive)
                            ?.let { it.longOrNull ?: it.textOrNull?.toLongOrNull() }
                    }
                emit(sessionId, seq, AcpEvent.ElicitationComplete(eid))
            }
            "_devingo/permission_resolved" -> {
                val requestId = (p["requestId"] as? JsonPrimitive)?.longOrNull ?: return
                emit(
                    sessionId, seq,
                    AcpEvent.PermissionResolved(
                        requestId,
                        (p["outcome"] as? JsonPrimitive)?.textOrNull ?: "answered",
                    ),
                )
            }
            "_devingo/event" -> {
                // A journaled response owned by another client consumed its
                // _seq even when nothing below surfaces an event — seed it
                // or the next live frame reads the hole as a gap.
                if (sessionId != null && seq > 0) seqTracker?.invoke(sessionId, seq)
                // Only turn-ending frames matter here: a session/prompt
                // result carries stopReason, a prompt's error frame carries
                // only `error` — either way the remote turn is over. (An
                // error on another request kind would just end the marker a
                // beat early; the next chunk re-marks it.)
                val frame = p["event"] as? JsonObject
                val stopReason = ((frame?.get("result") as? JsonObject)
                    ?.get("stopReason") as? JsonPrimitive)?.textOrNull
                when {
                    stopReason != null ->
                        emit(sessionId, seq, AcpEvent.RemoteTurnEnd(stopReason))
                    frame?.get("error") != null -> emit(
                        sessionId, seq,
                        AcpEvent.RemoteTurnEnd(
                            ((frame["error"] as? JsonObject)?.get("message")
                                as? JsonPrimitive)?.textOrNull,
                        ),
                    )
                }
            }
            "_cognition.ai/output" -> {
                // Devin's extension channel for agent-side log lines.
                val channel = p["channel"]?.jsonPrimitive?.textOrNull ?: ""
                val message = p["message"]?.jsonPrimitive?.textOrNull ?: return
                val level = p["level"]?.jsonPrimitive?.textOrNull ?: "info"
                if (level != "info") {
                    emit(sessionId, seq, AcpEvent.AgentLog(channel, message, level))
                }
            }
            "_cognition.ai/compaction" -> {
                // Devin reports context compaction lifecycle via status/state.
                val status = (p["status"] as? JsonPrimitive)?.textOrNull
                    ?: (p["state"] as? JsonPrimitive)?.textOrNull
                if (status != null) {
                    emit(sessionId, seq, AcpEvent.CompactionUpdate(status))
                }
            }
            AcpMethods.REVERT_STEPS_UPDATED, "cognition.ai/revert/stepsUpdated" ->
                emit(sessionId, seq, AcpEvent.RevertStepsUpdated(p))
            // Devin registers these under `cognition.ai/*`; both spellings
            // reach the wire — the frontend listens on the underscored one.
            AcpMethods.REVERT_HISTORY_REWOUND, "cognition.ai/revert/historyRewound" ->
                emit(
                    sessionId, seq,
                    AcpEvent.RevertHistoryRewound(
                        (p["firstRemovedUserMessageId"] as? JsonPrimitive)?.textOrNull,
                    ),
                )
            // _cognition.ai/mcp/serversChanged and other extensions: ignore.
        }
    }

    private fun handleSessionUpdate(params: JsonObject, sessionId: String?, seq: Long) {
        val update = params["update"] as? JsonObject ?: return
        // Devin reports the live subagent count on a frame's `_meta` (A7.4) —
        // params- or update-level, whichever carries it.
        val numLiveSubagents = metaInt(params["_meta"], "cognition.ai/numLiveSubagents")
            ?: metaInt(update["_meta"], "cognition.ai/numLiveSubagents")
        if (numLiveSubagents != null) {
            emit(sessionId, seq, AcpEvent.SubagentCount(numLiveSubagents))
        }
        val kind = update["sessionUpdate"]?.jsonPrimitive?.textOrNull
        // Devin multiplexes descendant-agent frames onto the root stream,
        // tagged `subagent_context.parentAgentId` = the owning agent's
        // session id ("root" = this session itself). t3's devinAcp reroutes
        // them to that agent — the store folds them into the matching
        // subagent's output instead of rendering duplicate parent rows.
        val ctxId = ((update["_meta"] as? JsonObject)
            ?.get("cognition.ai/subagent_context") as? JsonObject)
            ?.get("parentAgentId")?.jsonPrimitive?.textOrNull
            ?.takeIf { it.isNotBlank() && it != "root" && it != sessionId }
        if (ctxId != null) {
            val ctxText = when (kind) {
                "tool_call", "tool_call_update" ->
                    runCatching {
                        json.decodeFromJsonElement<ToolCallInfo>(update)
                    }.getOrNull()?.let {
                        val t = it.title ?: it.kind ?: "tool call"
                        it.status?.let { s -> "$t · $s" } ?: t
                    }
                else -> update.textOfContent()
            }
            if (ctxText != null) {
                emit(
                    sessionId, seq,
                    AcpEvent.SubagentCtxFrame(
                        ctxId, ctxText,
                        newLine = kind == "tool_call" || kind == "tool_call_update",
                    ),
                )
            }
            return
        }
        when (kind) {
            "agent_message_chunk" -> update.textOfContent()?.let {
                emit(sessionId, seq, AcpEvent.AgentChunk(it))
            }
            "agent_thought_chunk" -> update.textOfContent()?.let {
                emit(sessionId, seq, AcpEvent.ThoughtChunk(it))
            }
            "user_message_chunk" -> update.textOfContent()?.let {
                emit(sessionId, seq, AcpEvent.UserChunk(it))
            }
            "available_commands_update" -> {
                val commands = (update["availableCommands"] as? JsonArray)
                    ?.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val meta = o["_meta"] as? JsonObject
                        AvailableCommand(
                            name = o["name"]?.jsonPrimitive?.textOrNull
                                ?: return@mapNotNull null,
                            description = o["description"]?.jsonPrimitive?.textOrNull,
                            inputHint = (o["input"] as? JsonObject)
                                ?.get("hint")?.jsonPrimitive?.textOrNull,
                            category = (meta?.get("cognition.ai/category") as? JsonPrimitive)
                                ?.textOrNull,
                            icon = (meta?.get("cognition.ai/icon") as? JsonPrimitive)
                                ?.textOrNull,
                        )
                    }
                    .orEmpty()
                emit(sessionId, seq, AcpEvent.CommandsAvailable(commands))
            }
            "tool_call", "tool_call_update" -> {
                val info = runCatching {
                    json.decodeFromJsonElement<ToolCallInfo>(update)
                }.getOrNull() ?: return
                when {
                    // A7.4: subagent calls route to the store's subagent
                    // list — they never land as transcript tool rows.
                    isSubagentCall(info) ->
                        emit(sessionId, seq, AcpEvent.SubagentUpdate(info))
                    else -> emit(
                        sessionId, seq,
                        if (kind == "tool_call") AcpEvent.ToolCall(info)
                        else AcpEvent.ToolCallUpdate(info),
                    )
                }
            }
            "tool_call_content_chunk" -> {
                // Devin streams tool output as chunks — the store appends
                // them to the row's detail rather than replacing it.
                runCatching {
                    json.decodeFromJsonElement<ToolCallInfo>(update)
                }.getOrNull()?.let {
                    emit(sessionId, seq, AcpEvent.ToolContentChunk(it))
                }
            }
            "plan" -> {
                val entries = (update["entries"] as? JsonArray)?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    PlanEntry(
                        content = o["content"]?.jsonPrimitive?.textOrNull
                            ?: return@mapNotNull null,
                        status = o["status"]?.jsonPrimitive?.textOrNull,
                        priority = o["priority"]?.jsonPrimitive?.textOrNull,
                    )
                }.orEmpty()
                emit(sessionId, seq, AcpEvent.PlanUpdate(entries))
            }
            "current_mode_update" -> {
                update["currentModeId"]?.jsonPrimitive?.textOrNull?.let {
                    emit(sessionId, seq, AcpEvent.ModeChanged(it))
                }
            }
            "config_option_update" -> {
                // Devin re-sends the full configOptions array after a change.
                val options = (update["configOptions"] as? JsonArray)?.mapNotNull { el ->
                    runCatching {
                        json.decodeFromJsonElement<ConfigOption>(el)
                    }.getOrNull()
                }.orEmpty()
                emit(sessionId, seq, AcpEvent.ConfigOptionsUpdated(options))
            }
            "session_info_update" -> {
                update["title"]?.jsonPrimitive?.textOrNull?.let { title ->
                    emit(
                        sessionId, seq,
                        AcpEvent.SessionTitle(title, update["_meta"] as? JsonObject)
                    )
                }
            }
            "usage_update" -> {
                val used = (update["used"] as? JsonPrimitive)?.numberAsLong
                val size = (update["size"] as? JsonPrimitive)?.numberAsLong
                if (used != null && size != null) {
                    val meta = update["_meta"] as? JsonObject
                    emit(
                        sessionId, seq,
                        AcpEvent.UsageUpdate(
                            used = used,
                            size = size,
                            inputTokens = (meta?.get("cognition.ai/inputTokens")
                                as? JsonPrimitive)?.numberAsLong,
                            outputTokens = (meta?.get("cognition.ai/outputTokens")
                                as? JsonPrimitive)?.numberAsLong,
                            cacheReadTokens = (meta?.get("cognition.ai/cachedReadTokens")
                                as? JsonPrimitive)?.numberAsLong,
                        ),
                    )
                }
            }
            else -> Log.w(TAG, "Unknown sessionUpdate kind: $kind")
        }
    }

    private fun JsonObject.textOfContent(): String? =
        (this["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.textOrNull
            ?: (this["content"] as? JsonPrimitive)?.textOrNull

    /**
     * A `tool_call` carrying a Devin subagent (A7.4). The documented wire
     * shape (t3's `devinAcp.ts`) marks the spawn/completion as OBJECTS on
     * `_meta` — `cognition.ai/subagent_started` / `subagent_completed`.
     * Below that: kind `subagent`/`task`, `inferenceToolName` naming a task
     * spawner, or a flat child-session key (unverified — kept as a
     * fallback for other devin builds).
     */
    private fun isSubagentCall(call: ToolCallInfo): Boolean {
        val meta = call._meta
        if (meta?.get("cognition.ai/subagent_started") is JsonObject ||
            meta?.get("cognition.ai/subagent_completed") is JsonObject
        ) {
            return true
        }
        if (call.kind == "subagent" || call.kind == "task") return true
        if (meta == null) return false
        if (meta.metaPresent("cognition.ai/childSessionUrl") ||
            meta.metaPresent("cognition.ai/sharedSubagentId") ||
            meta.metaPresent("cognition.ai/childSessionId")
        ) {
            return true
        }
        return (meta["cognition.ai/inferenceToolName"] as? JsonPrimitive)
            ?.textOrNull in SUBAGENT_TOOL_NAMES
    }

    /** Present and not `JsonNull` — `"k": null` mustn't classify a call. */
    private fun JsonObject.metaPresent(key: String): Boolean =
        this[key].let { it != null && it !is JsonNull }

    /** Loose int read on a `_meta` object: number literal or numeric string. */
    private fun metaInt(meta: JsonElement?, key: String): Int? =
        ((meta as? JsonObject)?.get(key) as? JsonPrimitive)
            ?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() ?: it.textOrNull?.toIntOrNull() }

    private fun failAll(reason: String, emitEvent: Boolean = true) {
        // Claim each deferred with an atomic remove — a put() racing a
        // values() snapshot + clear() could strand it uncompleted forever.
        pending.entries.forEach {
            if (pending.remove(it.key, it.value)) {
                it.value.deferred.completeExceptionally(AcpException(reason))
            }
        }
        if (emitEvent) emit(null, 0, AcpEvent.Disconnected(reason))
    }

    private fun closeText(code: Int, reason: String): String =
        if (reason.isBlank()) "连接已断开 ($code)" else "连接已断开 ($code): $reason"

    companion object {
        private const val TAG = "AcpClient"

        /**
         * The `clientCapabilities._meta` declaration that unlocks devin's
         * full `agentCapabilities._meta` echo — parsed into [AgentCaps].
         *
         * Through the gateway this never reaches devin (the hub answers
         * client `initialize` from its cached handshake), so the functional
         * declaration lives in `gatewayInitParams` (bridge/hub.go) — keep
         * both in sync; this stays for spec parity when no gateway sits
         * in between.
         */
        private val CLIENT_CAPS_META = buildJsonObject {
            put("cognition.ai/revert", true)
            put("cognition.ai/multiRootWorkspace", true)
            put("cognition.ai/subagentSupport", true)
            put("cognition.ai/sessionRename", true)
            put("cognition.ai/sessionShare", true)
        }

        /** `inferenceToolName` values that spawn a subagent (A7.4). */
        private val SUBAGENT_TOOL_NAMES =
            setOf("task", "subagent", "spawn_subagent", "spawn_agent")
    }
}

private val JsonPrimitive.textOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }

/** First matching key as a string, or null (skips JsonNull / non-strings). */
internal fun JsonObject.strField(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.let { if (it.isString) it.content else null }
    }

internal fun JsonObject.boolField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/** First matching key as an int — numeric literal or float (truncated). */
internal fun JsonObject.intField(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)
            ?.let { it.longOrNull?.toInt() ?: it.doubleOrNull?.toInt() }
    }

internal fun JsonObject.longField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

/**
 * lenient number decode: accepts integral literals, floats (truncated toward
 * zero) and numeric strings — a bare `longOrNull` drops the latter two.
 */
private val JsonPrimitive.numberAsLong: Long?
    get() = longOrNull ?: doubleOrNull?.toLong()

private val healthJson = Json { ignoreUnknownKeys = true }

class AcpException(message: String, val code: Int = -1) : Exception(message)

@Serializable
data class BridgeHealth(
    val ok: Boolean = false,
    val devin: String = "",
    val devinFound: Boolean = false,
    val defaultCwd: String = "",
    val authEnabled: Boolean = false,
)

/**
 * Models from the bridge's /models endpoint (`devin models list`). The JSON
 * layout is family-grouped and not part of a stable schema, so this walks the
 * structure and lifts out every object carrying an id + name pair.
 */
suspend fun fetchBridgeModels(baseHttpUrl: String, token: String?): List<SessionModel> =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("$baseHttpUrl/models").apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AcpException("models HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        extractModels(healthJson.parseToJsonElement(body))
    }

/**
 * The bridge's `/fs/write`: write [content] to [path] inside the session's
 * recorded workspace (token-authed, path containment enforced server-side).
 * Throws [AcpException] on any non-2xx so the caller can surface the error.
 */
suspend fun postBridgeFile(
    baseHttpUrl: String,
    token: String?,
    sessionId: String,
    path: String,
    content: String,
) = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    val payload = buildJsonObject {
        put("sessionId", sessionId)
        put("path", path)
        put("content", content)
    }
    val req = Request.Builder()
        .url("$baseHttpUrl/fs/write")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = runCatching {
                resp.body?.string()
                    ?.let { healthJson.parseToJsonElement(it).jsonObject["error"] }
                    ?.jsonPrimitive?.content
            }.getOrNull()
            throw AcpException(detail ?: "fs/write HTTP ${resp.code}")
        }
    }
}

/** The bridge's `/git/diff` payload — `truncated` means the 4 MiB cap cut the patch. */
data class GitDiffResult(val patch: String, val truncated: Boolean)

/**
 * The bridge's `/git/diff`: the session workspace's uncommitted `git diff`
 * patch (token-authed; server caps it ~4 MiB). Throws [AcpException] on any
 * non-2xx so the review sheet can surface the error.
 */
suspend fun postBridgeGitDiff(
    baseHttpUrl: String,
    token: String?,
    sessionId: String,
): GitDiffResult = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    val payload = buildJsonObject {
        put("sessionId", sessionId)
    }
    val req = Request.Builder()
        .url("$baseHttpUrl/git/diff")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = runCatching {
                resp.body?.string()
                    ?.let { healthJson.parseToJsonElement(it).jsonObject["error"] }
                    ?.jsonPrimitive?.content
            }.getOrNull()
            throw AcpException(detail ?: "git/diff HTTP ${resp.code}")
        }
        val body = resp.body?.string().orEmpty()
        val obj = healthJson.parseToJsonElement(body).jsonObject
        GitDiffResult(
            patch = obj["patch"]?.jsonPrimitive?.content.orEmpty(),
            truncated = (obj["truncated"] as? JsonPrimitive)
                ?.booleanOrNull == true,
        )
    }
}

/**
 * The bridge's `/fs/list`: the session workspace's file paths, cwd-relative
 * (tracked + non-ignored untracked, ≤2000 — N6.2's @file picker feed).
 * Throws [AcpException] on any non-2xx so the picker can surface the error.
 */
suspend fun postBridgeFsList(
    baseHttpUrl: String,
    token: String?,
    sessionId: String,
): List<String> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    val payload = buildJsonObject {
        put("sessionId", sessionId)
    }
    val req = Request.Builder()
        .url("$baseHttpUrl/fs/list")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = runCatching {
                resp.body?.string()
                    ?.let { healthJson.parseToJsonElement(it).jsonObject["error"] }
                    ?.jsonPrimitive?.content
            }.getOrNull()
            throw AcpException(detail ?: "fs/list HTTP ${resp.code}")
        }
        val body = resp.body?.string().orEmpty()
        val arr = healthJson.parseToJsonElement(body).jsonObject["files"]
        (arr as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
    }
}

/** One /fs/search hit — a file or directory inside the session workspace. */
data class FsSearchItem(val relPath: String, val isDir: Boolean)

/**
 * The bridge's `/fs/search` (GET): fuzzy-matches files AND directories
 * under the session's recorded workspace — the @-picker's typed-query
 * feed, backed by a real filesystem walk rather than `git ls-files`
 * (untracked dirs included; A7.3). Throws [AcpException] on non-2xx.
 */
suspend fun getBridgeFsSearch(
    baseHttpUrl: String,
    token: String?,
    sessionId: String,
    query: String,
    limit: Int = 50,
): List<FsSearchItem> = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    val url = "$baseHttpUrl/fs/search".toHttpUrl().newBuilder()
        .addQueryParameter("sessionId", sessionId)
        .addQueryParameter("q", query)
        .addQueryParameter("limit", limit.toString())
        .build()
    val req = Request.Builder()
        .url(url)
        .apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }
        .build()
    client.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val detail = runCatching {
                resp.body?.string()
                    ?.let { healthJson.parseToJsonElement(it).jsonObject["error"] }
                    ?.jsonPrimitive?.content
            }.getOrNull()
            throw AcpException(detail ?: "fs/search HTTP ${resp.code}")
        }
        val body = resp.body?.string().orEmpty()
        val arr = healthJson.parseToJsonElement(body).jsonObject["items"]
        (arr as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rel = o.strField("relPath", "path") ?: return@mapNotNull null
            FsSearchItem(rel, o.strField("kind", "type") == "directory")
        } ?: emptyList()
    }
}

private fun extractModels(el: JsonElement, out: MutableList<SessionModel> = mutableListOf()): List<SessionModel> {
    when (el) {
        is JsonObject -> {
            val id = listOf("modelId", "model_id", "id", "slug")
                .firstNotNullOfOrNull { (el[it] as? JsonPrimitive)
                    ?.let { p -> if (p.isString) p.content else null } }
            val name = listOf("name", "title", "label")
                .firstNotNullOfOrNull { (el[it] as? JsonPrimitive)
                    ?.let { p -> if (p.isString) p.content else null } }
            if (id != null && name != null) {
                out += SessionModel(modelId = id, name = name)
            } else {
                el.values.forEach { extractModels(it, out) }
            }
        }
        is JsonArray -> el.forEach { extractModels(it, out) }
        else -> Unit
    }
    return out.distinctBy { it.modelId }.toMutableList()
}

/** Per-candidate /healthz budget for multi-address probing (C5). */
const val BRIDGE_PROBE_TIMEOUT_MS = 1_500L

/**
 * Bare reachability check for a candidate bridge address: /healthz is
 * unauthenticated by design, so any success response means a bridge lives
 * at [baseHttpUrl]. Connect+read share one [timeoutMs] budget.
 */
suspend fun probeBridgeHealth(baseHttpUrl: String, timeoutMs: Long): Boolean =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url("$baseHttpUrl/healthz").build()
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }
            .getOrDefault(false)
    }

/** HTTP helper for the bridge's /healthz endpoint (pre-flight checks). */
suspend fun fetchBridgeHealth(baseHttpUrl: String, token: String?): BridgeHealth =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("$baseHttpUrl/healthz").apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AcpException("healthz HTTP ${resp.code}")
            healthJson.decodeFromString(BridgeHealth.serializer(), resp.body?.string() ?: "")
        }
    }
