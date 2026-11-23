package com.devingo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Base64
import com.devingo.app.acp.ACP_ERROR_AUTH_REQUIRED
import com.devingo.app.acp.AcpClient
import com.devingo.app.acp.AcpEvent
import com.devingo.app.acp.AcpException
import com.devingo.app.acp.AcpMethods
import com.devingo.app.acp.AcpSession
import com.devingo.app.acp.AvailableCommand
import com.devingo.app.acp.BridgeHealth
import com.devingo.app.acp.ConfigOption
import com.devingo.app.acp.ContentBlock
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
import com.devingo.app.acp.fetchBridgeHealth
import com.devingo.app.acp.fetchBridgeModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { Idle, Checking, Connecting, Authenticating, StartingSession, Ready, Error }

    data class UiState(
        val phase: Phase = Phase.Idle,
        val statusText: String = "",
        val error: String? = null,
        val agentTitle: String? = null,
        val sessionId: String? = null,
        val modes: List<SessionMode> = emptyList(),
        val currentModeId: String? = null,
        val models: List<SessionModel> = emptyList(),
        val currentModelId: String? = null,
        val commands: List<AvailableCommand> = emptyList(),
        val sessionTitle: String? = null,
        /** Context window usage: [contextUsed] of [contextSize] tokens. */
        val contextUsed: Long = 0,
        val contextSize: Long = 0,
        /** Cumulative turn token accounting from `usage_update` (if the agent reports it). */
        val usageInput: Long = 0,
        val usageOutput: Long = 0,
        val usageCacheRead: Long = 0,
        val running: Boolean = false,
        /** True while `authenticate` is in flight — the browser is waiting on the PC. */
        val awaitingBrowserLogin: Boolean = false,
        /** True while the auto-reconnect loop is retrying a dropped socket. */
        val reconnecting: Boolean = false,
        val authMethods: List<String> = emptyList(),
        /** configOptions entries outside mode/model (effort, thinking, …). */
        val extraConfigOptions: List<ConfigOption> = emptyList(),
    )

    enum class Kind { User, Agent, Thought, Tool, Plan, System, Error }

    data class ChatItem(
        val id: Long,
        val kind: Kind,
        val text: String,
        val status: String? = null,
        val toolCallId: String? = null,
        /** ACP tool kind (execute/read/edit/…) — drives the timeline node icon. */
        val toolKind: String? = null,
        /** Expandable detail for tool calls: output text, diff paths, etc. */
        val detail: String? = null,
        /** File diffs reported inside the tool call's content blocks. */
        val diffs: List<ToolDiff> = emptyList(),
        val planEntries: List<PlanEntry> = emptyList(),
        /** True when the prompt never reached the agent (send threw before any update). */
        val failed: Boolean = false,
    )

    /** One `diff` content item of a tool call: unified old/new file text. */
    data class ToolDiff(val path: String, val oldText: String?, val newText: String)

    data class PendingPermission(
        val toolCall: ToolCallInfo,
        val options: List<PermissionOption>,
        internal val choice: CompletableDeferred<String?>,
    )

    private companion object {
        /** Keep at most this many transcript items; oldest beyond the head are dropped. */
        const val MAX_MESSAGES = 1500
        /** Items at the head (session banner, first user prompt) that trimming keeps. */
        const val KEEP_HEAD = 10
        /** Streaming chunks flush to the list at most every this many ms… */
        const val FLUSH_INTERVAL_MS = 60L
        /** …or immediately once the buffer holds this many chars. */
        const val FLUSH_CHARS = 200
        /** Permission requests auto-resolve to "cancelled" after this. */
        const val PERMISSION_TIMEOUT_MS = 5 * 60_000L
        /** If session/cancel doesn't end the prompt job within this, kill it. */
        const val CANCEL_WATCHDOG_MS = 10_000L
        /**
         * Combined compressed bytes of one send's attachments (pre-Base64).
         * Decimal MB: after 4/3 Base64 inflation + JSON envelope this still
         * lands under the bridge's 4 MiB WS message limit.
         */
        const val MAX_ATTACHMENT_BYTES = 3_000_000L
        const val RECONNECT_MAX_ATTEMPTS = 3
        const val RECONNECT_BASE_DELAY_MS = 2_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val client = AcpClient(json)
    private val notifier = Notifier(getApplication())

    /** Android 13+ runtime request for POST_NOTIFICATIONS. */
    fun requestNotificationPermission(activity: android.app.Activity) =
        notifier.ensurePermission(activity)
    private var nextItemId = 0L
    private var promptJob: Job? = null
    private var reconnectJob: Job? = null
    private var steerJob: Job? = null
    /** disconnect()'s async closeSession→client.disconnect tail; connect() joins it. */
    private var teardownJob: Job? = null

    /** Prompts queued while a turn is running — sent FIFO when it ends. */
    private val _queuedPrompts = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = _queuedPrompts.asStateFlow()

    /** Steps offered by the revert sheet; null until [loadRevertSteps] runs. */
    private val _revertSteps = MutableStateFlow<List<RevertStep>?>(null)
    val revertSteps: StateFlow<List<RevertStep>?> = _revertSteps.asStateFlow()

    // Streaming accumulation: chunks land in [streamBuf] and flush to the
    // message list on a timer (or at FLUSH_CHARS), instead of copying the
    // whole transcript per chunk.
    private var streamingId: Long? = null
    private var streamKind: Kind? = null
    private val streamBuf = StringBuilder()
    private var flushJob: Job? = null

    private var planItemId: Long? = null
    private var pendingCwd: String = ""
    private var pendingResumeId: String? = null
    private var pendingApiKey: String? = null
    private var bridgeHttpUrl: String = ""
    private var bridgeToken: String? = null
    private var activeCwd: String = ""

    // Last successful connection parameters, for auto-reconnect.
    private var lastHost: String = ""
    private var lastResumeCwd: String = ""
    private var lastResumeSessionId: String? = null

    /** True once any turn activity (chunk/tool/plan) arrived for the in-flight prompt. */
    @Volatile private var promptAcked = false
    /** Called when a session is ready so the app can persist its id. */
    var onSessionReady: ((sessionId: String, cwd: String) -> Unit)? = null
    @Volatile private var intentionalDisconnect = false

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    // Permission requests queue internally; the UI only ever sees the head,
    // so a second concurrent request can't overwrite the pending one.
    private val permissionLock = Any()
    private val permissionQueue = ArrayDeque<PendingPermission>()
    private val _pendingPermission = MutableStateFlow<PendingPermission?>(null)
    val pendingPermission: StateFlow<PendingPermission?> = _pendingPermission.asStateFlow()

    init {
        client.requestHandler = { req -> onAgentRequest(req) }
        viewModelScope.launch {
            client.events.collect { onEvent(it) }
        }
    }

    // ---- connection lifecycle ------------------------------------------------

    /**
     * Connect to the bridge and open a session. When [resumeSessionId] is set,
     * `session/load` replays the existing transcript instead of starting fresh.
     */
    fun connect(
        host: String,
        token: String,
        cwd: String,
        resumeSessionId: String? = null,
        apiKey: String? = null,
    ) {
        if (_ui.value.phase in listOf(
                Phase.Checking, Phase.Connecting, Phase.Authenticating, Phase.StartingSession,
            )
        ) return
        reconnectJob?.cancel()
        reconnectJob = null
        lastHost = host
        pendingResumeId = resumeSessionId
        pendingApiKey = apiKey?.trim()?.ifBlank { null }
        // A new connection epoch supersedes the old resume/cwd anchors —
        // they're re-established once a session is up (rememberSession,
        // pendingCwd). Leaving them stale would let a mid-handshake drop
        // auto-resume the previous session or attach a dead cwd.
        lastResumeSessionId = null
        lastResumeCwd = ""
        activeCwd = ""
        pendingCwd = ""
        bridgeHttpUrl = healthUrl(host)
        bridgeToken = token.ifBlank { null }
        viewModelScope.launch {
            try {
                _ui.update {
                    it.copy(
                        phase = Phase.Checking,
                        statusText = "检查 bridge…",
                        error = null,
                        reconnecting = false,
                    )
                }
                val health = runCatching { fetchBridgeHealth(healthUrl(host), token.ifBlank { null }) }
                    .getOrNull()
                if (health != null && !health.devinFound) {
                    _ui.update { it.copy(phase = Phase.Error, error = "bridge 上找不到 devin CLI（${health.devin}）") }
                    return@launch
                }

                _ui.update { it.copy(phase = Phase.Connecting, statusText = "建立 WebSocket…") }
                // disconnect()'s teardown can hold the old socket ~2s
                // (closeSession) — it must finish before a fresh socket is
                // created, or the tail's ws=null/close would kill it. Only
                // then do drops count again, so re-arm the flag here, not at
                // the top: while the old socket is still `ws`, its genuine
                // Disconnected must stay swallowed too.
                teardownJob?.join()
                intentionalDisconnect = false
                client.connect(wsUrl(host), token.ifBlank { null })

                _ui.update { it.copy(statusText = "ACP 握手…") }
                val init = client.initialize()

                val workingDir = cwd.ifBlank { health?.defaultCwd.orEmpty() }
                pendingCwd = workingDir
                _ui.update {
                    it.copy(
                        phase = Phase.StartingSession,
                        statusText = "创建会话…",
                        agentTitle = init.agentInfo?.title ?: init.agentInfo?.name,
                        authMethods = init.authMethods.map { m -> m.id },
                    )
                }
                startSession(workingDir, init.authMethods.isNotEmpty())
            } catch (e: TimeoutCancellationException) {
                _ui.update { it.copy(phase = Phase.Error, error = "连接超时") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = Phase.Error, error = e.display()) }
            }
        }
    }

    private suspend fun startSession(cwd: String, canAuthenticate: Boolean) {
        try {
            // Keep pendingResumeId until the session is up: an auth retry must
            // still resume rather than silently starting a fresh session.
            if (pendingResumeId != null) {
                val resumeId = pendingResumeId!!
                _ui.update { it.copy(statusText = "恢复会话…") }
                // session/load replays history as events while the request is
                // in flight — clear first so replayed items don't stack onto
                // the previous transcript (e.g. after a reconnect).
                resetTranscript()
                val s = client.loadSession(resumeId, cwd)
                pendingResumeId = null
                activeCwd = cwd
                rememberSession(resumeId, cwd)
                _ui.update {
                    it.copy(phase = Phase.Ready, statusText = "", sessionId = resumeId)
                }
                applySessionConfig(s)
                onSessionReady?.invoke(resumeId, cwd)
            } else {
                resetTranscript()
                val s = client.newSession(cwd)
                activeCwd = cwd
                rememberSession(s.sessionId, cwd)
                _ui.update {
                    it.copy(
                        phase = Phase.Ready,
                        statusText = "",
                        sessionId = s.sessionId,
                    )
                }
                applySessionConfig(s)
                // New sessions aren't persisted by `devin` until they have a
                // turn, so the id is only saved once the first prompt lands
                // (see send()); otherwise "resume last" offers dead ids.
            }
            append(Kind.System, "会话已建立 · $cwd")
            if (_ui.value.models.isEmpty()) loadBridgeModels()
        } catch (e: AcpException) {
            if (e.isAuthError() && canAuthenticate) {
                authenticate()
            } else {
                throw e
            }
        }
    }

    /** Remember the live session so an unintended drop can be restored. */
    private fun rememberSession(sessionId: String, cwd: String) {
        lastResumeSessionId = sessionId
        lastResumeCwd = cwd
    }

    // ---- auto reconnect --------------------------------------------------------

    /**
     * Socket died without the user asking for it. Retry with exponential
     * backoff (2s, 4s, 8s — capped at 30s, at most 3 attempts); on success
     * `session/load` restores the session and its transcript replay.
     */
    private fun scheduleReconnect(reason: String) {
        if (reconnectJob?.isActive == true) return
        // e.g. "连接已断开 (1011): devin acp exited" — the bridge's close
        // reason is why the socket dropped, so it belongs in the line.
        val detail = reason.ifBlank { "连接已断开" }
        val host = lastHost
        val token = bridgeToken
        // pendingResumeId covers a drop mid-resume-handshake: the user
        // already chose that session, so it stays the reconnect target.
        val sessionId = lastResumeSessionId ?: _ui.value.sessionId ?: pendingResumeId
        val cwd = lastResumeCwd.ifBlank { activeCwd.ifBlank { pendingCwd } }
        if (host.isBlank() || sessionId.isNullOrBlank() || cwd.isBlank()) {
            _ui.update {
                it.copy(phase = Phase.Error, error = detail, running = false)
            }
            append(Kind.System, detail)
            return
        }
        append(Kind.System, "$detail，尝试自动重连…")
        reconnectJob = viewModelScope.launch {
            _ui.update {
                it.copy(reconnecting = true, running = false, statusText = "连接已断开")
            }
            var restored = false
            var lastError = ""
            for (attempt in 1..RECONNECT_MAX_ATTEMPTS) {
                val backoff = (RECONNECT_BASE_DELAY_MS shl (attempt - 1))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                _ui.update { it.copy(statusText = "重连中（第 $attempt/${RECONNECT_MAX_ATTEMPTS} 次）…") }
                delay(backoff)
                try {
                    client.connect(wsUrl(host), token)
                    client.initialize()
                    // Replay lands as events during loadSession — clear first.
                    resetTranscript()
                    val s = client.loadSession(sessionId, cwd)
                    activeCwd = cwd
                    rememberSession(sessionId, cwd)
                    _ui.update {
                        it.copy(
                            phase = Phase.Ready,
                            statusText = "",
                            error = null,
                            sessionId = sessionId,
                            reconnecting = false,
                        )
                    }
                    applySessionConfig(s)
                    onSessionReady?.invoke(sessionId, cwd)
                    append(Kind.System, "已重连并恢复会话")
                    if (_ui.value.models.isEmpty()) loadBridgeModels()
                    restored = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.display()
                }
            }
            if (!restored) {
                _ui.update {
                    it.copy(
                        phase = Phase.Error,
                        reconnecting = false,
                        running = false,
                        error = "自动重连失败: $lastError",
                    )
                }
                append(Kind.System, "自动重连失败，请重新连接")
            }
        }
    }

    /**
     * Devin reports modes/models as `configOptions` (categories "mode" and
     * "model") on both `session/new` and `session/load`; the ACP `modes`/
     * `models` fields are the fallback shape.
     */
    private fun applySessionConfig(result: SessionNewResult) {
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

    private fun AcpException.isAuthError(): Boolean =
        code == ACP_ERROR_AUTH_REQUIRED || message?.let {
            it.contains("auth", ignoreCase = true) ||
                it.contains("login", ignoreCase = true) ||
                it.contains("登录") || it.contains("认证")
        } == true

    /**
     * Triggered manually from the UI or automatically on auth_required. Uses
     * the stored Devin API key when one was supplied at connect time — that
     * skips the browser round-trip entirely.
     */
    fun authenticate() {
        val apiKey = pendingApiKey
        viewModelScope.launch {
            try {
                if (apiKey != null) {
                    _ui.update { it.copy(phase = Phase.Authenticating, statusText = "API key 登录中…") }
                    client.authenticate("devin-browser", apiKey)
                } else {
                    _ui.update { it.copy(phase = Phase.Authenticating, awaitingBrowserLogin = true, statusText = "等待在电脑浏览器中完成登录…") }
                    client.authenticate("devin-browser")
                }
                _ui.update { it.copy(awaitingBrowserLogin = false, phase = Phase.StartingSession, statusText = "已登录，创建会话…") }
                startSession(pendingCwd, canAuthenticate = false)
            } catch (e: TimeoutCancellationException) {
                _ui.update { it.copy(phase = Phase.Error, awaitingBrowserLogin = false, error = "登录超时") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(phase = Phase.Error, awaitingBrowserLogin = false, error = "认证失败: ${e.display()}") }
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        pendingResumeId = null
        // The resume/cwd anchors belong to this connection epoch — the
        // session is intentionally left, so a new connect() must never
        // revive it or its working directory.
        lastResumeSessionId = null
        lastResumeCwd = ""
        activeCwd = ""
        reconnectJob?.cancel()
        reconnectJob = null
        promptJob?.cancel()
        steerJob?.cancel()
        // Tell the agent to release the session lock before the socket dies —
        // orphaned acp processes otherwise hold isLocked until reaped.
        val sid = _ui.value.sessionId
        teardownJob = viewModelScope.launch {
            if (sid != null) {
                runCatching { withTimeoutOrNull(2_000) { client.closeSession(sid) } }
            }
            client.disconnect()
        }
        _ui.value = UiState()
        _queuedPrompts.value = emptyList()
        _revertSteps.value = null
        resetTranscript()
        clearPermissions()
    }

    // ---- sessions over a throwaway ACP link ------------------------------------
    // session/list + session/delete need initialize but NOT authenticate, so
    // a lightweight control handshake covers the connect screen's history
    // sheet without spinning up a full session.

    suspend fun listSessionsOnBridge(host: String, token: String, cwd: String): List<AcpSession> =
        acpControl(host, token) { it.listSessions(cwd.ifBlank { null }) }

    suspend fun deleteSessionOnBridge(host: String, token: String, sessionId: String): Boolean =
        acpControl(host, token) { c ->
            runCatching { c.deleteSession(sessionId) }.isSuccess
        }

    private suspend fun <T> acpControl(
        host: String,
        token: String,
        block: suspend (AcpClient) -> T,
    ): T {
        val c = AcpClient(json)
        try {
            c.connect(wsUrl(host), token.ifBlank { null })
            c.initialize()
            return block(c)
        } finally {
            c.shutdown()
        }
    }

    // ---- revert / fork ---------------------------------------------------------

    /** Populate the revert sheet's step list for the live session. */
    fun loadRevertSteps() {
        val sid = _ui.value.sessionId ?: return
        _revertSteps.value = null
        viewModelScope.launch {
            try {
                _revertSteps.value = client.revertSteps(sid)
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

    /**
     * Fork a new session off [stepId], then switch the transcript to it —
     * the same acp process can `session/load` the forked id.
     */
    fun forkFromStep(stepId: String) {
        val sid = _ui.value.sessionId ?: return
        viewModelScope.launch {
            try {
                val newId = client.revertForkFromStep(sid, stepId)
                _revertSteps.value = null
                if (newId.isNullOrBlank()) {
                    append(Kind.System, "分叉请求已提交，但未返回新会话 ID")
                } else {
                    append(Kind.System, "已分叉出新会话 $newId，正在切换…")
                    resetTranscript()
                    val s = client.loadSession(newId, activeCwd)
                    rememberSession(newId, activeCwd)
                    _ui.update { it.copy(sessionId = newId) }
                    applySessionConfig(s)
                    onSessionReady?.invoke(newId, activeCwd)
                    append(Kind.System, "已切换到分叉会话 · $activeCwd")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "分叉失败: ${e.display()}")
            }
        }
    }

    // ---- prompts -------------------------------------------------------------

    /** A photo/screenshot picked on-device, sent as an ACP `image` block. */
    class ImageAttachment(val bytes: ByteArray, val mimeType: String)

    data class QueuedPrompt(val text: String, val attachmentCount: Int = 0)

    /**
     * Send while idle. When a turn is running the message joins the queue
     * (Devin-style "Queue" semantics); use [steer] for an immediate
     * mid-turn interrupt, which `midTurnPrompts` support enables.
     *
     * Returns false when nothing was sent — the caller must keep the input
     * and attachments so the user doesn't lose them.
     */
    fun send(text: String, attachments: List<ImageAttachment> = emptyList()): Boolean {
        val sid = _ui.value.sessionId ?: return false
        val trimmed = text.trimEnd()
        if (trimmed.isBlank() && attachments.isEmpty()) return false
        val total = attachments.sumOf { it.bytes.size.toLong() }
        if (total > MAX_ATTACHMENT_BYTES) {
            append(Kind.Error, "图片合计超过 3MB，未发送")
            return false
        }
        if (_ui.value.running || promptJob?.isActive == true) {
            // Queued prompts only carry the attachment count — queueing a
            // send with images would silently drop the bytes, so refuse.
            if (attachments.isNotEmpty()) {
                append(Kind.Error, "运行中发送会丢失图片，请等本轮结束或移除图片")
                return false
            }
            _queuedPrompts.update { it + QueuedPrompt(trimmed, attachments.size) }
            return true
        }
        launchPrompt(sid, trimmed, attachments)
        return true
    }

    /** Interrupt the running turn with a new instruction right now. */
    fun steer(text: String) {
        val sid = _ui.value.sessionId ?: return
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return
        append(Kind.User, trimmed)
        promptAcked = true // the turn is live; a steer failure isn't a lost echo
        steerJob = viewModelScope.launch {
            try {
                reportStopReason(client.promptMidTurn(sid, trimmed).stopReason)
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

    private fun drainQueue() {
        val next = _queuedPrompts.value.firstOrNull() ?: return
        _queuedPrompts.update { it.drop(1) }
        val sid = _ui.value.sessionId ?: return
        launchPrompt(sid, next.text, emptyList())
    }

    private fun launchPrompt(
        sid: String,
        text: String,
        attachments: List<ImageAttachment>,
    ) {
        val blocks = buildBlocks(text, attachments)
        val label = buildString {
            append(text)
            if (attachments.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("[${attachments.size} 张图片]")
            }
        }
        val userItem = append(Kind.User, label)
        promptAcked = false
        promptJob = viewModelScope.launch {
            _ui.update { it.copy(running = true) }
            try {
                val result = client.promptBlocks(sid, blocks)
                onSessionReady?.invoke(sid, activeCwd)
                reportStopReason(result.stopReason)
                if (_messages.value.isNotEmpty()) {
                    notifier.notifyTurnDone(
                        _ui.value.sessionTitle?.takeIf { it.isNotBlank() }
                            ?: _ui.value.agentTitle ?: "DevinGo",
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
                _ui.update { it.copy(running = false) }
                drainQueue()
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
        val base = activeCwd.replace('\\', '/').trimEnd('/')
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
        val sid = _ui.value.sessionId ?: return
        runCatching { client.cancel(sid) }
        // Watchdog: if the agent never answers session/cancel, the prompt
        // request would hang and `running` would stay true forever.
        val job = promptJob ?: return
        viewModelScope.launch {
            delay(CANCEL_WATCHDOG_MS)
            if (promptJob === job && job.isActive) {
                job.cancel()
                append(Kind.System, "本轮已强制结束")
            }
        }
    }

    fun setMode(modeId: String) {
        val sid = _ui.value.sessionId ?: return
        _ui.update { it.copy(currentModeId = modeId) }
        viewModelScope.launch {
            try {
                client.setMode(sid, modeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换模式失败: ${e.display()}")
            }
        }
    }

    /**
     * ACP `session/new` may not return a model list at all; fall back to the
     * bridge's `devin models list` so the picker still shows options.
     */
    private fun loadBridgeModels() {
        viewModelScope.launch {
            val models = runCatching {
                fetchBridgeModels(bridgeHttpUrl, bridgeToken)
            }.getOrNull().orEmpty()
            if (models.isNotEmpty()) {
                _ui.update {
                    if (it.models.isEmpty()) it.copy(models = models) else it
                }
            }
        }
    }

    /** Generic config setter for extraConfigOptions (effort and friends). */
    fun setConfigOption(configId: String, value: String) {
        val sid = _ui.value.sessionId ?: return
        _ui.update { st ->
            st.copy(
                extraConfigOptions = st.extraConfigOptions.map {
                    if (it.id == configId) it.copy(currentValue = value) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                client.setConfigOption(sid, configId, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换设置失败: ${e.display()}")
            }
        }
    }

    fun setModel(modelId: String) {
        val sid = _ui.value.sessionId ?: return
        _ui.update { it.copy(currentModelId = modelId) }
        viewModelScope.launch {
            try {
                client.setModel(sid, modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                append(Kind.Error, "切换模型失败: ${e.display()}")
            }
        }
    }

    // ---- agent -> client requests --------------------------------------------

    private suspend fun onAgentRequest(req: IncomingRequest) {
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
        val choice = CompletableDeferred<String?>()
        val pending = PendingPermission(params.toolCall, params.options, choice)
        pushPermission(pending)
        notifier.notifyPermissionPending(
            params.toolCall.title ?: params.toolCall.kind ?: "工具调用",
        )
        // Concurrent requests queue up behind the visible one; each still gets
        // its own 5-minute clock measured from arrival.
        val optionId = try {
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { choice.await() }
        } finally {
            popPermission(pending)
        }
        req.respond(
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

    private fun pushPermission(p: PendingPermission) = synchronized(permissionLock) {
        permissionQueue.addLast(p)
        _pendingPermission.value = permissionQueue.firstOrNull()
    }

    private fun popPermission(p: PendingPermission) = synchronized(permissionLock) {
        permissionQueue.remove(p)
        _pendingPermission.value = permissionQueue.firstOrNull()
    }

    private fun clearPermissions() = synchronized(permissionLock) {
        permissionQueue.forEach { it.choice.complete(null) }
        permissionQueue.clear()
        _pendingPermission.value = null
    }

    /** Called by the permission dialog; null = user dismissed -> "cancelled". */
    fun resolvePermission(optionId: String?) {
        _pendingPermission.value?.choice?.complete(optionId)
    }

    // ---- event -> message mapping --------------------------------------------

    private fun onEvent(ev: AcpEvent) {
        when (ev) {
            is AcpEvent.AgentChunk -> {
                promptAcked = true
                appendStreaming(Kind.Agent, ev.text)
            }
            is AcpEvent.ThoughtChunk -> {
                promptAcked = true
                appendStreaming(Kind.Thought, ev.text)
            }
            is AcpEvent.UserChunk -> {
                promptAcked = true
                flushStreaming()
                // session/load replays the user's own messages; skip the chunk
                // if it merely echoes the prompt we just appended locally.
                val lastUser = _messages.value.lastOrNull { it.kind == Kind.User }
                if (lastUser == null || lastUser.text != ev.text) {
                    appendStreaming(Kind.User, ev.text)
                }
            }
            else -> {
                // Chunks are buffered — anything else must not overtake them,
                // so the pending text is committed before handling the event.
                flushStreaming()
                onNonChunkEvent(ev)
            }
        }
    }

    private fun onNonChunkEvent(ev: AcpEvent) {
        when (ev) {
            is AcpEvent.ToolCall -> {
                promptAcked = true
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
                updateTool(ev.call)
            }
            is AcpEvent.PlanUpdate -> {
                promptAcked = true
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
            is AcpEvent.Disconnected -> {
                // Sticky, not consume-once: teardown's own "已断开" event and
                // any late peer-close on the dying socket must never schedule
                // a phantom reconnect. connect() re-arms the flag once the
                // teardown has been joined and a new socket is imminent.
                if (intentionalDisconnect) return
                // A dead socket strands any queued permission prompts.
                clearPermissions()
                _ui.update { it.copy(running = false) }
                scheduleReconnect(ev.reason)
            }
            else -> Unit // forward-compat with updates added alongside acp/ changes
        }
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

    // ---- transcript assembly ---------------------------------------------------

    private fun appendStreaming(kind: Kind, text: String) {
        if (streamKind != null && streamKind != kind) flushStreaming()
        streamKind = kind
        streamBuf.append(text)
        if (streamBuf.length >= FLUSH_CHARS) {
            flushStreaming()
        } else {
            scheduleStreamFlush()
        }
    }

    private fun scheduleStreamFlush() {
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
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
                capMessages(list + item)
            }
        }
    }

    /**
     * Drop the transcript buffer and every item — used before `session/load`
     * replays history and on manual disconnect.
     */
    private fun resetTranscript() {
        flushJob?.cancel()
        flushJob = null
        streamBuf.setLength(0)
        streamKind = null
        streamingId = null
        planItemId = null
        _messages.value = emptyList()
    }

    /** Cap the transcript at [MAX_MESSAGES], keeping the first [KEEP_HEAD] items. */
    private fun capMessages(list: List<ChatItem>): List<ChatItem> {
        if (list.size <= MAX_MESSAGES) return list
        val head = list.take(KEEP_HEAD)
        return head + list.drop(KEEP_HEAD).drop(list.size - MAX_MESSAGES)
    }

    private fun append(
        kind: Kind,
        text: String,
        status: String? = null,
        toolCallId: String? = null,
        detail: String? = null,
    ): ChatItem {
        flushStreaming()
        val item = ChatItem(nextItemId++, kind, text, status, toolCallId, detail = detail)
        streamingId = null
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
        (call.content as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
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
        (call.locations as? kotlinx.serialization.json.JsonArray)?.forEach { loc ->
            (loc as? kotlinx.serialization.json.JsonObject)
                ?.get("path").strOrNull()
                ?.let { parts.add("📄 $it") }
        }
        (call.content as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@forEach
            when (o["type"].strOrNull()) {
                "content" -> (o["content"] as? kotlinx.serialization.json.JsonObject)
                    ?.get("text").strOrNull()?.let { parts.add(it) }
                "diff" -> o["path"].strOrNull()?.let { parts.add("diff: $it") }
                "terminal" -> (o["output"] ?: o["terminal"]).strOrNull()
                    ?.let { parts.add(it.take(1500)) }
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }?.take(4000)
    }

    private fun kotlinx.serialization.json.JsonElement?.strOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)
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

    // ---- misc ----------------------------------------------------------------

    private fun wsUrl(host: String): String = when {
        host.startsWith("ws://") || host.startsWith("wss://") ->
            if (host.endsWith("/acp")) host else "$host/acp"
        else -> "ws://$host/acp"
    }

    private fun healthUrl(host: String): String {
        val h = host.removePrefix("ws://").removePrefix("wss://").removeSuffix("/acp").trimEnd('/')
        val scheme = if (host.startsWith("wss://")) "https" else "http"
        return "$scheme://$h"
    }

    private fun Throwable.display(): String = when (this) {
        is AcpException -> message ?: "ACP 错误 $code"
        is java.net.SocketTimeoutException -> "连接超时"
        else -> message ?: javaClass.simpleName
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        flushJob?.cancel()
        client.shutdown()
    }
}
