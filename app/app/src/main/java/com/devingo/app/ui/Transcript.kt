package com.devingo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatItem
import com.devingo.app.Kind
import com.devingo.app.PendingPermission
import com.devingo.app.RowKind
import com.devingo.app.TranscriptModel
import com.devingo.app.Turn
import com.devingo.app.TurnTiming
import com.devingo.app.UiState
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

internal fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "${n / 1_000_000}M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}

private fun workDurationLabel(sec: Long): String = when {
    sec < 60 -> "工作了 $sec 秒"
    else -> "工作了 ${sec / 60} 分 ${sec % 60} 秒"
}

/**
 * The Devin transcript: reverseLayout LazyColumn fed bottom-to-top —
 * status line first, then each turn's WorkRow, body items (reversed) and
 * user bubble. All rows share animateItem motion.
 */
@Composable
internal fun Transcript(
    model: TranscriptModel,
    tail: ChatItem?,
    ui: UiState,
    pendingPermission: PendingPermission?,
    timings: Map<Long, TurnTiming>,
    expandedWork: Map<Long, Boolean>,
    onToggleWork: (Long) -> Unit,
    onUserLongPress: (ChatItem) -> Unit,
    onResend: (ChatItem) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // The row model arrives prebuilt (SessionStore, off the main thread) —
    // a tail item still streaming text overrides the model's copy below.
    val rows = model.rows
    val itemById = model.itemById
    val turnByKey = model.turnByKey
    val lastItemId = model.lastItemId

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 18.dp,
            vertical = 8.dp,
        ),
    ) {
        items(
            rows,
            key = { row ->
                when (row) {
                    is RowKind.Status -> "status"
                    is RowKind.Work -> "w${row.turnKey}"
                    is RowKind.Body -> "b${row.itemId}"
                    is RowKind.User -> "u${row.itemId}"
                }
            },
            contentType = { row ->
                when (row) {
                    is RowKind.Status -> "status"
                    is RowKind.Work -> "work"
                    is RowKind.Body -> itemById[row.itemId]?.kind?.name ?: "body"
                    is RowKind.User -> "user"
                }
            },
        ) { row ->
            // The LazyColumn stays full width so scrolling works anywhere;
            // item content centers inside a 720dp cap on wide screens.
            Box(
                Modifier
                    .animateItem(
                        fadeInSpec = tween(180),
                        // No spring repositioning: during streaming a new row
                        // would bounce the whole transcript every flush.
                        placementSpec = null,
                        fadeOutSpec = tween(120),
                    )
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    when (row) {
                        is RowKind.Status -> StatusLine(ui, pendingPermission)
                        is RowKind.User -> {
                            val m = itemById[row.itemId]
                                ?.let { if (tail?.id == it.id) tail else it }
                                ?: return@items
                            UserBubble(
                                m,
                                onLongPress = { onUserLongPress(m) },
                                onResend = { onResend(m) },
                            )
                        }
                        is RowKind.Body -> {
                            val m = itemById[row.itemId]
                                ?.let { if (tail?.id == it.id) tail else it }
                                ?: return@items
                            BodyItem(
                                m,
                                streaming = ui.running && m.id == lastItemId &&
                                    m.kind == Kind.Agent,
                            )
                        }
                        is RowKind.Work -> {
                            val turn = turnByKey[row.turnKey] ?: return@items
                            val timing = timings[turn.user?.id ?: turn.key]
                            WorkRow(
                                turn = turn,
                                tail = tail,
                                live = row.live,
                                timing = timing,
                                expanded = expandedWork[turn.key] == true,
                                onToggle = { onToggleWork(turn.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- status line ---------------------------------------------------------------

/** Always the last transcript item: hexagon + one-line state. */
@Composable
private fun StatusLine(ui: UiState, pendingPermission: PendingPermission?) {
    val cs = MaterialTheme.colorScheme
    val (text, hexColor, pulsing) = when {
        pendingPermission != null -> Triple(
            "Devin 正在等你授权",
            DevinGoColors.statusOrange,
            false,
        )
        ui.running -> Triple(
            "Devin 正在工作",
            DevinGoColors.statusOrange,
            true,
        )
        else -> Triple(
            "Devin 正在等待指令",
            DevinGoColors.statusOrange,
            false,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusHexagon(hexColor, pulsing = pulsing)
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(tween(150)) togetherWith fadeOut(tween(100)))
            },
            label = "statusText",
        ) { t ->
            Text(
                t,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- work row ------------------------------------------------------------------

/**
 * The single "> 工作了 N 秒" row closing each turn (or the live "工作中"
 * state). Tap expands the turn's steps + diff cards inline, right under the
 * header inside the same lazy item. The per-second timer lives here in a
 * leaf composable.
 */
@Composable
private fun WorkRow(
    turn: Turn,
    tail: ChatItem?,
    live: Boolean,
    timing: TurnTiming?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // A still-streaming tail item shows its newest text — the model's copy
    // is only refreshed on structural changes.
    val items = remember(turn.work, tail) {
        turn.work.map { if (it.id == tail?.id) tail else it }
    }
    val failed = turn.failed
    val (diffAdd, diffDel) = turn.diffStats
    val startedAt = timing?.start ?: 0L
    // Live elapsed seconds — ticks once per second while this row is live.
    // Seed from startedAt so a re-composed row never flashes 0:00.
    var elapsed by remember(live, startedAt) {
        mutableIntStateOf(
            if (live && startedAt > 0L) {
                ((System.currentTimeMillis() - startedAt) / 1000).toInt()
            } else {
                0
            },
        )
    }
    LaunchedEffect(live, startedAt) {
        if (!live || startedAt <= 0L) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        }
    }
    val currentStep = items.lastOrNull {
        it.kind == Kind.Tool && it.status == "in_progress"
    }?.text?.takeIf { it.isNotBlank() }
        ?: if (items.any { it.kind == Kind.Thought }) "思考中" else null

    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "workChevron",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.ChevronRight, null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRot),
                tint = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            if (live) {
                Text(
                    "工作中 · ${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).shimmer(),
                )
            } else {
                Text(
                    timing?.durationSec?.let { workDurationLabel(it) }
                        ?: "查看过程 · ${items.size} 步",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (failed > 0) {
                Text(
                    " · $failed 个失败",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.error,
                    maxLines = 1,
                )
            }
            if (diffAdd > 0) {
                Text(
                    " +$diffAdd",
                    style = MaterialTheme.typography.labelLarge,
                    color = DevinGoColors.diffAdd,
                    maxLines = 1,
                )
            }
            if (diffDel > 0) {
                Text(
                    " −$diffDel",
                    style = MaterialTheme.typography.labelLarge,
                    color = DevinGoColors.diffRemove,
                    maxLines = 1,
                )
            }
        }
        if (live && currentStep != null) {
            val isExec = items.lastOrNull {
                it.kind == Kind.Tool && it.status == "in_progress"
            }?.toolKind == "execute"
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                },
                label = "workStep",
            ) { step ->
                Text(
                    step,
                    style = (if (isExec) monoText(MaterialTheme.typography.bodySmall)
                    else MaterialTheme.typography.bodySmall),
                    color = cs.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            WorkLogColumn(items = items, live = live)
        }
    }
}

// ---- inline body items -----------------------------------------------------------

@Composable
private fun BodyItem(
    m: ChatItem,
    streaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (m.kind) {
        Kind.Agent -> AgentText(m, streaming, modifier)
        Kind.Error -> ErrorLine(m.text, modifier)
        Kind.System -> SystemLine(m.text, modifier)
        Kind.Plan -> PlanCard(m, modifier)
        else -> Unit // Thought/Tool never render inline.
    }
}

@Composable
private fun AgentText(m: ChatItem, streaming: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    MarkdownBlock(
        content = m.text,
        // MarkdownBlock pads its content 4dp horizontally internally; pull
        // the block back so agent text shares the transcript's left edge.
        modifier = modifier
            .fillMaxWidth()
            .offset(x = (-4).dp)
            .padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
        streaming = streaming,
    )
}

@Composable
private fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Warning, null,
            modifier = Modifier.size(16.dp),
            tint = cs.error,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.error,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SystemLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    )
}

/** Compact plan card: header + collapsible entry list. */
@Composable
private fun PlanCard(m: ChatItem, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val entries = m.planEntries
    if (entries.isEmpty()) return
    val done = entries.count {
        it.status?.contains("complete", true) == true ||
            it.status?.contains("done", true) == true
    }
    val allDone = done == entries.size
    var expanded by remember(m.id, allDone) { mutableStateOf(!allDone) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(
            Hairline, cs.outlineVariant,
        ),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "计划",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$done/${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                val rot by androidx.compose.animation.core.animateFloatAsState(
                    if (expanded) 90f else 0f,
                    label = "planChevron",
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight, null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rot),
                    tint = cs.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = expanded,
                enter = androidx.compose.animation.expandVertically() +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() +
                    androidx.compose.animation.fadeOut(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    entries.forEach { e ->
                        val status = e.status.orEmpty().lowercase()
                        val state = when {
                            status.contains("complete") || status.contains("done") -> 2
                            status.contains("progress") -> 1
                            else -> 0
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(Modifier.padding(top = 5.dp)) {
                                when (state) {
                                    2 -> Icon(
                                        Icons.Filled.Check, null,
                                        modifier = Modifier.size(12.dp),
                                        tint = cs.secondary,
                                    )
                                    1 -> StatusHexagon(
                                        DevinGoColors.statusOrange,
                                        size = 9.dp,
                                        pulsing = true,
                                    )
                                    else -> Box(
                                        Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(cs.outlineVariant),
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                e.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state == 2) cs.onSurfaceVariant
                                else cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- user bubble -----------------------------------------------------------------

/** Right-aligned user message; long text collapses behind 展开. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    m: ChatItem,
    onLongPress: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var expanded by remember(m.id) { mutableStateOf(false) }
    var truncated by remember(m.id) { mutableStateOf(false) }
    // The item holds raw text; the "[N 张图片]" suffix is render-only and
    // reads attachmentCount — the bytes themselves are cleared post-send.
    val label = when {
        m.attachmentCount == 0 -> m.text
        m.text.isEmpty() -> "[${m.attachmentCount} 张图片]"
        else -> "${m.text} [${m.attachmentCount} 张图片]"
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        // 82%-wide slot, content hugs the bubble and right-aligns inside it.
        Box(Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                ),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (m.failed) cs.errorContainer else cs.surfaceContainer,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        maxLines = if (expanded) Int.MAX_VALUE else 12,
                        overflow = if (expanded) {
                            TextOverflow.Clip
                        } else {
                            TextOverflow.Ellipsis
                        },
                        onTextLayout = { truncated = it.hasVisualOverflow },
                    )
                }
                if (truncated && !expanded) {
                    Text(
                        "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = DevinGoColors.link,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clickable { expanded = true }
                            .padding(top = 4.dp, end = 4.dp),
                    )
                }
                if (m.failed) {
                    Row(
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Warning, null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.error,
                        )
                        Text(
                            "未发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.error,
                        )
                        Text(
                            "重发",
                            style = MaterialTheme.typography.labelSmall,
                            color = DevinGoColors.link,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clickable { onResend() },
                        )
                    }
                }
            }
        }
    }
}
