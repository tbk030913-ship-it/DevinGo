package com.devingo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.snapshotFlow
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
import com.devingo.app.Phase
import com.devingo.app.UiState
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
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
    val revertSteps by vm.revertSteps.collectAsState()
    val pendingPermission by vm.pendingPermission.collectAsState()
    // Draft + per-turn timings live in the session store — they survive
    // switching away and back, which Compose-local state never did.
    val input by vm.draft.collectAsState()
    val attachments by vm.draftAttachments.collectAsState()
    val turnTimes by vm.turnTimes.collectAsState()
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
    // Per-turn inline work-log expansion; survives scrolling via the map.
    val expandedWork = remember(ui.sessionId) { mutableStateMapOf<Long, Boolean>() }

    // Back returns to Home WITHOUT disconnecting — the session's store keeps
    // streaming and its row shows running/unread badges. An open sheet
    // consumes back first (dismisses itself).
    BackHandler(enabled = !modelSheet && !infoSheet && revertSteps == null) {
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
    // Scroll-to-bottom button: visible while the transcript isn't at bottom.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.firstOrNull()?.index == 0 &&
                info.visibleItemsInfo.firstOrNull()?.offset == 0
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
            onOpenDrawer = onOpenDrawer,
            onOpenModel = { modelSheet = true },
            onOpenInfo = { infoSheet = true },
            onRevert = vm::loadRevertSteps,
            onDisconnect = vm::disconnect,
        )
        // The socket's condition rides its own slim banner — it never
        // swaps the page back to a status screen (U6).
        ConnBanner(ui)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.itemById.isEmpty()) {
                ChatEmptyState(hasCommands = ui.commands.isNotEmpty())
            } else {
                Transcript(
                    model = model,
                    tail = tail,
                    ui = ui,
                    pendingPermission = pendingPermission,
                    timings = turnTimes,
                    expandedWork = expandedWork,
                    onToggleWork = { key ->
                        expandedWork[key] = expandedWork[key] != true
                    },
                    onUserLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.loadRevertSteps()
                    },
                    onResend = { vm.resend(it) },
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ScrollToBottomButton(
                visible = model.itemById.isNotEmpty() && !atBottom,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
        // Context warning chip once usage passes 80% — hidden while a
        // permission card owns the bottom slot.
        if (contextFrac >= 0.8f && ui.contextSize > 0 && pendingPermission == null) {
            ContextChip(
                pct = contextFrac,
                onClick = { infoSheet = true },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 6.dp),
            )
        }
        // Slash-command popup above the composer pill — hidden while the
        // permission card is up (it owns the popup slot).
        val showCommands = remember(ui.commands, input, pendingPermission) {
            pendingPermission == null && commandMatches(ui, input).isNotEmpty()
        }
        AnimatedVisibility(
            visible = showCommands,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .padding(horizontal = 16.dp),
            enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = fadeOut() + androidx.compose.animation.shrinkVertically(),
        ) {
            ComposerPopup {
                CommandSuggestions(ui, input) { vm.setDraft(it) }
            }
        }
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
        Composer(
            ui = ui,
            input = input,
            attachments = attachments,
            queued = queuedPrompts,
            permissionPending = pendingPermission != null,
            onInputChange = vm::setDraft,
            onSend = {
                if (vm.send(input, attachments)) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.setDraft("")
                    vm.setDraftAttachments(emptyList())
                }
            },
            onSteer = {
                vm.steer(input)
                vm.setDraft("")
            },
            onAttach = { vm.setDraftAttachments(attachments + it) },
            onRemoveAttachment = { vm.setDraftAttachments(attachments - it) },
            onCancelQueued = vm::cancelQueued,
            onStop = vm::cancelTurn,
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
            onPick = vm::forkFromStep,
        )
    }
}

// ---- top bar ---------------------------------------------------------------------

@Composable
private fun ChatTopBar(
    ui: UiState,
    canScrollUp: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenInfo: () -> Unit,
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
                        DevinMenuItem("回退 / 分叉", onClick = {
                            menuOpen = false
                            onRevert()
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

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    // AnimatedVisibility lives here (not at the call site) so the plain
    // top-level overload resolves — a ColumnScope receiver would otherwise
    // shadow it inside the transcript Box.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            modifier = Modifier
                .size(40.dp)
                .pressScale(interaction),
            shape = CircleShape,
            color = cs.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(Hairline, cs.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    HugeIcons.ChevronDown, "滚动到底部",
                    modifier = Modifier.size(20.dp),
                    tint = cs.onSurface,
                )
            }
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
