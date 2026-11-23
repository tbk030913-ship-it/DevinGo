package com.devingo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatViewModel
import com.devingo.app.Prefs
import com.devingo.app.acp.AcpSession
import com.devingo.app.tailnet.TailnetManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Root navigation (spec §N): Home full-screen when no session is open; with
 * a session open, Home becomes the left drawer over the chat. Phase changes
 * cross-fade+scale; session switches use the disconnect → 2.3s → connect
 * workaround for the VM's async socket teardown race.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(vm: ChatViewModel, prefs: Prefs) {
    val ui by vm.ui.collectAsState()
    val sessionOpen = ui.phase == ChatViewModel.Phase.Ready
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var pendingSwitch by remember { mutableStateOf<PendingConnect?>(null) }
    // Shared with both Home surfaces so the drawer lists the same project
    // groups the user expanded on full-screen Home. homeState keeps the
    // list cache + display prefs alive while HomeScreen is uncomposed —
    // the drawer drops its content while closed (see below), so without it
    // every open cold-started on a skeleton; a fresh composition refreshes
    // in the background behind the cached rows.
    var expandedProjects by remember { mutableStateOf(prefs.expandedProjects) }
    val homeState = remember { HomeState() }

    // Session switching / new session while one is open: the old socket can
    // take ~2s to die asynchronously and would kill a fresh connection, so
    // disconnect first, wait, then connect.
    fun switchSession(resumeId: String?, cwd: String?, sessionId: String?) {
        pendingSwitch = PendingConnect(resumeId, cwd, sessionId)
        vm.disconnect()
        scope.launch {
            delay(2300)
            val p = pendingSwitch ?: return@launch
            pendingSwitch = null
            // Tailnet failure keeps the raw host: the connect attempt then
            // fails through the normal error path instead of silently no-oping.
            val host = prefs.host.trim()
            vm.connect(
                runCatching { TailnetManager.resolveHost(host) }.getOrDefault(host),
                prefs.token.trim(),
                (p.cwd ?: prefs.cwd.trim()),
                resumeSessionId = p.resumeId,
                apiKey = prefs.apiKey,
            )
        }
    }

    // If disconnect leaves phase == Ready (e.g. socket already gone), the
    // pending connect still fires on its own timer above.

    AnimatedContent(
        targetState = sessionOpen,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f)) togetherWith
                (fadeOut(tween(150)) + scaleOut(targetScale = 0.98f))
        },
        label = "phase",
        modifier = Modifier.fillMaxSize(),
    ) { open ->
        if (open) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen,
                scrimColor = Color.Black.copy(alpha = 0.08f),
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.86f),
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        drawerShape = androidx.compose.ui.graphics.RectangleShape,
                        drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        // Compose Home only while the sheet is open or
                        // animating/dragging (targetValue flips to Open as
                        // soon as an open starts; currentValue stays Open
                        // until a close settles). A closed drawer is an
                        // empty placeholder off-screen, so streaming chunks
                        // never recompose the session list under it. The
                        // gate relies on gesturesEnabled = isOpen above:
                        // opens are programmatic only. If edge-swipe-to-open
                        // is ever enabled, a sub-threshold drag would make
                        // the partially slid-out sheet render empty.
                        if (drawerState.currentValue == DrawerValue.Open ||
                            drawerState.targetValue == DrawerValue.Open
                        ) {
                            HomeScreen(
                                vm = vm,
                                prefs = prefs,
                                drawerMode = true,
                                homeState = homeState,
                                expandedProjects = expandedProjects,
                                onExpandedChange = {
                                    expandedProjects = it
                                    prefs.expandedProjects = it
                                },
                                pendingSwitch = pendingSwitch,
                                onPickSession = { s: AcpSession ->
                                    scope.launch { drawerState.close() }
                                    switchSession(s.sessionId, s.cwd, s.sessionId)
                                },
                                onNewSession = {
                                    scope.launch { drawerState.close() }
                                    switchSession(null, null, null)
                                },
                                onNewSessionCwd = { cwd ->
                                    scope.launch { drawerState.close() }
                                    switchSession(null, cwd, null)
                                },
                            )
                        }
                    }
                },
            ) {
                ChatScreen(
                    vm = vm,
                    sessionCwd = prefs.lastSessionCwd,
                    onOpenDrawer = {
                        scope.launch { drawerState.open() }
                    },
                )
            }
        } else {
            HomeScreen(
                vm = vm,
                prefs = prefs,
                drawerMode = false,
                homeState = homeState,
                expandedProjects = expandedProjects,
                onExpandedChange = {
                    expandedProjects = it
                    prefs.expandedProjects = it
                },
                pendingSwitch = pendingSwitch,
                onPickSession = { s ->
                    scope.launch { startConnectFromHome(vm, prefs, s) }
                },
                onNewSession = {
                    scope.launch { startConnectFromHome(vm, prefs, null) }
                },
                onNewSessionCwd = { cwd ->
                    scope.launch { startConnectCwd(vm, prefs, cwd) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun startConnectFromHome(vm: ChatViewModel, prefs: Prefs, s: AcpSession?) {
    val host = prefs.host.trim()
    if (host.isEmpty()) return
    vm.connect(
        runCatching { TailnetManager.resolveHost(host) }.getOrDefault(host),
        prefs.token.trim(),
        (s?.cwd ?: prefs.cwd.trim()),
        resumeSessionId = s?.sessionId,
        apiKey = prefs.apiKey,
    )
}

/** Project-group "+": fresh session rooted at that group's cwd. */
private suspend fun startConnectCwd(vm: ChatViewModel, prefs: Prefs, cwd: String?) {
    val host = prefs.host.trim()
    if (host.isEmpty() || cwd.isNullOrBlank()) return
    vm.connect(
        runCatching { TailnetManager.resolveHost(host) }.getOrDefault(host),
        prefs.token.trim(),
        cwd,
        resumeSessionId = null,
        apiKey = prefs.apiKey,
    )
}
