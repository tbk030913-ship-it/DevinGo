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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainActivity : ComponentActivity() {

    private val prefs by lazy { Prefs(this) }
    private var pendingNotifAction: String? = null
    private var notifActionHandler: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotifAction = intent?.getStringExtra(SessionService.EXTRA_NOTIFICATION_ACTION)
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
                    val messages by vm.messages.collectAsState()
                    val pendingPermission by vm.pendingPermission.collectAsState()

                    val settingsVersion by prefs.settingsVersion.collectAsState()

                    // Foreground-service keep-alive while a session is open.
                    // Keyed on the settings version so toggles apply live.
                    LaunchedEffect(
                        ui.phase == ChatViewModel.Phase.Ready,
                        prefs.keepAlive,
                        settingsVersion,
                    ) {
                        if (ui.phase == ChatViewModel.Phase.Ready && prefs.keepAlive) {
                            SessionService.start(
                                this@MainActivity,
                                ui.sessionTitle ?: ui.agentTitle,
                            )
                        } else {
                            SessionService.stop(this@MainActivity)
                        }
                    }
                    // Live Updates surface: derive the notification status from
                    // the live flows. Map → dedupe → debounce so the service only
                    // ever sees real state changes, never per-chunk flushes.
                    LaunchedEffect(Unit) {
                        var turnStart = 0L
                        var wasRunning = false
                        var finished: LiveStatus.State? = null
                        var finishedAt = 0L
                        combine(
                            vm.ui,
                            vm.messages,
                            vm.pendingPermission,
                            prefs.settingsVersion,
                        ) { u, m, p, _ -> Triple(u, m, p) }
                            .map { (u, m, p) ->
                                if (u.running && !wasRunning) {
                                    turnStart = System.currentTimeMillis()
                                    finished = null
                                }
                                if (!u.running && wasRunning) {
                                    finished = if (u.phase == ChatViewModel.Phase.Error ||
                                        !u.error.isNullOrBlank()
                                    ) {
                                        LiveStatus.State.Failed
                                    } else {
                                        LiveStatus.State.Done
                                    }
                                    finishedAt = System.currentTimeMillis()
                                }
                                wasRunning = u.running
                                // The card only lives for DONE_VISIBLE_MS; after
                                // that the service reposts plain idle text.
                                if (finished != null &&
                                    System.currentTimeMillis() - finishedAt >= DONE_VISIBLE_MS
                                ) {
                                    finished = null
                                }
                                var status = deriveLiveStatus(u, m, p, turnStart, finished)
                                // The user is looking at the chat: the finished
                                // card collapses back to plain idle text.
                                if ((status.state == LiveStatus.State.Done ||
                                        status.state == LiveStatus.State.Failed) &&
                                    AppVisibility.isForeground &&
                                    AppVisibility.chatVisible
                                ) {
                                    finished = null
                                    status = status.copy(
                                        state = LiveStatus.State.Idle,
                                        detail = "等待指令",
                                    )
                                }
                                val gated = status.copy(promoteOngoing = prefs.liveUpdates)
                                if (u.phase == ChatViewModel.Phase.Ready &&
                                    prefs.keepAlive
                                ) {
                                    gated
                                } else {
                                    null
                                }
                            }
                            .filterNotNull()
                            .distinctUntilChanged()
                            .debounce(300)
                            .collect { status ->
                                SessionService.update(this@MainActivity, status)
                            }
                    }
                    // Live notification actions (允许/拒绝) resolve through the
                    // same path as the in-app permission card.
                    notifActionHandler = { action ->
                        when (action) {
                            SessionService.EXTRA_ACTION_ALLOW -> resolvePermissionFromOptions(vm, allow = true)
                            SessionService.EXTRA_ACTION_DENY -> resolvePermissionFromOptions(vm, allow = false)
                        }
                    }
                    LaunchedEffect(Unit) {
                        pendingNotifAction?.let { notifActionHandler?.invoke(it) }
                        pendingNotifAction = null
                    }
                    AppShell(vm, prefs)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(SessionService.EXTRA_NOTIFICATION_ACTION)?.let { action ->
            val handler = notifActionHandler
            if (handler != null) handler(action) else pendingNotifAction = action
        }
    }

    /** Maps a notification 允许/拒绝 tap onto the pending permission's options. */
    private fun resolvePermissionFromOptions(vm: ChatViewModel, allow: Boolean) {
        val pending = vm.pendingPermission.value ?: return
        // Allow only ever picks allow_once — allow_always is a persistent
        // grant the notification must not hand out silently. Deny prefers
        // reject_once, then reject_always; no match resolves as cancelled.
        val option = if (allow) {
            pending.options.firstOrNull { it.kind == "allow_once" }
        } else {
            pending.options.firstOrNull { it.kind == "reject_once" }
                ?: pending.options.firstOrNull { it.kind == "reject_always" }
        }
        vm.resolvePermission(option?.optionId)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
    }

    override fun onStop() {
        AppVisibility.isForeground = false
        super.onStop()
    }

    private companion object {
        const val DONE_VISIBLE_MS = 10 * 60 * 1000L
    }
}
