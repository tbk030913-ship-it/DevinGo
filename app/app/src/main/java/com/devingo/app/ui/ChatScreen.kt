package com.devingo.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.AppVisibility
import com.devingo.app.ChatViewModel
import com.devingo.app.CitationAttachment
import com.devingo.app.Phase
import com.devingo.app.RowKind
import com.devingo.app.UiState
import com.devingo.app.ui.diff.ReviewRequest
import com.devingo.app.ui.diff.ReviewSheet
import com.devingo.app.ui.diff.formatReviewCitation
import com.devingo.app.ui.terminal.PreviewSheet
import com.devingo.app.ui.terminal.TerminalSheet
import com.devingo.app.ui.terminal.detectPreviewPort
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.Diff
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreHorizontal

/**
 * Devin chat surface: top bar (drawer chevron, title+model, more menu),
 * reverseLayout transcript, context chip + composer, and all sheets.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    // Row model + streaming tail arrive prebuilt — no rebuild on the
    // main thread when a chunk lands.
    val model by vm.transcript.collectAsState()
    val tail by vm.streamingTail.collectAsState()
    val queuedPrompts by vm.queuedPrompts.collectAsState()
    val editingQueuedId by vm.editingQueuedId.collectAsState()
    val revertSteps by vm.revertSteps.collectAsState()
    val pendingPermission by vm.pendingPermission.collectAsState()
    val pendingElicitation by vm.pendingElicitation.collectAsState()
    // A7.6: the secret card + background-shell sheet feeds + `!` composer cap.
    val pendingSecret by vm.pendingSecret.collectAsState()
    val backgroundShells by vm.backgroundShells.collectAsState()
    val userShellCommands by vm.userShellCommands.collectAsState()
    // Draft + per-turn timings live in the session store — they survive
    // switching away and back, which Compose-local state never did.
    val input by vm.draft.collectAsState()
    val attachments by vm.draftAttachments.collectAsState()
    val citation by vm.draftCitation.collectAsState()
    val turnTimes by vm.turnTimes.collectAsState()
    // A7.3: the @-picker's capability gating + 会话 source.
    val agentCaps by vm.agentCaps.collectAsState()
    val sessions by vm.sessions.collectAsState()
    // A7.4: the session's subagents — capsule count, work-log cards, sheets.
    val subagents by vm.subagents.collectAsState()
    val numLiveSubagents by vm.numLiveSubagents.collectAsState()
    // Keyed on the bound session — the page stays Page.Chat across a
    // session switch, so without the key scroll position and expansions
    // would leak into the next session's transcript.
    val listState = rememberSaveable(ui.sessionId, saver = LazyListState.Saver) {
        LazyListState()
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var modelSheet by remember { mutableStateOf(false) }
    var infoSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var citationSheet by remember { mutableStateOf(false) }
    // A7.6: the 后台命令 sheet — session-keyed so a switch drops it.
    var backgroundSheet by remember(ui.sessionId) { mutableStateOf(false) }
    // W8.1: the 文件 sheet + source viewer — session-keyed like the rest.
    var filesSheet by remember(ui.sessionId) { mutableStateOf(false) }
    var viewerTarget by remember(ui.sessionId) { mutableStateOf<SourceTarget?>(null) }
    // N6.1: which review the sheet shows — a turn scope, or the session's.
    var reviewRequest by remember { mutableStateOf<ReviewRequest?>(null) }
    // N6.4: terminal + preview sheets — keyed by session so a session
    // switch drops the console socket along with the sheet.
    var terminalOpen by remember(ui.sessionId) { mutableStateOf(false) }
    var previewOpen by remember(ui.sessionId) { mutableStateOf(false) }
    // Scanned at open time, not per-frame: newest localhost/127.0.0.1 port
    // the transcript mentions (N6.4).
    var previewPort by remember(ui.sessionId) { mutableStateOf<Int?>(null) }
    // Per-turn inline work-log expansion; survives scrolling via the map.
    val expandedWork = remember(ui.sessionId) { mutableStateMapOf<Long, Boolean>() }
    // A7.4: the capsule opens the list sheet; a work-log/capsule row opens
    // the agent's detail sheet. Both keyed by session — a switch drops them.
    var subagentListOpen by remember(ui.sessionId) { mutableStateOf(false) }
    var detailSubagentId by remember(ui.sessionId) { mutableStateOf<String?>(null) }
    // Match on the stable toolCallId too — the entry's `id` upgrades when a
    // late frame names the wire agentId, and a sheet keyed on the old id
    // mustn't detach mid-view.
    val detailSubagent = subagents.firstOrNull {
        it.id == detailSubagentId || it.toolCallId == detailSubagentId
    }

    // Back returns to Home WITHOUT disconnecting — the session's store keeps
    // streaming and its row shows running/unread badges. An open sheet
    // consumes back first (dismisses itself).
    BackHandler(
        enabled = !modelSheet && !infoSheet && !queueSheet && !citationSheet &&
            !backgroundSheet && !filesSheet && viewerTarget == null &&
            !subagentListOpen && detailSubagent == null &&
            reviewRequest == null && revertSteps == null,
    ) {
        vm.leaveSession()
    }

    // Notification alerts stay quiet only while the chat is actually on
    // screen — and only for the session that's on screen.
    DisposableEffect(ui.sessionId) {
        AppVisibility.chatVisible = true
        AppVisibility.chatSessionId = ui.sessionId
        onDispose {
            AppVisibility.chatVisible = false
            AppVisibility.chatSessionId = null
        }
    }

    val cs = MaterialTheme.colorScheme
    val canScrollUp by remember {
        derivedStateOf { listState.canScrollBackward }
    }
    // Scroll-to-bottom: "at bottom" is first-visible == row 0 at offset 0
    // (reverseLayout); an empty list is trivially at bottom.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.isEmpty() ||
                (info.visibleItemsInfo.firstOrNull()?.index == 0 &&
                    info.visibleItemsInfo.firstOrNull()?.offset == 0)
        }
    }
    // Auto-scroll only on a NEW user message (spec §P — no per-chunk scroll).
    var lastUserId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(model.lastUserId) {
        val id = model.lastUserId
        if (id != null && id != lastUserId) {
            lastUserId = id
            listState.animateScrollToItem(0)
        }
    }
    val contextFrac = if (ui.contextSize > 0) {
        ui.contextUsed.toFloat() / ui.contextSize
    } else 0f

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surface),
        // Wide screens: children narrower than the cap center horizontally
        // (composer, popups, permission card) instead of hugging the left.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChatTopBar(
            ui = ui,
            canScrollUp = canScrollUp,
            diffStats = model.diffs.takeIf { it.files.isNotEmpty() }
                ?.let { it.additions to it.deletions },
            onOpenDrawer = onOpenDrawer,
            onOpenModel = { modelSheet = true },
            onOpenInfo = { infoSheet = true },
            onOpenReview = { reviewRequest = ReviewRequest.Session() },
            onOpenTerminal = { terminalOpen = true },
            onOpenBackground = { backgroundSheet = true },
            onOpenFiles = { filesSheet = true },
            onOpenPreview = {
                previewPort = detectPreviewPort(model, tail)
                previewOpen = true
            },
            onRevert = vm::loadRevertSteps,
            onDisconnect = vm::disconnect,
        )
        // The socket's condition rides its own slim banner — it never
        // swaps the page back to a status screen (U6).
        ConnBanner(ui)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A7.4: the wire's numLiveSubagents wins once reported (it sees
            // agents the local list never parsed); else the list's live count.
            val subagentCount = if (numLiveSubagents >= 0) {
                numLiveSubagents
            } else {
                subagents.count { it.live }
            }
            // Mirrors FloatingWorkingControl's `hasCapsule || showScrollToEnd`
            // — the running/scroll segments only appear while !atBottom, so a
            // single `!atBottom` term covers them both.
            val capsuleShown =
                ui.reconnecting || ui.phase == Phase.Error ||
                    queuedPrompts.isNotEmpty() || subagentCount > 0 || !atBottom
            val capsuleInset by animateDpAsState(
                targetValue = if (capsuleShown) 48.dp else 0.dp,
                animationSpec = if (isReducedMotion()) snap() else tween(240),
                label = "capsuleInset",
            )
            if (model.itemById.isEmpty() && queuedPrompts.isEmpty()) {
                ChatEmptyState(hasCommands = ui.commands.isNotEmpty())
            } else {
                Transcript(
                    model = model,
                    tail = tail,
                    ui = ui,
                    pendingPermission = pendingPermission,
                    pendingElicitation = pendingElicitation,
                    pendingSecret = pendingSecret,
                    timings = turnTimes,
                    expandedWork = expandedWork,
                    queued = queuedPrompts,
                    onToggleWork = { key ->
                        expandedWork[key] = expandedWork[key] != true
                    },
                    // N6.1: diff pills + DiffCard's 全屏审查 open the native
                    // review sheet scoped to that turn/file.
                    onReviewDiff = { key, path ->
                        reviewRequest = ReviewRequest.Turn(key, path)
                    },
                    onUserLongPress = {
                        if (ui.revertSupported) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.loadRevertSteps()
                        }
                    },
                    onResend = { vm.resend(it) },
                    onEditQueued = { vm.beginQueuedEdit(it) },
                    // Message-level quote: the whole markdown pins to the
                    // draft and the sheet opens for trim/comment (U5.5).
                    onQuote = { item ->
                        vm.setDraftCitation(
                            CitationAttachment(
                                messageId = item.id,
                                quotedText = item.text,
                            ),
                        )
                        citationSheet = true
                    },
                    onSavePlan = { vm.savePlanToWorkspace(planMarkdown(it.planEntries)) },
                    // A7.4: the turn-scoped cards; a row opens the detail sheet.
                    subagents = subagents,
                    subagentActionsEnabled = ui.subagentSupported,
                    onSubagentOpen = { detailSubagentId = it.id },
                    onSubagentBackground = { vm.subagentBackground(it.id) },
                    onSubagentForeground = { vm.subagentForeground(it.id) },
                    onSubagentCancel = { vm.subagentCancel(it.id) },
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Keep the status line above the floating capsule — the
                    // overlay covers ~48dp whenever it's showing (U5.4).
                    bottomInset = capsuleInset,
                )
            }
            // U5.4: the floating work capsule replaces the lone ↓ button —
            // connection/running status + queue + subagent stub on the left,
            // scroll-to-end sliding out on the right.
            FloatingWorkingControl(
                reconnecting = ui.reconnecting,
                connectionFailed = ui.phase == Phase.Error,
                // A live-socket error (attach/open failure) can't be retried
                // via kickReconnect — only offer the tap when the socket's
                // actually down or a backoff wait is parked.
                retryEnabled = ui.reconnecting || !ui.connected,
                reconnectRetryAtMs = ui.reconnectRetryAtMs,
                statusText = ui.statusText.ifBlank { ui.error.orEmpty() },
                running = ui.running && !atBottom,
                runningDetail = model.runningDetail,
                turnStartedAtMs = turnTimes[model.lastUserId]?.start ?: 0L,
                queuedCount = queuedPrompts.size,
                // A7.4: the store's subagent list, wire-reported live count.
                subagentCount = subagentCount,
                showScrollToEnd = !atBottom && model.itemById.isNotEmpty(),
                onReconnect = vm::reconnectNow,
                onStop = vm::cancelTurn,
                onOpenQueue = { queueSheet = true },
                // A single agent skips the list — straight to its detail.
                // The badge counts the wire's live figure, so branch only
                // when both agree there's exactly one to open.
                onOpenSubagents = {
                    if (subagents.size == 1 && subagentCount == 1) {
                        detailSubagentId = subagents.first().id
                    } else {
                        subagentListOpen = true
                    }
                },
                onScrollToEnd = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }
        // Context warning chip once usage passes 80% — hidden while a
        // permission/elicitation/secret card owns the bottom slot.
        if (contextFrac >= 0.8f && ui.contextSize > 0 &&
            pendingPermission == null && pendingElicitation == null &&
            pendingSecret == null
        ) {
            ContextChip(
                pct = contextFrac,
                onClick = { infoSheet = true },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp),
            )
        }
        // The `/`+`@` popover moved inside the composer's popup slot (A7.3).
        // Inline permission card takes the popup slot above the composer.
        PermissionCard(
            pending = pendingPermission,
            onResolve = vm::resolvePermission,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
        // A7.2: the elicitation card takes the same slot when Devin asks
        // questions (FIFO with permissions — they're independent queues).
        PendingUserInputCard(
            pending = pendingElicitation,
            running = ui.running,
            onResolve = vm::resolveElicitation,
            onStop = vm::cancelTurn,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
        // A7.6: the secret card shares the bottom slot — save/decline goes
        // back over `_cognition.ai/secret/create`.
        SecretRequestCard(
            pending = pendingSecret,
            onResolve = vm::resolveSecret,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        )
        Composer(
            ui = ui,
            input = input,
            attachments = attachments,
            citation = citation,
            queueEditing = editingQueuedId != null,
            permissionPending = pendingPermission != null,
            onInputChange = vm::setDraft,
            onSend = {
                if (editingQueuedId != null) {
                    // The composer doubles as the queue editor — save
                    // rewrites the parked prompt, nothing is sent.
                    vm.saveQueuedEdit()
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                } else if (vm.send()) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.setDraft("")
                    vm.setDraftAttachments(emptyList())
                }
            },
            onSteer = {
                vm.steer()
                vm.setDraft("")
            },
            onAttach = { vm.setDraftAttachments(attachments + it) },
            onRemoveAttachment = { vm.setDraftAttachments(attachments - it) },
            onCitationClick = { citationSheet = true },
            onCitationRemove = { vm.setDraftCitation(null) },
            onOpenModel = { modelSheet = true },
            onSelectMode = vm::setMode,
            onCancelQueuedEdit = vm::cancelQueuedEdit,
            onStop = vm::cancelTurn,
            onFetchFiles = vm::fetchWorkspaceFiles,
            agentCaps = agentCaps,
            sessions = sessions.orEmpty(),
            onSearchWorkspace = vm::searchWorkspace,
            onMentionResources = vm::mentionResources,
            onMentionInsert = vm::registerMentionRef,
            userShellCommands = userShellCommands,
        )
    }
    if (backgroundSheet) {
        BackgroundWorkSheet(
            shells = backgroundShells,
            onKill = vm::killBackgroundShell,
            onDismiss = { backgroundSheet = false },
        )
    }
    // W8.1: the workspace tree — a file tap opens the source viewer.
    if (filesSheet) {
        WorkspaceFilesSheet(
            onSearch = vm::searchWorkspace,
            onListDir = vm::listWorkspaceDir,
            onOpenFile = { viewerTarget = SourceTarget(it) },
            onDismiss = { filesSheet = false },
        )
    }
    viewerTarget?.let { t ->
        val toastCtx = LocalContext.current
        SourceFileViewer(
            target = t,
            onLoad = vm::readWorkspaceFile,
            onQuote = { path, lo, hi ->
                // `@file:` token verbatim — a path with spaces needs the
                // verbatim hint the @-picker also registers (N6.2).
                val ref = if (lo == hi) "$path#L$lo" else "$path#L$lo-L$hi"
                vm.registerMentionRef(ref)
                val cur = input
                val sep = if (cur.isEmpty() || cur.last().isWhitespace()) "" else " "
                vm.setDraft("$cur$sep@file:$ref ")
                viewerTarget = null
                filesSheet = false
                Toast.makeText(toastCtx, "已加入草稿", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { viewerTarget = null },
        )
    }
    if (modelSheet) {
        ModelSheet(
            ui = ui,
            onSelectMode = vm::setMode,
            onSelectModel = vm::setModel,
            onSelectConfig = vm::setConfigOption,
            onDismiss = { modelSheet = false },
        )
    }
    if (infoSheet) {
        SessionInfoSheet(ui, vm.boundCwd, onDismiss = { infoSheet = false })
    }
    if (revertSteps != null) {
        RevertSheet(
            steps = revertSteps.orEmpty(),
            onDismiss = vm::dismissRevertSteps,
            onFork = vm::forkFromStep,
            onExecute = vm::revertExecute,
            fetchPreview = vm::revertPreview,
        )
    }
    if (queueSheet) {
        ThreadQueueSheet(
            queued = queuedPrompts,
            running = ui.running,
            editingId = editingQueuedId,
            onDismiss = { queueSheet = false },
            onSteer = vm::steerQueued,
            // t3's edit path also pops back to the composer.
            onEdit = {
                vm.beginQueuedEdit(it)
                queueSheet = false
            },
            onRemove = vm::cancelQueued,
        )
    }
    // A7.4: the capsule's list sheet + the per-agent detail sheet. The
    // detail resolves live off the store list so output keeps streaming in.
    if (subagentListOpen) {
        SubagentListSheet(
            subagents = subagents,
            actionsEnabled = ui.subagentSupported,
            onOpen = { detailSubagentId = it.id },
            onBackground = { vm.subagentBackground(it.id) },
            onForeground = { vm.subagentForeground(it.id) },
            onCancel = { vm.subagentCancel(it.id) },
            onDismiss = { subagentListOpen = false },
        )
    }
    detailSubagent?.let { agent ->
        // The live store flow wins while the child is attached; the
        // session-list snapshot covers a child we've never opened (the
        // flag can flip while this sheet is open either way).
        val childPermFlow = remember(agent.childSessionId) {
            agent.childSessionId?.let { vm.pendingPermissionFlowOf(it) }
        }
        val childPermLive = childPermFlow?.collectAsState()?.value
        SubagentDetailSheet(
            agent = agent,
            // Children surface as separate sessions — the hub routes their
            // `session/request_permission` to whoever subscribes them.
            childPendingApproval = if (childPermFlow != null) {
                childPermLive != null
            } else {
                sessions.orEmpty()
                    .firstOrNull { it.sessionId == agent.childSessionId }
                    ?.pendingPermission == true
            },
            actionsEnabled = ui.subagentSupported,
            onBackground = { vm.subagentBackground(agent.id) },
            onForeground = { vm.subagentForeground(agent.id) },
            onCancel = { vm.subagentCancel(agent.id) },
            onOpenChild = agent.childSessionId
                ?.let { sid -> { vm.openChildSession(sid) } },
            onDismiss = { detailSubagentId = null },
        )
    }
    val context = LocalContext.current
    reviewRequest?.let { req ->
        ReviewSheet(
            diffs = model.diffs,
            request = req,
            fetchGitDiff = vm::fetchGitDiff,
            onSubmitComment = { sel, comment ->
                // The first comment pins the citation slot; later ones fold
                // into the draft text — mergedWithCitation emits the
                // markdown quote at send either way.
                vm.addReviewCitation(
                    CitationAttachment(
                        messageId = -1L,
                        quotedText = formatReviewCitation(sel),
                        comment = comment,
                    ),
                )
                Toast.makeText(context, "已加入草稿", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { reviewRequest = null },
        )
    }
    // N6.4: terminal console + web preview for this session's workspace.
    if (terminalOpen) {
        val ep = vm.bridgeEndpoint()
        TerminalSheet(
            sessionId = ui.sessionId.orEmpty(),
            project = vm.boundCwd.substringAfterLast('/')
                .substringAfterLast('\\')
                .ifBlank { ui.sessionTitle.orEmpty() },
            httpBase = ep?.first.orEmpty(),
            token = ep?.second,
            onDismiss = { terminalOpen = false },
        )
    }
    if (previewOpen) {
        val ep = vm.bridgeEndpoint()
        PreviewSheet(
            port = previewPort,
            httpBase = ep?.first.orEmpty(),
            token = ep?.second,
            onDismiss = { previewOpen = false },
        )
    }
    val clipboard = LocalClipboard.current
    val reducedMotion = isReducedMotion()
    val c = citation
    if (citationSheet && c != null) {
        CitationSheet(
            citation = c,
            sourceText = model.itemById[c.messageId]?.text,
            locatable = model.rows.any { it is RowKind.Body && it.itemId == c.messageId },
            onChange = vm::setDraftCitation,
            onLocate = {
                val idx = model.rows.indexOfFirst {
                    it is RowKind.Body && it.itemId == c.messageId
                }
                citationSheet = false
                if (idx >= 0) {
                    scope.launch {
                        if (reducedMotion) {
                            listState.scrollToItem(idx)
                        } else {
                            listState.animateScrollToItem(idx)
                        }
                    }
                }
            },
            onCopy = {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("citation", c.quotedText)),
                    )
                }
            },
            onRemove = {
                vm.setDraftCitation(null)
                citationSheet = false
            },
            onDismiss = { citationSheet = false },
        )
    }
}

// ---- top bar ---------------------------------------------------------------------

@Composable
private fun ChatTopBar(
    ui: UiState,
    canScrollUp: Boolean,
    diffStats: Pair<Int, Int>?,
    onOpenDrawer: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTerminal: () -> Unit,
    /** A7.6: the background-shell sheet (user `!` + agent background). */
    onOpenBackground: () -> Unit,
    /** W8.1: the workspace files tree. */
    onOpenFiles: () -> Unit,
    onOpenPreview: () -> Unit,
    onRevert: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.statusBarsPadding().background(cs.surface)) {
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft, "会话列表",
                        modifier = Modifier.size(24.dp),
                        tint = cs.onSurface,
                    )
                }
                // Center: title + chevron over signal-bars + model name.
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onOpenModel)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.sessionTitle?.takeIf { it.isNotBlank() } ?: "新会话",
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                        Icon(
                            HugeIcons.ChevronDown, null,
                            modifier = Modifier.size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalBars()
                        Spacer(Modifier.width(4.dp))
                        val modelName = ui.models.firstOrNull {
                            it.modelId == ui.currentModelId
                        }?.name ?: ui.currentModelId ?: "模型"
                        // §C: "<model> · <effort>" once an effort option exists.
                        val subtitle = listOfNotNull(
                            modelName,
                            effortChoiceName(ui.extraConfigOptions),
                        ).joinToString(" · ")
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // N6.1: session diff badge — opens the review sheet. Only
                // rendered when the session has accumulated tool diffs.
                if (diffStats != null) {
                    IconButton(onClick = onOpenReview) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                HugeIcons.Diff, "审查改动",
                                modifier = Modifier.size(20.dp),
                                tint = cs.onSurface,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "+${diffStats.first} −${diffStats.second}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                // W8.1: the workspace file tree.
                IconButton(onClick = onOpenFiles) {
                    Icon(
                        HugeIcons.Folder01, "文件",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            HugeIcons.MoreHorizontal, "更多",
                            modifier = Modifier.size(22.dp),
                            tint = cs.onSurface,
                        )
                    }
                    DevinMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DevinMenuItem("会话信息", onClick = {
                            menuOpen = false
                            onOpenInfo()
                        })
                        // P4: agents without the revert surface get no entry.
                        if (ui.revertSupported) {
                            DevinMenuItem("回退 / 分叉", onClick = {
                                menuOpen = false
                                onRevert()
                            })
                        }
                        DevinMenuDivider()
                        // N6.4: terminal console + dev-server preview for
                        // the bound session's workspace.
                        DevinMenuItem("终端控制台", onClick = {
                            menuOpen = false
                            onOpenTerminal()
                        })
                        // A7.6: running/finished shell-manager terminals.
                        DevinMenuItem("后台命令", onClick = {
                            menuOpen = false
                            onOpenBackground()
                        })
                        // W8.1: the workspace file tree + viewer.
                        DevinMenuItem("文件", onClick = {
                            menuOpen = false
                            onOpenFiles()
                        })
                        DevinMenuItem("预览", onClick = {
                            menuOpen = false
                            onOpenPreview()
                        })
                        DevinMenuDivider()
                        DevinMenuItem(
                            "清除会话状态",
                            onClick = {
                                menuOpen = false
                                onDisconnect()
                            },
                            danger = true,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = canScrollUp) {
            HorizontalDivider(color = cs.outlineVariant, thickness = Hairline)
        }
    }
}

