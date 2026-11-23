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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatViewModel
import com.devingo.app.Prefs
import com.devingo.app.acp.AcpSession
import com.devingo.app.tailnet.TailnetManager
import kotlinx.coroutines.launch

/**
 * Root navigation (spec §N): Home full-screen when no session is open; with
 * a session open, Home becomes the left drawer over the chat. Phase changes
 * cross-fade+scale. Session switches re-bind the shared connection's store —
 * no disconnect, no wait, no Home flash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(vm: ChatViewModel, prefs: Prefs) {
    val ui by vm.ui.collectAsState()
    val openTarget by vm.openTarget.collectAsState()
    val sessionOpen = ui.sessionId != null
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Shared with both Home surfaces so the drawer lists the same project
    // groups the user expanded on full-screen Home. homeState keeps the
    // list cache + display prefs alive while HomeScreen is uncomposed —
    // the drawer drops its content while closed (see below), so without it
    // every open cold-started on a skeleton; a fresh composition refreshes
    // in the background behind the cached rows.
    var expandedProjects by remember { mutableStateOf(prefs.expandedProjects) }
    val homeState = remember { HomeState() }
    val snackbarHostState = remember { SnackbarHostState() }

    // One background refresh per drawer opening; rows render the shared
    // `_devingo/sessions` cache immediately.
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            vm.refreshSessions(resolvedHost(prefs), prefs.token.trim())
        }
    }

    // Something needs attention in a session that isn't on screen — the
    // snackbar's action jumps straight to it.
    LaunchedEffect(Unit) {
        vm.alerts.collect { alert ->
            val result = snackbarHostState.showSnackbar(
                message = alert.message,
                actionLabel = if (alert.openable) "打开" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.openSession(alert.sessionId)
            }
        }
    }

    // Drawer picks pass the *current* prefs host+token so a settings-changed
    // host is noticed before the open (noteHost drops the stale epoch).
    fun pickSession(s: AcpSession) {
        scope.launch {
            drawerState.close()
            vm.openSession(s.sessionId, s.cwd, resolvedHost(prefs), prefs.token.trim())
        }
    }

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
                                openTarget = openTarget,
                                onPickSession = ::pickSession,
                                onNewSession = {
                                    scope.launch {
                                        drawerState.close()
                                        vm.newSession(null, resolvedHost(prefs), prefs.token.trim())
                                    }
                                },
                                onNewSessionCwd = { cwd ->
                                    scope.launch {
                                        drawerState.close()
                                        vm.newSession(cwd, resolvedHost(prefs), prefs.token.trim())
                                    }
                                },
                            )
                        }
                    }
                },
            ) {
                ChatScreen(
                    vm = vm,
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
                openTarget = openTarget,
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
    Box(Modifier.fillMaxSize()) {
        DevinSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private suspend fun startConnectFromHome(vm: ChatViewModel, prefs: Prefs, s: AcpSession?) {
    val host = resolvedHost(prefs)
    if (host.isEmpty()) return
    vm.connect(
        host,
        prefs.token.trim(),
        (s?.cwd ?: prefs.cwd.trim()),
        resumeSessionId = s?.sessionId,
        apiKey = prefs.apiKey,
    )
}

private suspend fun resolvedHost(prefs: Prefs): String {
    val host = prefs.host.trim()
    return runCatching { TailnetManager.resolveHost(host) }.getOrDefault(host)
}

/** Project-group "+": fresh session rooted at that group's cwd. */
private suspend fun startConnectCwd(vm: ChatViewModel, prefs: Prefs, cwd: String?) {
    val host = resolvedHost(prefs)
    if (host.isEmpty() || cwd.isNullOrBlank()) return
    vm.connect(
        host,
        prefs.token.trim(),
        cwd,
        resumeSessionId = null,
        apiKey = prefs.apiKey,
    )
}
