package com.devingo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * View/binder for the currently selected session. The WebSocket, reconnect
 * loop, per-session transcripts and the shared session list all live in
 * [ConnectionManager] (application scope) — this class only flattens the
 * bound [SessionStore]'s flows into the shape the Compose UI already reads
 * and forwards user actions back.
 *
 * Session stores outlive this VM across configuration changes AND session
 * switches: switching re-points [ConnectionManager.currentStore], nothing
 * disconnects, and the other sessions keep streaming off-screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val cm = ConnectionManager.get(app)
    private val notifier = Notifier(app)

    private fun <T> bound(produce: SessionStore.() -> StateFlow<T>, initial: T): StateFlow<T> =
        cm.currentStore
            .flatMapLatest { it?.produce() ?: flowOf(initial) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /** Connection+session merge — the single shape the UI renders. */
    val ui: StateFlow<UiState> = combine(
        cm.conn,
        cm.currentStore.flatMapLatest { it?.ui ?: flowOf(SessionUi()) },
        cm.bridgeModels,
    ) { conn, sui, bridgeModels ->
        UiState(
            phase = conn.phase,
            statusText = conn.statusText,
            error = conn.error,
            agentTitle = conn.agentTitle,
            awaitingBrowserLogin = conn.awaitingBrowserLogin,
            reconnecting = conn.reconnecting,
            connected = conn.connected,
            authMethods = conn.authMethods,
            sessionId = sui.sessionId,
            modes = sui.modes,
            currentModeId = sui.currentModeId,
            models = sui.models.ifEmpty { bridgeModels },
            currentModelId = sui.currentModelId,
            commands = sui.commands,
            sessionTitle = sui.sessionTitle,
            contextUsed = sui.contextUsed,
            contextSize = sui.contextSize,
            usageInput = sui.usageInput,
            usageOutput = sui.usageOutput,
            usageCacheRead = sui.usageCacheRead,
            running = sui.running,
            extraConfigOptions = sui.extraConfigOptions,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    // ---- bound-session flows -------------------------------------------------

    val messages: StateFlow<List<ChatItem>> = bound({ messages }, emptyList())
    val pendingPermission: StateFlow<PendingPermission?> = bound({ pendingPermission }, null)
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = bound({ queuedPrompts }, emptyList())
    val revertSteps: StateFlow<List<com.devingo.app.acp.RevertStep>?> = bound({ revertSteps }, null)
    val turnTimes: StateFlow<Map<Long, TurnTiming>> = bound({ turnTimes }, emptyMap())
    val draft: StateFlow<String> = bound({ draft }, "")
    val draftAttachments: StateFlow<List<ImageAttachment>> = bound({ draftAttachments }, emptyList())

    // ---- shared (connection-level) flows -------------------------------------

    val sessions: StateFlow<List<com.devingo.app.acp.AcpSession>?> = cm.sessions
    val sessionsLoading: StateFlow<Boolean> = cm.sessionsLoading
    val sessionsError: StateFlow<String?> = cm.sessionsError
    val badges: StateFlow<Map<String, SessionBadge>> = cm.badges
    val openTarget: StateFlow<OpenTarget?> = cm.openTarget
    val alerts = cm.alerts

    /** Persist-last-session hook — MainActivity wires it to Prefs. */
    var onSessionReady: ((sessionId: String, cwd: String) -> Unit)? = null
        set(v) {
            field = v
            cm.onSessionReady = v
        }

    /** Android 13+ runtime request for POST_NOTIFICATIONS. */
    fun requestNotificationPermission(activity: android.app.Activity) =
        notifier.ensurePermission(activity)

    // ---- connection / session lifecycle ----------------------------------------

    /**
     * Open a session over the shared socket: ensures the connection (health
     * check + handshake + `_devingo/hello` on first use), then attaches or
     * creates the session — all over one connection.
     */
    fun connect(
        host: String,
        token: String,
        cwd: String,
        resumeSessionId: String? = null,
        apiKey: String? = null,
    ) = cm.connect(host, token, cwd, resumeSessionId, apiKey)

    /** Drawer/Home tap on a listed session — instant switch, no reconnect. */
    fun openSession(sessionId: String, cwd: String? = null, host: String = "", token: String = "") =
        cm.openSession(sessionId, cwd, host, token)

    /** "+" / per-project new session on the shared connection. */
    fun newSession(cwd: String? = null, host: String = "", token: String = "") =
        cm.newSession(cwd, host, token)

    /** Leave chat for Home — the session's store keeps running off-screen. */
    fun leaveSession() = cm.leaveSession()

    /** Full socket teardown + drop all stores (chat menu's 清除会话状态). */
    fun disconnect() = cm.disconnect()

    fun authenticate() = cm.authenticate()

    /** (resumeId, cwd) of the failed open attempt, for the retry snackbar. */
    fun retryTarget(): Pair<String?, String> = cm.retryTarget()

    // ---- session list ------------------------------------------------------------

    /**
     * Refresh the `_devingo/sessions` cache — cancellation-aware (a newer
     * call supersedes the in-flight one). When no socket exists yet this
     * connects first (idempotent on a live socket), so Home's boot path,
     * the drawer-open refresh and the error-retry all share this entry.
     */
    fun refreshSessions(host: String, token: String) =
        cm.connectAndRefresh(host, token)

    /** Swipe/contextual delete — shared socket, cache updated on success. */
    suspend fun deleteSession(sessionId: String): Boolean = try {
        cm.deleteSession(sessionId)
        true
    } catch (e: Exception) {
        false
    }

    // ---- bound-session user actions ----------------------------------------------

    private val store: SessionStore? get() = cm.currentStore.value

    /** The bound session's working directory (fixed at store creation). */
    val boundCwd: String get() = store?.cwd.orEmpty()

    fun send(text: String, attachments: List<ImageAttachment> = emptyList()): Boolean =
        store?.send(text, attachments) ?: false

    fun steer(text: String) {
        store?.steer(text)
    }

    fun cancelQueued(index: Int) {
        store?.cancelQueued(index)
    }

    fun resend(item: ChatItem) {
        store?.resend(item)
    }

    fun cancelTurn() {
        store?.cancelTurn()
    }

    fun setMode(modeId: String) {
        store?.setMode(modeId)
    }

    fun setModel(modelId: String) {
        store?.setModel(modelId)
    }

    fun setConfigOption(configId: String, value: String) {
        store?.setConfigOption(configId, value)
    }

    /** Permission dialog answer; null = dismissed -> "cancelled". */
    fun resolvePermission(optionId: String?) {
        store?.resolvePermission(optionId)
    }

    /**
     * The notification's 允许/拒绝 names the session it was posted for — the
     * answer must land there even when that session isn't on screen.
     */
    fun pendingPermissionOf(sessionId: String): PendingPermission? =
        cm.storeFor(sessionId)?.pendingPermission?.value

    fun resolvePermissionFor(sessionId: String, optionId: String?) {
        cm.storeFor(sessionId)?.resolvePermission(optionId)
    }

    fun loadRevertSteps() {
        store?.loadRevertSteps()
    }

    fun dismissRevertSteps() {
        store?.dismissRevertSteps()
    }

    /** Fork off [stepId] on the bound session, then open the new session. */
    fun forkFromStep(stepId: String) {
        val s = store ?: return
        cm.scope.launch {
            s.forkFromStep(stepId)?.let { cm.openSession(it, s.cwd) }
        }
    }

    // ---- drafts (persisted per session) -------------------------------------------

    fun setDraft(text: String) {
        store?.setDraft(text)
    }

    fun setDraftAttachments(attachments: List<ImageAttachment>) {
        store?.setDraftAttachments(attachments)
    }
}