/** Devin's three-bar signal glyph: 2dp wide, heights 4/7/10. */
@Composable
private fun SignalBars(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier.size(width = 12.dp, height = 10.dp)) {
        val barW = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val heights = floatArrayOf(4.dp.toPx(), 7.dp.toPx(), 10.dp.toPx())
        heights.forEachIndexed { i, h ->
            drawRect(
                color = cs.onSurfaceVariant,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h),
            )
        }
    }
}

// ---- misc rows --------------------------------------------------------------------

/**
 * Slim connection banner pinned under the top bar (U6): while the socket
 * is down or coming back — connecting, reconnecting, dropped — plus an
 * attach failure on a live socket (the chat looks dead but isn't down) or
 * the attach handshake itself. Page state never depends on it.
 */
@Composable
private fun ConnBanner(ui: UiState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val content = when {
        ui.phase == Phase.Error ->
            (ui.statusText.ifBlank { ui.error ?: "连接已断开" }) to cs.error
        ui.reconnecting -> (ui.statusText.ifBlank { "正在重连…" }) to cs.outline
        !ui.connected -> (ui.statusText.ifBlank { "连接中…" }) to cs.outline
        // Socket live but the bound session isn't subscribed — mid-attach,
        // or an attach that failed quietly.
        ui.sessionId != null && !ui.attached ->
            (ui.statusText.ifBlank { "会话恢复中…" }) to cs.outline
        else -> null
    }
    AnimatedVisibility(visible = content != null, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                content?.first.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = content?.second ?: cs.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Context-usage pill above the composer; opens SessionInfoSheet. */
@Composable
private fun ContextChip(
    pct: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pctInt = (pct * 100).toInt().coerceIn(0, 100)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = cs.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(DevinGoColors.warning),
            )
            Text(
                "上下文已用 $pctInt%",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChatEmptyState(hasCommands: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DevinLogo(size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            "有什么可以帮你？",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (hasCommands) {
            Spacer(Modifier.height(8.dp))
            Text(
                "输入 / 查看命令",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
