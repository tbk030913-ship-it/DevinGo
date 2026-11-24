package com.devingo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.devingo.app.acp.AgentCaps
import com.devingo.app.acp.FsSearchItem
import com.devingo.app.acp.GitDiffResult
import com.devingo.app.acp.MentionResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
class ChatViewModel(
    app: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(app) {

    private val cm = ConnectionManager.get(app)
    private val notifier = Notifier(app)

    private companion object {
        const val KEY_DRAFT_SID = "draft_session_id"
        const val KEY_DRAFT_TEXT = "draft_text"
    }

    init {
        // C6: the bound session's draft also rides the SavedStateHandle —
        // the activity Bundle persists it on process death independently
        // of the debounced DataStore write. It seeds back only when the
        // store itself came up empty (its own restore hadn't landed).
        viewModelScope.launch {
            cm.currentStore.collectLatest { store ->
                if (store == null) return@collectLatest
                val savedText = savedState.get<String>(KEY_DRAFT_TEXT).orEmpty()
                if (savedState.get<String>(KEY_DRAFT_SID) == store.sessionId &&
                    savedText.isNotEmpty() && store.draft.value.isEmpty()
                ) {
                    store.setDraft(savedText)
                }
                store.draft.collect { text ->
                    savedState[KEY_DRAFT_SID] = store.sessionId
                    savedState[KEY_DRAFT_TEXT] = text
                }
            }
        }
    }

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
            reconnectRetryAtMs = conn.reconnectRetryAtMs,
            connected = conn.connected,
            sessionId = sui.sessionId,
            attached = sui.attached,
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
            revertSupported = conn.revertSupported,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    // ---- bound-session flows -------------------------------------------------

    /** Row model built off the main thread (U2) — replaces raw `messages`. */
    val transcript: StateFlow<TranscriptModel> = bound({ transcript }, TranscriptModel.EMPTY)
    val streamingTail: StateFlow<ChatItem?> = bound({ streamingTail }, null)
    val pendingPermission: StateFlow<PendingPermission?> = bound({ pendingPermission }, null)
    /** The elicitation card awaiting answers in the bound session (A7.2). */
    val pendingElicitation: StateFlow<PendingElicitation?> =
        bound({ pendingElicitation }, null)
    val queuedPrompts: StateFlow<List<QueuedPrompt>> = bound({ queuedPrompts }, emptyList())
    /** The queued prompt pulled into the composer for editing (U5.4). */
    val editingQueuedId: StateFlow<Long?> = bound({ editingQueuedId }, null)
    val revertSteps: StateFlow<List<com.devingo.app.acp.RevertStep>?> = bound({ revertSteps }, null)
    val turnTimes: StateFlow<Map<Long, TurnTiming>> = bound({ turnTimes }, emptyMap())
    val draft: StateFlow<String> = bound({ draft }, "")
    val draftAttachments: StateFlow<List<ImageAttachment>> = bound({ draftAttachments }, emptyList())
    /** The quote pinned to the composer (U5.5 citation workflow). */
    val draftCitation: StateFlow<CitationAttachment?> = bound({ draftCitation }, null)
    /** The bound session's spawned subagents, live (A7.4). */
    val subagents: StateFlow<List<Subagent>> = bound({ subagents }, emptyList())
    /**
     * Wire-reported `cognition.ai/numLiveSubagents` (-1 = never sent). The
     * capsule prefers it over the count derived from [subagents].
     */
    val numLiveSubagents: StateFlow<Int> = bound({ numLiveSubagents }, -1)

    // ---- shared (connection-level) flows -------------------------------------

    val sessions: StateFlow<List<com.devingo.app.acp.AcpSession>?> = cm.sessions
    val sessionsLoading: StateFlow<Boolean> = cm.sessionsLoading
    val sessionsError: StateFlow<String?> = cm.sessionsError
    val badges: StateFlow<Map<String, SessionBadge>> = cm.badges
    /** The negotiated capability set — the @-picker's kind gating (A7.3). */
    val agentCaps: StateFlow<AgentCaps> = cm.agentCaps
    /** Pending approvals across all sessions — the notification's count. */
    val pendingApprovals: StateFlow<Int> = cm.pendingApprovals
    val openTarget: StateFlow<OpenTarget?> = cm.openTarget
    val alerts = cm.alerts

    /** Home | Chat(sessionId) — decoupled from connection phase (U6). */
    val page: StateFlow<Page> = cm.page

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

    /** Snapshot all live stores to the transcript cache (app backgrounding). */
    fun persistCaches() = cm.persistCaches()

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

    /** Bridge http base + token for /pty + /preview (same address /fs uses). */
    fun bridgeEndpoint(): Pair<String, String?>? = cm.bridgeEndpoint()

    fun send(): Boolean {
        val s = store ?: return false
        // The store's draft is the truth — a keystroke in the same frame
        // as the tap hasn't reached the collected UI state yet.
        return s.send(s.draft.value, s.draftAttachments.value)
    }

    fun steer() {
        val s = store ?: return
        s.steer(s.draft.value)
    }

    fun cancelQueued(id: Long) {
        store?.cancelQueued(id)
    }

    /** Queue sheet's 立即转向 — de-queue the item and interrupt the turn. */
    fun steerQueued(id: Long) {
        store?.steerQueued(id)
    }

    /** Pull a queued prompt's text + images into the composer for editing. */
    fun beginQueuedEdit(id: Long) {
        store?.beginQueuedEdit(id)
    }

    fun cancelQueuedEdit() {
        store?.cancelQueuedEdit()
    }

    fun saveQueuedEdit() {
        store?.saveQueuedEdit()
    }

    /** The floating capsule's retry tap — cut the reconnect backoff short. */
    fun reconnectNow() = cm.kickReconnect()

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

    /** Question card submit/dismiss; null = declined without answers. */
    fun resolveElicitation(p: PendingElicitation, reply: ElicitationReply?) {
        store?.resolveElicitation(p, reply)
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

    // ---- subagents (A7.4) ------------------------------------------------------

    /** `_cognition.ai/subagent/cancel` on the bound session. */
    fun subagentCancel(id: String) {
        store?.subagentCancel(id)
    }

    /** `_cognition.ai/subagent/background` on the bound session. */
    fun subagentBackground(id: String) {
        store?.subagentBackground(id)
    }

    /** `_cognition.ai/subagent/foreground` on the bound session. */
    fun subagentForeground(id: String) {
        store?.subagentForeground(id)
    }

    /** Open a subagent's child session in place (detail sheet's 打开会话). */
    fun openChildSession(sessionId: String) {
        cm.openSession(sessionId, null)
    }

    // ---- drafts (persisted per session) -------------------------------------------

    fun setDraft(text: String) {
        store?.setDraft(text)
    }

    fun setDraftAttachments(attachments: List<ImageAttachment>) {
        store?.setDraftAttachments(attachments)
    }

    /** Pin/replace/clear the composer's quote (null clears). */
    fun setDraftCitation(citation: CitationAttachment?) {
        store?.setDraftCitation(citation)
    }

    /** Add a review comment — pins the citation slot, then appends to text. */
    fun addReviewCitation(citation: CitationAttachment) {
        store?.addReviewCitation(citation)
    }

    /** Write the plan card's markdown as plan.md in the session workspace. */
    fun savePlanToWorkspace(markdown: String) {
        store?.savePlanToWorkspace(markdown)
    }

    /** The workspace's uncommitted `git diff` patch via the bridge. */
    suspend fun fetchGitDiff(): GitDiffResult =
        store?.fetchGitDiff() ?: throw IllegalStateException("no bound session")

    /** The workspace's file paths for the composer's @file picker (N6.2). */
    suspend fun fetchWorkspaceFiles(): List<String> =
        store?.fetchWorkspaceFiles() ?: emptyList()

    /** A picker-inserted `@{kind}:{ref}` — verbatim ref for the send scan. */
    fun registerMentionRef(ref: String) {
        store?.registerMentionRef(ref)
    }

    /** The bridge's fuzzy file+dir search — the @-picker's typed feed (A7.3). */
    suspend fun searchWorkspace(query: String): List<FsSearchItem> =
        store?.searchWorkspace(query) ?: emptyList()

    /**
     * Rules/skills/plans the agent lists for @-mentions. Failures (agent
     * without the `_cognition.ai` list surface, or a dead socket) fold
     * to an empty category rather than an error — it's a picker (A7.3).
     */
    suspend fun mentionResources(kind: String): List<MentionResource> = try {
        store?.mentionResources(kind) ?: emptyList()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}
