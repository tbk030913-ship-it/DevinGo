package com.devingo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devingo.app.tailnet.TailnetManager
import com.devingo.app.ui.AppShell
import com.devingo.app.ui.theme.DevinGoTheme
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainActivity : ComponentActivity() {

    private val prefs by lazy { Prefs(this) }
    private var pendingNotifAction: String? = null
    /** Session an allow/deny action pins its answer to. */
    private var pendingNotifActionSession: String? = null
    /** Session a notification tap opens — never an allow/deny target. */
    private var pendingNotifSession: String? = null
    /** (action, sessionId) — the extra pins which session's request answers. */
    private var notifActionHandler: ((String, String?) -> Unit)? = null
    private var notifSessionHandler: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotifAction = intent?.getStringExtra(SessionService.EXTRA_NOTIFICATION_ACTION)
        val notifSid = intent?.getStringExtra(Notifier.EXTRA_SESSION_ID)
        // An allow/deny extra pins which session's request it answers; only a
        // plain notification tap (no action) is a request to navigate there.
        if (pendingNotifAction != null) {
            pendingNotifActionSession = notifSid
        } else {
            pendingNotifSession = notifSid
        }
        // Warm the embedded tailnet node early when the switch is on so the
        // first connect doesn't pay its startup latency.
        TailnetManager.init(this, prefs)
        // Light-only app: keep system bar icons dark even in system night mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            DevinGoTheme {
                CompositionLocalProvider(
                    LocalToaster provides Toaster(this@MainActivity),
                ) {
                    val vm: ChatViewModel = viewModel()
                    LaunchedEffect(Unit) {
                        vm.requestNotificationPermission(this@MainActivity)
                    }
                    vm.onSessionReady = { id, cwd ->
                        prefs.lastSessionId = id
                        prefs.lastSessionCwd = cwd
                    }
                    val ui by vm.ui.collectAsState()

                    val settingsVersion by prefs.settingsVersion.collectAsState()

                    // Foreground-service keep-alive while the shared ACP
                    // socket is up — including Home, where background
                    // sessions still stream.
                    // Keyed on the settings version so toggles apply live.
                    val connected = ui.connected || ui.reconnecting
                    LaunchedEffect(connected, prefs.keepAlive, settingsVersion) {
                        if (connected && prefs.keepAlive) {
                            SessionService.start(
                                this@MainActivity,
                                ui.sessionTitle ?: ui.agentTitle,
                            )
                        } else {
                            SessionService.stop(this@MainActivity)
                        }
                    }
                    // Live Updates surface: every SessionStore derives its
                    // own LiveStatus app-side (N6.3); this collector only
                    // folds them into the aggregate snapshot and pushes —
                    // deduped + debounced so the service sees real changes.
                    LaunchedEffect(Unit) {
                        val cm = ConnectionManager.get(application)
                        combine(
                            cm.sessionStatuses,
                            cm.conn,
                            cm.pendingApprovals,
                            prefs.settingsVersion,
                        ) { _, conn, pendingCount, _ -> conn to pendingCount }
                            .map { (conn, pendingCount) ->
                                if ((conn.connected || conn.reconnecting) &&
                                    prefs.keepAlive
                                ) {
                                    // The user is looking at this chat: its
                                    // finished card collapses back to idle.
                                    val collapseFor =
                                        if (AppVisibility.isForeground &&
                                            AppVisibility.chatVisible
                                        ) {
                                            AppVisibility.chatSessionId
                                        } else {
                                            null
                                        }
                                    cm.activitySnapshot(collapseFor, pendingCount)
                                        .withPromote(prefs.liveUpdates)
                                } else {
                                    null
                                }
                            }
                            .flowOn(Dispatchers.Default)
                            .filterNotNull()
                            .distinctUntilChanged()
                            .debounce(300)
                            .collect { snapshot ->
                                SessionService.update(this@MainActivity, snapshot)
                            }
                    }
                    // Live notification actions (允许/拒绝) resolve through the
                    // same path as the in-app permission card — but pinned to
                    // the session the notification was issued for.
                    notifActionHandler = { action, sid ->
                        when (action) {
                            SessionService.EXTRA_ACTION_ALLOW ->
                                resolvePermissionFromOptions(vm, allow = true, sessionId = sid)
                            SessionService.EXTRA_ACTION_DENY ->
                                resolvePermissionFromOptions(vm, allow = false, sessionId = sid)
                        }
                    }
                    // A notification's sessionId routes to its owning session.
                    notifSessionHandler = { sid -> vm.openSession(sid) }
                    LaunchedEffect(Unit) {
                        pendingNotifAction?.let {
                            notifActionHandler?.invoke(it, pendingNotifActionSession)
                        }
                        pendingNotifAction = null
                        pendingNotifActionSession = null
                        pendingNotifSession?.let { notifSessionHandler?.invoke(it) }
                        pendingNotifSession = null
                    }
                    AppShell(vm, prefs)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val sid = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
        val action = intent.getStringExtra(SessionService.EXTRA_NOTIFICATION_ACTION)
        if (action != null) {
            // 允许/拒绝 answers the pinned session's request in place —
            // answering from the shade must not teleport into that session.
            val handler = notifActionHandler
            if (handler != null) {
                handler(action, sid)
            } else {
                pendingNotifAction = action
                pendingNotifActionSession = sid
            }
        } else if (sid != null) {
            // Only a plain notification tap navigates into its session.
            val handler = notifSessionHandler
            if (handler != null) handler(sid) else pendingNotifSession = sid
        }
    }

    /**
     * Maps a notification 允许/拒绝 tap onto the pending permission's options —
     * of the session the notification was issued for ([sessionId], null = the
     * one on screen).
     */
    private fun resolvePermissionFromOptions(
        vm: ChatViewModel,
        allow: Boolean,
        sessionId: String? = null,
    ) {
        val pending = if (sessionId != null) {
            vm.pendingPermissionOf(sessionId)
        } else {
            vm.pendingPermission.value
        } ?: return
        // Allow only ever picks allow_once — allow_always is a persistent
        // grant the notification must not hand out silently. Deny prefers
        // reject_once, then reject_always; no match resolves as cancelled.
        val option = if (allow) {
            pending.options.firstOrNull { it.kind == "allow_once" }
        } else {
            pending.options.firstOrNull { it.kind == "reject_once" }
                ?: pending.options.firstOrNull { it.kind == "reject_always" }
        }
        if (sessionId != null) {
            vm.resolvePermissionFor(sessionId, option?.optionId)
        } else {
            vm.resolvePermission(option?.optionId)
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
        // Foreground = a good moment to retry a dead socket immediately
        // rather than waiting out the reconnect backoff.
        ConnectionManager.get(application).onAppForeground()
    }

    override fun onStop() {
        AppVisibility.isForeground = false
        // Transcript caches flush here so a killed process still reopens
        // sessions on the last good snapshot.
        ConnectionManager.get(application).persistCaches()
        super.onStop()
    }
}
