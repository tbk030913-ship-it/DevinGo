package com.devingo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatViewModel
import com.devingo.app.OpenTarget
import com.devingo.app.Phase
import com.devingo.app.Prefs
import com.devingo.app.acp.AcpSession
import com.devingo.app.acp.fetchBridgeHealth
import com.devingo.app.tailnet.TailnetManager
import com.devingo.app.tailnet.TailnetState
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.QrCode01
import me.rerere.hugeicons.stroke.Settings02

/** A swipe-deleted session waiting on its undo snackbar before committing. */
internal data class PendingSessionDelete(val session: AcpSession, val index: Int)

/**
 * Home state owned by AppShell so it survives HomeScreen leaving composition
 * (the drawer drops its content while closed; full-screen Home while a
 * session is open). A reopen renders the cached list instantly while the
 * fresh composition's load refreshes it in the background, and an
 * interrupted delete-undo snackbar re-arms instead of dropping the delete.
 */
internal class HomeState {
    var sessions by mutableStateOf<List<AcpSession>?>(null)
    var pendingDeletes by mutableStateOf(listOf<PendingSessionDelete>())
    var sortBy by mutableStateOf(HomeSort.Recent)
    var hideEmpty by mutableStateOf(false)
    var compact by mutableStateOf(false)
    var query by mutableStateOf("")
    var searchOpen by mutableStateOf(false)
    val listState = LazyListState()
}

/** Sessions bucketed under one project (canonical cwd) for §A grouping. */
internal data class ProjectGroup(
    val key: String,
    val label: String,
    /** Representative cwd of the group — the "+" new-session target. */
    val cwd: String?,
    val sessions: List<AcpSession>,
)

