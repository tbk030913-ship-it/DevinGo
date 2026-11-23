package com.devingo.app

import android.app.Application
import android.util.Log
import com.devingo.app.acp.ACP_ERROR_AUTH_REQUIRED
import com.devingo.app.acp.AcpClient
import com.devingo.app.acp.AcpEvent
import com.devingo.app.acp.AcpException
import com.devingo.app.acp.AcpMethods
import com.devingo.app.acp.AcpSession
import com.devingo.app.acp.InboundEvent
import com.devingo.app.acp.IncomingRequest
import com.devingo.app.acp.JSONRPC_METHOD_NOT_FOUND
import com.devingo.app.acp.SessionModel
import com.devingo.app.acp.fetchBridgeHealth
import com.devingo.app.acp.fetchBridgeModels
import com.devingo.app.tailnet.TailnetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Application-level owner of the single ACP WebSocket. One instance per
 * process (held by [SessionService]); the chat ViewModel and the session
 * list only ever talk to the store/demux surface it exposes — never to the
 * socket itself.
 *
 * Responsibilities:
 *  - connect/initialize/`_devingo/hello`, auto-reconnect with backoff;
 *  - demultiplex inbound frames to the right [SessionStore] by sessionId;
 *  - host one store per visited session (transcript survives switching);
 *  - the shared `_devingo/sessions` cache + per-session badges;
 *  - cross-session alerts (permission while you're looking elsewhere).
 */
class ConnectionManager private constructor(private val app: Application) {

    /** Connection-level half of the UI state (merged into [UiState] by the VM). */
    data class ConnState(
        val phase: Phase = Phase.Idle,
        val statusText: String = "",
        val error: String? = null,
        val agentTitle: String? = null,
        val authMethods: List<String> = emptyList(),
        val awaitingBrowserLogin: Boolean = false,
        val reconnecting: Boolean = false,
        /**
         * The shared socket is established + handshaken — independent of
         * whether a session is bound (background sessions still stream).
         */
        val connected: Boolean = false,
    )

    companion object {
        private const val RECONNECT_MAX_ATTEMPTS = 3
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        /** `session/close` gets this long to answer before teardown proceeds. */
        private const val CLOSE_TIMEOUT_MS = 2_000L
        /** Transcript cache writes ride a debounce — one save per quiet stretch. */
        private const val CACHE_SAVE_DEBOUNCE_MS = 5_000L
        private const val TAG = "ConnectionManager"
        /** Identifier this client reports in `_devingo/hello`. */
        private const val clientId = "devingo-android"

        @Volatile private var instance: ConnectionManager? = null

        /** Process-wide singleton — service/activity restarts reuse it. */
        fun get(app: Application): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(app.applicationContext as Application)
                    .also { instance = it }
            }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val client = AcpClient(json)
    private val notifier = Notifier(app)
    private val cache = TranscriptCache(app, json)

    /** All session/connection work serializes here (the demux included). */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- exposed state ----------------------------------------------------

    private val _conn = MutableStateFlow(ConnState())
    val conn = _conn.asStateFlow()

    /** `_devingo/sessions` cache — null until the first successful load. */
    private val _sessions = MutableStateFlow<List<AcpSession>?>(null)
    val sessions = _sessions.asStateFlow()
    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading = _sessionsLoading.asStateFlow()
    private val _sessionsError = MutableStateFlow<String?>(null)
    val sessionsError = _sessionsError.asStateFlow()

    private val _badges = MutableStateFlow<Map<String, SessionBadge>>(emptyMap())
    val badges = _badges.asStateFlow()

    /** Heads-up events for sessions other than the one on screen. */
    private val _alerts = MutableSharedFlow<SessionAlert>(extraBufferCapacity = 8)
    val alerts = _alerts.asSharedFlow()

    /** The store currently bound to the chat UI; null = Home. */
    private val _currentStore = MutableStateFlow<SessionStore?>(null)
    val currentStore = _currentStore.asStateFlow()

    /**
     * Which top-level screen is up — U6. Decoupled from [ConnState.phase]:
     * the socket dropping while a session is open changes the chat page's
     * connection banner, never the page.
     */
    val page = _currentStore
        .map { it?.let { s -> Page.Chat(s.sessionId) } ?: Page.Home }
        .stateIn(scope, SharingStarted.Eagerly, Page.Home)

    /** `/models` fallback list when the session reports no model picker. */
    private val _bridgeModels = MutableStateFlow<List<SessionModel>>(emptyList())
    val bridgeModels = _bridgeModels.asStateFlow()

    /** Persist-last-session hook wired by the VM to Prefs. */
    var onSessionReady: ((sessionId: String, cwd: String) -> Unit)? = null

    /** The open request in flight — drives the drawer row's switch spinner. */
    private val _openTarget = MutableStateFlow<OpenTarget?>(null)
    val openTarget = _openTarget.asStateFlow()

    // ---- internals --------------------------------------------------------

    // stores/seqStash are read on OkHttp's dispatch thread (responses carry
    // _seq via seqTracker) while the Main-scoped demux mutates them — they
    // must be concurrent maps.
    private val stores = java.util.concurrent.ConcurrentHashMap<String, SessionStore>()
    private val storeWatchJobs = mutableMapOf<String, Job>()
    private val reattachJobs = mutableMapOf<String, Job>()
    /** `_seq`s seen before their store existed (session/new result frames). */
    private val seqStash = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var openJob: Job? = null
    private var reconnectJob: Job? = null
    private var listJob: Job? = null
    /** Serializes ensureSocket so a list refresh can't race a session open. */
    private val socketMutex = Mutex()
    @Volatile private var socketLive = false
    @Volatile private var intentionalDisconnect = false

    private var lastHost = ""
    private var lastToken: String? = null
    private var lastApiKey: String? = null
    private var pendingOpen: OpenTarget? = null
    private var pendingCwd = ""
    private var defaultCwd = ""
    private var canAuthenticate = false

    init {
        client.requestHandler = { req -> routeRequest(req) }
        client.seqTracker = { sid, seq ->
            val store = stores[sid]
            if (store != null) store.seedSeq(seq) else seqStash[sid] = seq
        }
        scope.launch { client.events.collect { route(it) } }
    }

    // ---- connection -------------------------------------------------------

    /** Point the manager at a different bridge: old epoch's stores die with it. */
    private fun noteHost(host: String, token: String) {
        if (host.isNotBlank() && host != lastHost) {
            // The socket may already be dead (socketLive false) while stores
            // still wait on a reconnect — the epoch must drop either way or
            // the old bridge's sessions would leak into the new host.
            client.disconnect()
            socketLive = false
            dropEpoch()
        }
        lastHost = host
        lastToken = token.ifBlank { null }
    }

    /** Cancel all work + drop every store; the caller closes the socket. */
    private fun dropEpoch() {
        intentionalDisconnect = true
        openJob?.cancel()
        reconnectJob?.cancel()
        listJob?.cancel()
        stores.keys.toList().forEach { dropStore(it) }
    }

    /**
     * Establish the socket + ACP handshake when they aren't already up.
     * Idempotent on a live socket; the [socketMutex] keeps a concurrent list
     * refresh from superseding an in-flight session-open handshake. Returns
     * false on failure — [ConnState.error] already carries the reason.
     */
    private suspend fun ensureSocket(): Boolean = socketMutex.withLock {
        if (socketLive) return true
        if (lastHost.isBlank()) {
            // Cold-start entries (e.g. a notification tap as the app's first
            // action) never passed host/token — fall back to persisted prefs
            // through the same tailnet resolution the UI call sites use.
            val prefs = Prefs(app)
            val raw = prefs.host.trim()
            if (raw.isNotBlank()) {
                val resolved = runCatching { TailnetManager.resolveHost(raw) }
                    .getOrDefault(raw)
                noteHost(resolved, prefs.token.trim())
                lastApiKey = prefs.apiKey.trim().ifBlank { null }
            }
        }
        val host = lastHost
        val token = lastToken
        if (host.isBlank()) {
            _conn.update { it.copy(phase = Phase.Error, error = "未配置 bridge") }
            return false
        }
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            _conn.update {
                it.copy(
                    phase = Phase.Checking,
                    statusText = "检查 bridge…",
                    error = null,
                    reconnecting = false,
                )
            }
            val health = runCatching { fetchBridgeHealth(healthUrl(host), token) }
                .getOrNull()
            if (health != null && !health.devinFound) {
                _conn.update {
                    it.copy(phase = Phase.Error, error = "bridge 上找不到 devin CLI（${health.devin}）")
                }
                return false
            }
            defaultCwd = health?.defaultCwd.orEmpty()
            _conn.update { it.copy(phase = Phase.Connecting, statusText = "建立 WebSocket…") }
            intentionalDisconnect = false
            client.connect(wsUrl(host), token)
            _conn.update { it.copy(statusText = "ACP 握手…") }
            val init = client.initialize()
            // Introduce ourselves to the gateway (attach/journal/…); the
            // app ships against _devingo/* but a stale bridge must not wedge
            // the connection over the hello alone.
            runCatching { client.hello(clientId) }
                .onFailure { e -> Log.w(TAG, "_devingo/hello: ${e.message}") }
            // socketLive flips only once the handshake is done — a cancel
            // mid-handshake must not leave a believed-live half-init socket.
            socketLive = true
            canAuthenticate = init.authMethods.isNotEmpty()
            _conn.update {
                it.copy(
                    phase = Phase.Ready,
                    statusText = "",
                    agentTitle = init.agentInfo?.title ?: init.agentInfo?.name,
                    authMethods = init.authMethods.map { m -> m.id },
                    connected = true,
                )
            }
            // Stores that outlived a dead socket (reconnect exhausted, or a
            // manual retry beat the loop) re-subscribe at their cursors.
            if (stores.isNotEmpty()) reattachStores()
            if (_bridgeModels.value.isEmpty()) loadBridgeModels()
            refreshSessions()
            return true
        } catch (e: CancellationException) {
            client.disconnect()
            socketLive = false
            throw e
        } catch (e: Exception) {
            // The socket may be live even though the handshake failed —
            // drop it or the next ensureSocket would skip straight past
            // initialize onto a half-broken connection.
            client.disconnect()
            socketLive = false
            _conn.update {
                it.copy(
                    phase = Phase.Error,
                    error = e.display(),
                    reconnecting = false,
                    connected = false,
                )
            }
            return false
        }
    }

    // ---- open / switch ------------------------------------------------------

    /**
     * The one entry point for "be in this session": ensures the socket, then
     * binds [resumeId] (or creates a session when null) — the same connection
     * serves Home, open, create, switch and delete.
     */
    fun connect(
        host: String,
        token: String,
        cwd: String,
        resumeSessionId: String? = null,
        apiKey: String? = null,
    ) {
        noteHost(host, token)
        lastApiKey = apiKey?.trim()?.ifBlank { null }
        pendingOpen = OpenTarget(resumeSessionId, cwd, resumeSessionId)
        pendingCwd = cwd
        openJob?.cancel()
        openJob = scope.launch {
            try {
                if (!ensureSocket()) return@launch
                openOrCreate(resumeSessionId, cwd.ifBlank { defaultCwd })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _conn.update {
                    it.copy(phase = Phase.Error, statusText = "", error = e.display())
                }
            }
        }
    }

    /**
     * Switch to an already-listed session (drawer/Home tap). Same socket.
     * [host]/[token] are the *current* prefs values — passing them lets a
     * settings-changed host drop the old epoch before the open.
     */
    fun openSession(
        sessionId: String,
        cwd: String? = null,
        host: String = "",
        token: String = "",
    ) {
        if (host.isNotBlank()) noteHost(host, token)
        if (_currentStore.value?.sessionId == sessionId) {
            _currentStore.value?.unread?.value = false
            return
        }
        val knownCwd = stores[sessionId]?.cwd
            ?: _sessions.value?.firstOrNull { it.sessionId == sessionId }?.cwd
        pendingOpen = OpenTarget(sessionId, cwd ?: knownCwd, sessionId)
        pendingCwd = cwd ?: knownCwd.orEmpty()
        openJob?.cancel()
        openJob = scope.launch {
            try {
                if (!ensureSocket()) return@launch
                openOrCreate(sessionId, (cwd ?: knownCwd).orEmpty().ifBlank { defaultCwd })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _conn.update {
                    it.copy(phase = Phase.Error, statusText = "", error = e.display())
                }
            }
        }
    }

    /** Fresh session rooted at [cwd] (or the bridge default). */
    fun newSession(cwd: String? = null, host: String = "", token: String = "") {
        if (host.isNotBlank()) noteHost(host, token)
        pendingOpen = OpenTarget(null, cwd, null)
        pendingCwd = cwd.orEmpty()
        openJob?.cancel()
        openJob = scope.launch {
            try {
                if (!ensureSocket()) return@launch
                openOrCreate(null, cwd.orEmpty().ifBlank { defaultCwd })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _conn.update {
                    it.copy(phase = Phase.Error, statusText = "", error = e.display())
                }
            }
        }
    }

    private suspend fun openOrCreate(resumeId: String?, cwd: String) {
        _openTarget.value = OpenTarget(resumeId, cwd, resumeId)
        _conn.update { it.copy(phase = Phase.StartingSession, statusText = "打开会话…") }
        try {
            if (resumeId == null) {
                val s = client.newSession(cwd)
                val store = createStore(s.sessionId, cwd)
                store.markSubscribed()
                store.applySessionConfig(s)
                store.append(Kind.System, "会话已建立 · $cwd")
                bindStore(store)
                onSessionReady?.invoke(s.sessionId, cwd)
                refreshSessions()
            } else {
                val existing = stores[resumeId]
                if (existing != null) {
                    if (existing.attached) {
                        // Store stayed subscribed — transcript already live.
                        bindStore(existing)
                        onSessionReady?.invoke(resumeId, existing.cwd)
                        return
                    }
                    // Outlived a socket drop but never re-attached (the
                    // reconnect loop may have given up first): re-attach at
                    // the store's cursor instead of re-binding a deaf store.
                    bindStore(existing)
                    try {
                        attachStore(existing, announce = false)
                        existing.append(Kind.System, "已重连并恢复会话")
                    } catch (e: AcpException) {
                        if (e.isAuthError() && canAuthenticate) {
                            authenticateAndReopen()
                            return
                        }
                        throw e
                    }
                    _conn.update { it.copy(phase = Phase.Ready, statusText = "", error = null) }
                    onSessionReady?.invoke(resumeId, existing.cwd)
                    return
                }
                val store = createStore(resumeId, cwd)
                bindStore(store) // chat shows the replay streaming in
                try {
                    attachStore(store, announce = true)
                } catch (e: AcpException) {
                    if (e.isAuthError() && canAuthenticate) {
                        authenticateAndReopen()
                        return
                    }
                    dropStore(resumeId)
                    throw e
                }
                _conn.update { it.copy(phase = Phase.Ready, statusText = "", error = null) }
                onSessionReady?.invoke(resumeId, cwd)
            }
        } finally {
            _openTarget.value = null
        }
    }

    /** `_devingo/attach` → journal replay; `needLoad` falls back to session/load. */
    private suspend fun attachStore(store: SessionStore, announce: Boolean) {
        store.attachMutex.withLock {
            store.beginReplay()
            try {
                val r = client.attach(store.sessionId, store.lastSeq)
                if (r.needLoad) {
                    loadInto(store)
                } else {
                    store.markAttached(r)
                }
                if (announce) store.append(Kind.System, "会话已恢复 · ${store.cwd}")
            } finally {
                store.endReplay()
                // Live-forwarded frames the backlog didn't also cover (seq
                // past the replayed cursor) are real live traffic — route
                // them now, in order.
                store.drainDeferred().forEach { route(it) }
            }
        }
    }

    /** `session/load` replay (journal no longer reaches back far enough). */
    private suspend fun loadInto(store: SessionStore) {
        store.beginLoad()
        val r = client.loadSession(store.sessionId, store.cwd)
        store.finishLoad(r)
    }

    private suspend fun createStore(sessionId: String, cwd: String): SessionStore {
        val store = SessionStore(
            sessionId = sessionId,
            cwd = cwd,
            client = client,
            json = json,
            notifier = notifier,
            scope = CoroutineScope(
                scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
            ),
            onSessionReady = { sid, c -> onSessionReady?.invoke(sid, c) },
        )
        // U7: show the last cached transcript right away — the attach replay
        // aligns it with the hub behind the staged commit.
        cache.load(sessionId)
            ?.takeIf { it.host == lastHost }
            ?.let { store.restoreFromCache(it) }
        seqStash.remove(sessionId)?.let { store.seedSeq(it) }
        stores[sessionId] = store
        watchStore(store)
        return store
    }

    private fun bindStore(store: SessionStore) {
        // The session going off-screen gets its transcript cached — the
        // next open renders it before the attach replay lands.
        _currentStore.value?.takeIf { it !== store }?.let { prev ->
            scope.launch { cache.save(prev.cacheSnapshot(lastHost)) }
        }
        _currentStore.value = store
        store.unread.value = false
        _conn.update { it.copy(phase = Phase.Ready, statusText = "", error = null) }
    }

    /**
     * Tear down a session's store. [gone] means the session no longer exists
     * server-side — only then is it also filtered out of the cached list;
     * epoch teardowns and attach failures must not flicker the list.
     */
    private fun dropStore(sessionId: String, gone: Boolean = false) {
        val store = stores.remove(sessionId) ?: return
        storeWatchJobs.remove(sessionId)?.cancel()
        reattachJobs.remove(sessionId)?.cancel()
        if (gone) {
            scope.launch { cache.remove(sessionId) }
        } else {
            scope.launch { cache.save(store.cacheSnapshot(lastHost)) }
        }
        store.drop()
        seqStash.remove(sessionId)
        // Non-delete drops leave the hub hosting (and fanning out to) the
        // session — unsubscribe so the subscription doesn't ghost on.
        if (!gone && socketLive) {
            scope.launch { runCatching { client.detach(sessionId) } }
        }
        _badges.update { it - sessionId }
        if (gone) _sessions.update { list -> list?.filterNot { it.sessionId == sessionId } }
        if (_currentStore.value === store) {
            _currentStore.value = null
        }
    }

    /** The store hosting [sessionId], when it's subscribed this epoch. */
    fun storeFor(sessionId: String): SessionStore? = stores[sessionId]

    /** Unbind without unsubscribing — the session keeps running off-screen. */
    fun leaveSession() {
        val leaving = _currentStore.value ?: return
        _currentStore.value = null
        scope.launch { cache.save(leaving.cacheSnapshot(lastHost)) }
    }

    /** Re-attach to repair a journal gap or a session_resync hint. */
    private fun reattach(store: SessionStore) {
        if (!socketLive) return // the reconnect loop re-attaches everything
        if (reattachJobs[store.sessionId]?.isActive == true) return
        reattachJobs[store.sessionId] = scope.launch {
            try {
                attachStore(store, announce = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reattach ${store.sessionId}: ${e.message}")
            }
        }
    }

    // ---- session list -------------------------------------------------------

    /**
     * Refresh the `_devingo/sessions` cache. Cancellation-aware: a newer call
     * supersedes the in-flight one (the hub also collapses concurrent list
     * calls per client).
     */
    fun refreshSessions(cwd: String? = null) {
        if (!socketLive) return
        listJob?.cancel()
        listJob = scope.launch {
            _sessionsLoading.value = true
            _sessionsError.value = null
            try {
                val list = client.devingoSessions(cwd?.ifBlank { null })
                _sessions.value = list
                mergeBadgesFromList(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessionsError.value = e.display()
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    /**
     * Connect (if needed) then refresh — Home's boot path when no socket
     * exists yet. Runs on the list job so it never cancels a session open.
     */
    fun connectAndRefresh(host: String, token: String) {
        noteHost(host, token)
        listJob?.cancel()
        listJob = scope.launch {
            _sessionsLoading.value = true
            _sessionsError.value = null
            try {
                if (ensureSocket()) {
                    val list = client.devingoSessions(null)
                    _sessions.value = list
                    mergeBadgesFromList(list)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sessionsError.value = e.display()
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    /** List overlay for sessions without a local store; stores win live. */
    private fun mergeBadgesFromList(list: List<AcpSession>) {
        val keep = list.map { it.sessionId }.toSet() + stores.keys
        _badges.update { cur ->
            cur.filterKeys { it in keep }.toMutableMap().apply {
                list.forEach { s ->
                    if (stores[s.sessionId] == null) {
                        put(s.sessionId, SessionBadge(s.pendingPermission, s.running, false))
                    }
                }
            }
        }
    }

    /** Track a store's live flags into the badge map for the drawer, and
     *  debounce-save its transcript so a later open renders instantly. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchStore(store: SessionStore) {
        storeWatchJobs[store.sessionId] = scope.launch {
            launch {
                combine(
                    store.pendingPermission,
                    store.ui,
                    store.unread,
                ) { p, u, unread ->
                    SessionBadge(
                        pendingPermission = p != null,
                        running = u.running,
                        unread = unread,
                    )
                }.collect { b ->
                    _badges.update { it + (store.sessionId to b) }
                }
            }
            launch {
                store.messages.debounce(CACHE_SAVE_DEBOUNCE_MS).collect {
                    runCatching { cache.save(store.cacheSnapshot(lastHost)) }
                }
            }
        }
    }

    /** Snapshot every live store — called when the app goes to background. */
    fun persistCaches() {
        stores.values.forEach { s ->
            scope.launch { runCatching { cache.save(s.cacheSnapshot(lastHost)) } }
        }
    }

    /** `session/delete` over the shared socket; updates the cache on success. */
    suspend fun deleteSession(sessionId: String) {
        client.deleteSession(sessionId)
        dropStore(sessionId, gone = true)
    }

    // ---- disconnect / reconnect ----------------------------------------------

    /** Intentional full disconnect (chat menu) — drops the socket and stores. */
    fun disconnect() {
        // Flag the socket dead up front: the teardown below closes it, so
        // per-store detaches during dropEpoch would only race a dying wire —
        // the close itself releases every subscription hub-side.
        socketLive = false
        // Tell the agent to release the session lock before the socket dies —
        // the disconnect lands after it in the same coroutine.
        val sid = _currentStore.value?.sessionId
        scope.launch {
            if (sid != null) {
                runCatching { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { client.closeSession(sid) } }
            }
            client.disconnect()
        }
        pendingOpen = null
        dropEpoch()
        _conn.value = ConnState()
    }

    private fun onSocketDropped(reason: String) {
        socketLive = false
        if (intentionalDisconnect) return
        // e.g. "连接已断开 (1011): devin acp exited" — the wire close detail
        // is why the socket dropped, so it belongs on screen (B3).
        val detail = reason.ifBlank { "连接已断开" }
        stores.values.forEach { it.onDisconnected(detail) }
        if (lastHost.isBlank()) {
            _conn.update {
                it.copy(
                    phase = Phase.Error,
                    statusText = detail,
                    error = reason,
                    reconnecting = false,
                    connected = false,
                )
            }
            return
        }
        if (reconnectJob?.isActive == true) return
        _conn.update {
            it.copy(
                reconnecting = true,
                connected = false,
                statusText = detail,
            )
        }
        reconnectJob = scope.launch {
            var lastError = ""
            for (attempt in 1..RECONNECT_MAX_ATTEMPTS) {
                val backoff = (RECONNECT_BASE_DELAY_MS shl (attempt - 1))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                _conn.update {
                    it.copy(
                        statusText = "$detail · 重连中（第 $attempt/$RECONNECT_MAX_ATTEMPTS 次）…",
                    )
                }
                delay(backoff)
                try {
                    client.connect(wsUrl(lastHost), lastToken)
                    client.initialize()
                    runCatching { client.hello(clientId) }
                    // Live only once the handshake completed — a cancel
                    // mid-handshake must not leave a believed-live socket.
                    socketLive = true
                    // Re-attach every store at its journal cursor — the
                    // replayed gap lands as ordinary events.
                    reattachStores()
                    _conn.update {
                        it.copy(
                            phase = if (_currentStore.value != null) Phase.Ready else it.phase,
                            statusText = "",
                            error = null,
                            reconnecting = false,
                            connected = true,
                        )
                    }
                    refreshSessions()
                    return@launch
                } catch (e: CancellationException) {
                    client.disconnect()
                    socketLive = false
                    throw e
                } catch (e: Exception) {
                    // A post-connect failure can leave the socket open —
                    // close it or the next attempt orphans it bridge-side.
                    client.disconnect()
                    socketLive = false
                    lastError = e.display()
                }
            }
            _conn.update {
                it.copy(
                    phase = Phase.Error,
                    reconnecting = false,
                    statusText = detail,
                    error = "自动重连失败: $lastError",
                )
            }
        }
    }

    /**
     * Re-attach every store at its journal cursor (socket recovery paths —
     * the reconnect loop and ensureSocket when stores outlived a drop).
     * A store whose attach fails keeps its transcript and stays flagged
     * unattached so the next open/reattach retries it — only an explicit
     * session_deleted/session/delete may drop a store, not a transient
     * attach error.
     */
    private suspend fun reattachStores() {
        for (store in stores.values) {
            if (store.attached) continue
            try {
                attachStore(store, announce = false)
                store.append(Kind.System, "已重连并恢复会话")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reattach ${store.sessionId}: ${e.message}")
                store.append(Kind.System, "会话恢复失败: ${e.display()}")
            }
        }
    }

    // ---- authenticate --------------------------------------------------------

    /** Manual/forced login — retries the pending open afterwards. */
    fun authenticate() {
        val apiKey = lastApiKey
        openJob?.cancel()
        openJob = scope.launch {
            try {
                if (apiKey != null) {
                    _conn.update {
                        it.copy(phase = Phase.Authenticating, statusText = "API key 登录中…")
                    }
                    client.authenticate("devin-browser", apiKey)
                } else {
                    _conn.update {
                        it.copy(
                            phase = Phase.Authenticating,
                            awaitingBrowserLogin = true,
                            statusText = "等待在电脑浏览器中完成登录…",
                        )
                    }
                    client.authenticate("devin-browser")
                }
                _conn.update { it.copy(awaitingBrowserLogin = false) }
                val target = pendingOpen
                if (target != null) {
                    _conn.update {
                        it.copy(phase = Phase.StartingSession, statusText = "已登录，打开会话…")
                    }
                    openOrCreate(target.resumeId, target.cwd.orEmpty().ifBlank { defaultCwd })
                } else {
                    _conn.update { it.copy(phase = Phase.Ready, statusText = "") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _conn.update {
                    it.copy(
                        phase = Phase.Error,
                        awaitingBrowserLogin = false,
                        error = "认证失败: ${e.display()}",
                    )
                }
            }
        }
    }

    /** The session open died on auth_required: log in, then reopen. */
    private suspend fun authenticateAndReopen() {
        val target = pendingOpen ?: return
        try {
            if (lastApiKey != null) {
                _conn.update {
                    it.copy(phase = Phase.Authenticating, statusText = "API key 登录中…")
                }
                client.authenticate("devin-browser", lastApiKey)
            } else {
                _conn.update {
                    it.copy(
                        phase = Phase.Authenticating,
                        awaitingBrowserLogin = true,
                        statusText = "等待在电脑浏览器中完成登录…",
                    )
                }
                client.authenticate("devin-browser")
            }
            _conn.update { it.copy(awaitingBrowserLogin = false) }
            openOrCreate(target.resumeId, target.cwd.orEmpty().ifBlank { defaultCwd })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _conn.update {
                it.copy(
                    phase = Phase.Error,
                    awaitingBrowserLogin = false,
                    error = "认证失败: ${e.display()}",
                )
            }
        }
    }

    /**
     * The (resumeId, cwd) the last open attempt was aimed at — an error
     * snackbar's 重试 replays exactly these. Null resumeId = a new session.
     */
    fun retryTarget(): Pair<String?, String> =
        (pendingOpen?.resumeId) to (pendingOpen?.cwd ?: pendingCwd)

    private fun AcpException.isAuthError(): Boolean =
        code == ACP_ERROR_AUTH_REQUIRED || message?.let {
            it.contains("auth", ignoreCase = true) ||
                it.contains("login", ignoreCase = true) ||
                it.contains("登录") || it.contains("认证")
        } == true

    // ---- demux ---------------------------------------------------------------

    private fun route(ev: InboundEvent) {
        if (ev.event is AcpEvent.Disconnected) {
            onSocketDropped(ev.event.reason)
            return
        }
        // Unscoped frames (parse failures, unjournaled logs) go to whatever
        // is on screen; session-scoped frames only to their own store —
        // a scoped frame for a session we don't host is dropped, never
        // leaked into the transcript on screen.
        val store = if (ev.sessionId != null) {
            stores[ev.sessionId] ?: return
        } else {
            _currentStore.value ?: return
        }
        // Two checks before consuming — both skipped for session_deleted:
        // a deleted session can't be re-attached, so its tombstone must be
        // consumed now or the store would ghost forever.
        if (ev.event !is AcpEvent.SessionDeleted) {
            // An `_devingo/attach` is in flight: a journaled frame landing
            // now may arrive AGAIN inside the attach backlog — park it and
            // let drainDeferred replay whatever the backlog didn't cover.
            if (store.deferIfReplaying(ev)) return
            // A live frame skipping ahead of the cursor means the gateway
            // withheld journaled entries (another client's load window, or
            // a plain journal hole): re-attach at the PRE-gap cursor and
            // drop the frame — the replay re-delivers it (and the gap) in
            // order. The check must run before noteSeq or the cursor is
            // already advanced past the withheld window and the attach
            // replays nothing.
            if (ev.seq > 0 && store.liveGap(ev.seq)) {
                reattach(store)
                return
            }
        }
        if (ev.seq > 0) store.noteSeq(ev.seq)
        when (val e = ev.event) {
            // Only a LIVE resync marker re-attaches; a marker arriving via
            // the replay itself just advanced the cursor — reacting to it
            // would loop attaches forever.
            is AcpEvent.SessionResync -> if (store.isLive(ev.seq)) reattach(store)
            is AcpEvent.SessionDeleted -> dropStore(store.sessionId, gone = true)
            is AcpEvent.PermissionResolved -> store.resolvePeerPermission(e.requestId)
            else -> {
                store.onEvent(e, ev.seq)
                // Replay backlog is history — only live frames light unread.
                if (store !== _currentStore.value && store.isLive(ev.seq) &&
                    e.marksUnread()
                ) {
                    store.unread.value = true
                }
            }
        }
    }

    /** Content events that should light the unread badge off-screen. */
    private fun AcpEvent.marksUnread(): Boolean = when (this) {
        is AcpEvent.AgentChunk, is AcpEvent.UserChunk, is AcpEvent.ThoughtChunk,
        is AcpEvent.ToolCall, is AcpEvent.ToolCallUpdate, is AcpEvent.PlanUpdate,
        is AcpEvent.SessionTitle, is AcpEvent.Failure, is AcpEvent.RemoteTurnEnd -> true
        else -> false
    }

    /** Agent->client requests route by params.sessionId to the owning store. */
    private suspend fun routeRequest(req: IncomingRequest) {
        val sid = req.sessionId
        val store = sid?.let { stores[it] }
        if (store == null) {
            req.respondError(JSONRPC_METHOD_NOT_FOUND, "DevinGo 不支持 ${req.method}")
            return
        }
        if (req.method == AcpMethods.SESSION_REQUEST_PERMISSION &&
            store !== _currentStore.value
        ) {
            val title = store.ui.value.sessionTitle?.takeIf { it.isNotBlank() }
                ?: stores[sid]?.cwd?.substringAfterLast('/')?.substringAfterLast('\\')
                ?: sid.orEmpty()
            _alerts.tryEmit(SessionAlert(store.sessionId, "「$title」需要你的授权"))
        }
        store.handleAgentRequest(req)
    }

    // ---- misc ------------------------------------------------------------------

    private fun loadBridgeModels() {
        val httpUrl = healthUrl(lastHost)
        scope.launch {
            val models = runCatching { fetchBridgeModels(httpUrl, lastToken) }
                .getOrNull().orEmpty()
            if (models.isNotEmpty()) _bridgeModels.value = models
        }
    }

    private fun wsUrl(host: String): String = when {
        host.startsWith("ws://") || host.startsWith("wss://") ->
            if (host.endsWith("/acp")) host else "$host/acp"
        else -> "ws://$host/acp"
    }

    private fun healthUrl(host: String): String {
        val h = host.removePrefix("ws://").removePrefix("wss://")
            .removeSuffix("/acp").trimEnd('/')
        val scheme = if (host.startsWith("wss://")) "https" else "http"
        return "$scheme://$h"
    }
}