/**
 * Devin Home / session list. Full-screen when no session is open; the same
 * composable renders inside the chat drawer ([drawerMode]). Sessions are
 * grouped by project (canonical cwd) like Devin Desktop's sidebar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    drawerMode: Boolean,
    homeState: HomeState,
    expandedProjects: Set<String>?,
    onExpandedChange: (Set<String>) -> Unit,
    openTarget: OpenTarget?,
    onPickSession: (AcpSession) -> Unit,
    onNewSession: () -> Unit,
    onNewSessionCwd: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var sessionsLoading by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var lastLoadOk by remember { mutableStateOf<Boolean?>(null) }
    var pullRefreshing by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var pairOpen by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf<HomeFilterPage?>(null) }
    var showAllGroups by remember { mutableStateOf(setOf<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    val host = prefs.host.trim()
    val configured = host.isNotEmpty()
    val tailnetState by TailnetManager.state.collectAsState()
    val tailnetRouted = prefs.tailnetEnabled && TailnetManager.hostNeedsTailnet(host)
    val busy = ui.phase in listOf(
        Phase.Checking,
        Phase.Connecting,
        Phase.Authenticating,
        Phase.StartingSession,
    )

    /** resolveHost failed — tailnet host configured but the node isn't up. */
    fun showTailnetError() {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Tailscale 未连接",
                actionLabel = "去设置",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) settingsOpen = true
        }
    }

    fun startConnect(resumeId: String? = null, sessionCwd: String? = null) {
        if (host.isEmpty()) {
            settingsOpen = true
            return
        }
        scope.launch {
            val resolved = try {
                TailnetManager.resolveHost(host)
            } catch (e: IllegalStateException) {
                showTailnetError()
                return@launch
            }
            vm.connect(
                resolved,
                prefs.token.trim(),
                (sessionCwd ?: prefs.cwd.trim()),
                resumeSessionId = resumeId,
                apiKey = prefs.apiKey,
            )
        }
    }

    fun loadSessions() {
        sessionsError = null
        sessionsLoading = true
        pullRefreshing = homeState.sessions != null
        scope.launch {
            if (host.isEmpty()) {
                homeState.sessions = emptyList()
                sessionsLoading = false
                lastLoadOk = null
                pullRefreshing = false
                return@launch
            }
            val resolved = try {
                TailnetManager.resolveHost(host)
            } catch (e: IllegalStateException) {
                sessionsError = "Tailscale 未连接"
                sessionsLoading = false
                pullRefreshing = false
                lastLoadOk = false
                showTailnetError()
                return@launch
            }
            // The shared `_devingo/sessions` cache fills homeState.sessions
            // via the collector below; failures land on the same flow.
            vm.refreshSessions(resolved, prefs.token.trim())
        }
    }

    fun commitSessionDelete(s: AcpSession) {
        scope.launch {
            val ok = runCatching {
                try {
                    TailnetManager.resolveHost(host)
                } catch (e: IllegalStateException) {
                    showTailnetError()
                    return@runCatching false
                }
                vm.deleteSession(s.sessionId)
            }.getOrDefault(false)
            if (!ok) loadSessions()
        }
    }

    /** QR result: probe all LAN candidates in parallel, fall back to tailnet. */
    fun applyPair(info: PairInfo) {
        scope.launch {
            val token = info.token.ifBlank { prefs.token }
            val busy = launch {
                snackbarHostState.showSnackbar("正在连接 bridge…", duration = SnackbarDuration.Indefinite)
            }
            try {
                // Parallel probes keep order preference: first candidate in
                // payload order that answers healthz wins.
                val reachable = info.hosts.map { h ->
                    async(Dispatchers.IO) {
                        h to runCatching { fetchBridgeHealth("http://$h", token) }.isSuccess
                    }
                }.awaitAll().firstOrNull { it.second }?.first
                val chosen = reachable ?: info.tail.takeIf { it.isNotBlank() }
                if (chosen == null) {
                    snackbarHostState.showSnackbar("没有找到可连接的 bridge 地址")
                    return@launch
                }
                if (info.token.isNotBlank()) prefs.token = info.token
                if (TailnetManager.hostNeedsTailnet(chosen)) prefs.tailnetEnabled = true
                prefs.host = chosen
                snackbarHostState.showSnackbar("已填入 $chosen")
            } finally {
                busy.cancel()
            }
        }
    }

    fun removeSessionLocal(s: AcpSession) {
        val idx = homeState.sessions?.indexOfFirst { it.sessionId == s.sessionId }
            ?.coerceAtLeast(0) ?: 0
        homeState.sessions = homeState.sessions?.filterNot {
            it.sessionId == s.sessionId
        }
        homeState.pendingDeletes = homeState.pendingDeletes +
            PendingSessionDelete(s, idx)
    }

    fun settleDelete(p: PendingSessionDelete, undone: Boolean) {
        homeState.pendingDeletes = homeState.pendingDeletes - p
        if (undone) {
            homeState.sessions = homeState.sessions?.let { list ->
                list.toMutableList().apply {
                    add(p.index.coerceIn(0, size), p.session)
                }
            }
        } else {
            commitSessionDelete(p.session)
        }
    }

    // The shared `_devingo/sessions` cache is the single source: rows read
    // it instantly, refreshes land here. Badges come from the same manager.
    val badges by vm.badges.collectAsState()
    LaunchedEffect(Unit) {
        vm.sessions.collect { if (it != null) homeState.sessions = it }
    }
    LaunchedEffect(Unit) {
        combine(vm.sessionsLoading, vm.sessionsError) { l, e -> l to e }
            .collect { (loading, err) ->
                sessionsLoading = loading
                sessionsError = err
                pullRefreshing = loading && homeState.sessions != null
                if (!loading) {
                    lastLoadOk = when {
                        homeState.sessions == null -> null
                        else -> err == null
                    }
                }
            }
    }

    // Reload when the node reaches Running so a late-starting tailnet doesn't
    // leave the list stuck on "Tailscale 未连接".
    val tailnetReady = tailnetRouted && tailnetState is TailnetState.Running
    // Fresh composition (each drawer open) refires this; it also re-runs
    // in place if host changes or the tailnet node comes up while open.
    LaunchedEffect(host, tailnetReady) { loadSessions() }
    LaunchedEffect(homeState.pendingDeletes.firstOrNull()?.session?.sessionId) {
        val head = homeState.pendingDeletes.firstOrNull() ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已删除会话",
            actionLabel = "撤销",
            duration = SnackbarDuration.Short,
        )
        settleDelete(head, result == SnackbarResult.ActionPerformed)
    }
    LaunchedEffect(ui.error) {
        ui.error?.let { msg ->
            val isAuth = msg.contains("auth", true) || msg.contains("登录", true) ||
                msg.contains("401", true) || msg.contains("403", true)
            val result = snackbarHostState.showSnackbar(
                message = msg.take(120),
                actionLabel = if (isAuth) "重新登录" else "重试",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (isAuth) {
                    vm.authenticate()
                } else {
                    // Replay the failed attempt's session/cwd — a bare
                    // startConnect() would lose the resume target.
                    val (resumeId, cwd) = vm.retryTarget()
                    startConnect(resumeId, cwd)
                }
            }
        }
    }

    // Filter + sort the flat session list first; grouping then buckets it
    // while preserving both orders (groups order by latest activity).
    val visible = remember(
        homeState.sessions, homeState.query, homeState.hideEmpty, homeState.sortBy,
    ) {
        homeState.sessions.orEmpty()
            .filter { s ->
                (!homeState.hideEmpty ||
                    s.hasUserMessages || !s.title.isNullOrBlank()) &&
                    (homeState.query.isBlank() ||
                        s.title.orEmpty().contains(homeState.query, true) ||
                        s.sessionId.contains(homeState.query, true))
            }
            .sortedWith(
                if (homeState.sortBy == HomeSort.Title) {
                    compareBy { it.title.orEmpty().lowercase() }
                } else {
                    compareByDescending { it.lastActivity.orEmpty() }
                },
            )
    }

    val groups = remember(visible) {
        visible.groupBy { projectKeyOf(it.cwd) }
            .map { (key, list) ->
                ProjectGroup(
                    key = key,
                    label = projectLabelOf(list.first().cwd),
                    cwd = list.first().cwd,
                    sessions = list,
                )
            }
            .sortedByDescending {
                it.sessions.maxOfOrNull { s -> s.lastActivity.orEmpty() } ?: ""
            }
    }

    // Expansion: user overrides win; otherwise the open/last session's group
    // plus the three most recently active groups start expanded. While a
    // search query is active every matching group shows expanded (not
    // persisted — toggles still write the real set).
    val defaultExpanded = remember(groups, ui.sessionId, drawerMode, prefs.lastSessionId) {
        val keys = groups.take(3).map { it.key }.toMutableSet()
        val targetId = if (drawerMode) ui.sessionId else prefs.lastSessionId
        groups.firstOrNull { g -> g.sessions.any { it.sessionId == targetId } }
            ?.let { keys += it.key }
        keys
    }
    val effectiveExpanded = if (homeState.query.isNotBlank()) {
        groups.map { it.key }.toSet()
    } else {
        expandedProjects ?: defaultExpanded
    }
    fun toggleProject(key: String) {
        val base = expandedProjects ?: defaultExpanded
        val next = base.toMutableSet()
        if (!next.add(key)) next.remove(key)
        onExpandedChange(next)
    }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        // Wide screens: content column caps at 720dp, centered.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize(),
        ) {
            HomeHeader(
                host = host,
                configured = configured,
                lastLoadOk = lastLoadOk,
                tailnet = tailnetState.takeIf { tailnetRouted },
                searchOpen = homeState.searchOpen,
                query = homeState.query,
                onToggleSearch = { homeState.searchOpen = !homeState.searchOpen },
                onQueryChange = { homeState.query = it },
                onScanPair = { pairOpen = true },
                onOpenSettings = { settingsOpen = true },
            )
            // Session list states.
            val state = when {
                !configured -> "notConfigured"
                homeState.sessions == null && sessionsLoading -> "loading"
                sessionsError != null && homeState.sessions.isNullOrEmpty() -> "error"
                homeState.sessions?.isEmpty() == true -> "empty"
                visible.isEmpty() -> "noMatch"
                else -> "list"
            }
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                },
                label = "homeState",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { s ->
                when (s) {
                    "notConfigured" -> HomeNotConfigured(
                        onOpenSettings = { settingsOpen = true },
                    )
                    "loading" -> SessionSkeletons()
                    "error" -> HomeError(
                        error = sessionsError.orEmpty(),
                        onRetry = { loadSessions() },
                    )
                    "empty", "noMatch" -> HomeEmpty(noMatch = s == "noMatch")
                    else -> PullToRefreshBox(
                        isRefreshing = pullRefreshing,
                        onRefresh = { loadSessions() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = homeState.listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp,
                            ),
                        ) {
                            groups.forEach { group ->
                                val expanded = group.key in effectiveExpanded
                                item(
                                    key = "g:${group.key}",
                                    contentType = "gheader",
                                ) {
                                    ProjectGroupHeader(
                                        group = group,
                                        expanded = expanded,
                                        onToggle = { toggleProject(group.key) },
                                        onNewSession = {
                                            if (drawerMode) onNewSessionCwd(group.cwd)
                                            else startConnect(null, group.cwd)
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                                if (expanded) {
                                    val cap = if (group.key in showAllGroups) {
                                        group.sessions.size
                                    } else {
                                        5
                                    }
                                    group.sessions.take(cap).forEach { sess ->
                                        item(
                                            key = "s:${sess.sessionId}",
                                            contentType = "session",
                                        ) {
                                            val isCurrent = drawerMode &&
                                                sess.sessionId == ui.sessionId
                                            SessionRow(
                                                s = sess,
                                                compact = homeState.compact,
                                                isCurrent = isCurrent,
                                                switching = openTarget?.sessionId ==
                                                    sess.sessionId,
                                                badge = badges[sess.sessionId],
                                                lastSession =
                                                    sess.sessionId == prefs.lastSessionId,
                                                canDelete = !isCurrent,
                                                onClick = {
                                                    if (sess.isLocked) {
                                                        scope.launch {
                                                            snackbarHostState.showSnackbar(
                                                                "该会话正被其他客户端占用，可能无法恢复",
                                                            )
                                                        }
                                                    }
                                                    if (drawerMode) onPickSession(sess)
                                                    else startConnect(
                                                        sess.sessionId,
                                                        sess.cwd,
                                                    )
                                                },
                                                onDelete = { removeSessionLocal(sess) },
                                                modifier = Modifier.animateItem(
                                                    fadeInSpec = tween(180),
                                                    placementSpec =
                                                        androidx.compose.animation.core
                                                            .spring(
                                                                stiffness = androidx.compose
                                                                    .animation.core
                                                                    .Spring.StiffnessMediumLow,
                                                            ),
                                                    fadeOutSpec = tween(120),
                                                ),
                                            )
                                        }
                                    }
                                    val remaining = group.sessions.size - cap
                                    if (remaining > 0) {
                                        item(
                                            key = "more:${group.key}",
                                            contentType = "more",
                                        ) {
                                            MoreSessionsRow(
                                                remaining = remaining,
                                                onClick = {
                                                    showAllGroups = showAllGroups + group.key
                                                },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Bottom bar floating over the list.
            HomeBottomBar(
                busy = busy,
                configured = configured,
                // Short phase words only — the long statusText stays in the
                // floating card above the bar.
                statusText = when {
                    openTarget?.resumeId != null -> "正在切换…"
                    ui.phase == Phase.Authenticating -> "登录中…"
                    ui.phase == Phase.StartingSession -> "创建会话…"
                    ui.phase == Phase.Checking ||
                        ui.phase == Phase.Connecting -> "连接中…"
                    else -> ""
                },
                sortBy = homeState.sortBy,
                hideEmpty = homeState.hideEmpty,
                compact = homeState.compact,
                filterPage = filterMenu,
                onFilterPage = { filterMenu = it },
                onSort = { homeState.sortBy = it },
                onHideEmpty = { homeState.hideEmpty = it },
                onCompact = { homeState.compact = it },
                onNewSession = {
                    if (drawerMode) onNewSession() else startConnect()
                },
                newSessionLoading = busy ||
                    (openTarget != null && openTarget?.resumeId == null),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
            )
        }
        // Browser-login wait card floats above the bottom bar.
        if (ui.awaitingBrowserLogin) {
            LoginWaitCard(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 76.dp),
            )
        }
        DevinSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (pairOpen) {
            QrPairingDialog(
                onPaired = { info -> pairOpen = false; applyPair(info) },
                onDismiss = { pairOpen = false },
            )
        }
        // Settings page slides over Home.
        AnimatedVisibility(
            visible = settingsOpen,
            enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
        ) {
            SettingsScreen(
                vm = vm,
                prefs = prefs,
                onBack = { settingsOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---- header -----------------------------------------------------------------------

@Composable
private fun HomeHeader(
    host: String,
    configured: Boolean,
    lastLoadOk: Boolean?,
    tailnet: TailnetState?,
    searchOpen: Boolean,
    query: String,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onScanPair: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(Hairline, cs.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    DevinLogo(size = 26.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "DevinGo",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Routed via the embedded node → the dot follows node
                    // state; otherwise it keeps reporting the last list load.
                    val dotColor = if (tailnet != null) {
                        when (tailnet) {
                            is TailnetState.Running -> cs.secondary
                            is TailnetState.Failed,
                            is TailnetState.Unsupported -> cs.error
                            else -> DevinGoColors.statusOrange
                        }
                    } else {
                        when (lastLoadOk) {
                            true -> cs.secondary
                            false -> cs.error
                            null -> cs.outline
                        }
                    }
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (tailnet != null) "Tailscale · $host"
                        else if (configured) host
                        else "未配置 bridge",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search, "搜索会话",
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSurface,
                )
            }
            IconButton(onClick = onScanPair) {
                Icon(
                    HugeIcons.QrCode01, "扫码连接",
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSurface,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    HugeIcons.Settings02, "设置",
                    modifier = Modifier.size(22.dp),
                    tint = cs.onSurface,
                )
            }
        }
        AnimatedVisibility(
            visible = searchOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                placeholder = {
                    Text(
                        "搜索会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.outline,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.onSurface,
                    unfocusedBorderColor = cs.outlineVariant,
                    focusedContainerColor = cs.surfaceContainerLowest,
                    unfocusedContainerColor = cs.surfaceContainerLowest,
                ),
            )
        }
    }
}

// ---- list states -------------------------------------------------------------------

@Composable
private fun HomeNotConfigured(onOpenSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DevinLogo(size = 56.dp)
        Spacer(Modifier.height(20.dp))
        Text("连接你的电脑", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            "填写 bridge 地址后即可在手机上操作 Devin",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        BlackPillButton("去设置", onClick = onOpenSettings)
    }
}

@Composable
private fun SessionSkeletons() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        repeat(5) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceContainer)
                        .shimmer(),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.35f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.surfaceContainer)
                        .shimmer(),
                )
            }
        }
    }
}

@Composable
private fun HomeError(error: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法连接 bridge", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = cs.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinePillButton("重试", onClick = onRetry)
    }
}

@Composable
private fun HomeEmpty(noMatch: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (noMatch) "没有匹配的会话" else "还没有会话",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (noMatch) "换个关键词试试。" else "你开始的会话会显示在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

/**
 * session/list filters by exact string match against Devin's stored cwd, which
 * uses backslashes for Windows drive paths; a forward-slash path matches nothing.
 */
private fun canonicalSessionCwd(cwd: String): String {
    val t = cwd.trim()
    return if (WINDOWS_DRIVE_PATH.containsMatchIn(t)) t.replace('/', '\\') else t
}

/** Group key: canonical cwd, separators stripped, lowercased; "" = 其他. */
private fun projectKeyOf(cwd: String?): String =
    canonicalSessionCwd(cwd ?: "").trim().trimEnd('/', '\\').lowercase()

/** Group label: last path segment of the original cwd, else the cwd itself. */
private fun projectLabelOf(cwd: String?): String {
    val t = cwd?.trim()?.trimEnd('/', '\\').orEmpty()
    if (t.isEmpty()) return "其他"
    return t.replace('\\', '/').split('/').lastOrNull { it.isNotBlank() } ?: t
}

private val WINDOWS_DRIVE_PATH = Regex("""^[A-Za-z]:[/\\]""")
