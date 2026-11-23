package com.devingo.app.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatItem
import com.devingo.app.Kind
import com.devingo.app.PendingPermission
import com.devingo.app.QueuedPrompt
import com.devingo.app.RowKind
import com.devingo.app.TranscriptModel
import com.devingo.app.Turn
import com.devingo.app.TurnTiming
import com.devingo.app.UiState
import com.devingo.app.WorkEntry
import com.devingo.app.ui.theme.DevinGoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnForward
import me.rerere.hugeicons.stroke.Brain
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.PencilEdit01
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

// ---- live thought preview ------------------------------------------------------
// Text shapers ported 1:1 from t3code (MIT):
//   .research/t3code-20261123/packages/client-runtime/src/work-log/presentation.ts
//     (liveThoughtLine)
//   .research/t3code-20261123/apps/mobile/src/lib/threadActivity.ts
//     (compactWorkEntryText, WORK_LOG_LABEL_MAX_LENGTH)
//   .research/t3code-20261123/packages/shared/src/String.ts (truncate)

private val ThoughtHeading =
    Regex("^\\s*\\*\\*([^*\r\n]+)\\*\\*[ \t]*\r?(?:\n|$)")
private val ThoughtLink = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val ThoughtMarks =
    Regex("^[ \t]*(?:#{1,6}|[-*+]|\\d+\\.)[ \t]+", RegexOption.MULTILINE)
private val ThoughtEmphasis = Regex("`+|\\*\\*|~~")
private val ThoughtItalic = Regex("(^|[^\\w*])[*_]([^*_\n]+)[*_](?![\\w*])")
private val CompactedWs = Regex("\\s+")
private val ThoughtSentenceEnd =
    Regex("[.?!][\"'”’)]?(?=\\s)|[。！？][\"'”’）」』]?")

private const val THOUGHT_PREVIEW_MAX_LENGTH = 200

/**
 * The line a live thought preview shows: a leading `**bold**` heading
 * wins outright; otherwise markdown is flattened and the text cuts at the
 * first sentence end (half- or full-width punctuation).
 */
private fun liveThoughtLine(markdown: String): String {
    val heading = ThoughtHeading.find(markdown)?.groupValues?.get(1)
    val text = (heading ?: markdown)
        .replace(ThoughtLink, "$1")
        .replace(ThoughtMarks, "")
        .replace(ThoughtEmphasis, "")
        .replace(ThoughtItalic, "$1$2")
        .replace(CompactedWs, " ")
        .trim()
    if (heading != null) return text
    val end = ThoughtSentenceEnd.find(text) ?: return text
    return text.substring(0, end.range.first + end.value.length)
}

/** Whitespace-collapsed, code-point-capped preview (200 chars + "..."). */
private fun compactWorkEntryText(value: String): String =
    truncate(value.replace(CompactedWs, " "), THOUGHT_PREVIEW_MAX_LENGTH)

/** JS `Array.from` counts code points, not UTF-16 units — match that. */
private fun truncate(text: String, maxLength: Int): String {
    val trimmed = text.trim()
    if (trimmed.codePointCount(0, trimmed.length) <= maxLength) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxLength)) + "..."
}

/**
 * The Devin transcript: reverseLayout LazyColumn fed bottom-to-top —
 * status line first, then each turn's WorkRow, body items (reversed) and
 * user bubble. Rows never displace each other (placementSpec = null); the
 * only entry motion is the 220ms fade/rise on <2s-fresh messages (U5.2).
 */
@Composable
internal fun Transcript(
    model: TranscriptModel,
    tail: ChatItem?,
    ui: UiState,
    pendingPermission: PendingPermission?,
    timings: Map<Long, TurnTiming>,
    expandedWork: Map<Long, Boolean>,
    queued: List<QueuedPrompt>,
    onToggleWork: (Long) -> Unit,
    onExpandWork: (Long) -> Unit,
    onUserLongPress: (ChatItem) -> Unit,
    onResend: (ChatItem) -> Unit,
    onEditQueued: (Long) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    // The row model arrives prebuilt (SessionStore, off the main thread) —
    // a tail item still streaming text overrides the model's copy below.
    val rows = model.rows
    val itemById = model.itemById
    val turnByKey = model.turnByKey
    val lastItemId = model.lastItemId
    val terminalAgentItemIds = model.terminalAgentItemIds

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp,
            top = 8.dp,
            end = 18.dp,
            // U5.4: keep the status line clear of the floating capsule —
            // t3's FLOATING_WORKING_CONTROL_COVERAGE as a bottom inset.
            bottom = 8.dp + bottomInset,
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
                    is RowKind.Queue -> "qp${row.index}"
                }
            },
            contentType = { row ->
                when (row) {
                    is RowKind.Status -> "status"
                    is RowKind.Work -> "work"
                    is RowKind.Body -> itemById[row.itemId]?.kind?.name ?: "body"
                    is RowKind.User -> "user"
                    is RowKind.Queue -> "queue"
                }
            },
        ) { row ->
            // The LazyColumn stays full width so scrolling works anywhere;
            // item content centers inside a 720dp cap on wide screens.
            Box(
                Modifier
                    .animateItem(
                        // No fade-in at this level — fresh-only entry lives
                        // in FreshEntry below; replays and streaming growth
                        // appear instantly. Placement stays off (B5).
                        fadeInSpec = null,
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
                            FreshEntry(m, rise = true) {
                                UserBubble(
                                    m,
                                    onLongPress = { onUserLongPress(m) },
                                    onResend = { onResend(m) },
                                )
                            }
                        }
                        is RowKind.Body -> {
                            val m = itemById[row.itemId]
                                ?.let { if (tail?.id == it.id) tail else it }
                                ?: return@items
                            val streaming = ui.running && m.id == lastItemId &&
                                m.kind == Kind.Agent
                            if (m.kind == Kind.Agent) {
                                val turn = turnByKey[row.turnKey]
                                // Only the settled turn's last agent text
                                // carries the meta bar (t3code's
                                // showAssistantMeta); streaming stays bare.
                                val showMeta = !streaming &&
                                    m.id in terminalAgentItemIds && turn != null
                                FreshEntry(m, rise = false) {
                                    BodyItem(
                                        m,
                                        streaming = streaming,
                                        meta = if (showMeta) {
                                            {
                                                AssistantMetaBar(
                                                    turn = turn,
                                                    timing = timings[
                                                        turn.user?.id ?: turn.key,
                                                    ],
                                                    canFork = ui.revertSupported &&
                                                        turn.user != null,
                                                    onFork = {
                                                        turn.user?.let(onUserLongPress)
                                                    },
                                                    onShowDiff = {
                                                        onExpandWork(turn.key)
                                                    },
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            } else {
                                BodyItem(m)
                            }
                        }
                        is RowKind.Queue -> {
                            val q = queued.getOrNull(row.index) ?: return@items
                            QueuedPreviewBubble(
                                q,
                                onClick = { onEditQueued(q.id) },
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
                                // No diff sheet yet (N6.1) — the pill opens
                                // the turn's WorkRow where DiffCards render.
                                onShowDiff = { onExpandWork(turn.key) },
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
    onShowDiff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // A still-streaming tail item shows its newest text — the model's copy
    // is only refreshed on structural changes.
    val items = remember(turn.work, tail) {
        turn.work.map { if (it.id == tail?.id) tail else it }
    }
    // Same overlay for the grouped render list.
    val entries = remember(turn.workEntries, tail) {
        if (tail == null) {
            turn.workEntries
        } else {
            turn.workEntries.map { e ->
                when (e) {
                    is WorkEntry.Step ->
                        if (e.item.id == tail.id) WorkEntry.Step(tail) else e
                    is WorkEntry.ToolGroup ->
                        if (e.items.none { it.id == tail.id }) {
                            e
                        } else {
                            WorkEntry.ToolGroup(
                                e.key, e.toolKind,
                                e.items.map { if (it.id == tail.id) tail else it },
                            )
                        }
                }
            }
        }
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

    // t3code's exposed thought: the newest non-empty reasoning, flattened
    // to one compacted line — while the turn is live, and afterwards while
    // the row stays collapsed (live || !expanded).
    val latestThought = items.lastOrNull {
        it.kind == Kind.Thought && it.text.isNotBlank()
    }?.text
    val thought = remember(latestThought) {
        latestThought
            ?.let { compactWorkEntryText(liveThoughtLine(it)) }
            ?.takeIf { it.isNotEmpty() }
    }
    // t3code ThreadThinkingRow: sent, running, no chunk/tool back yet.
    val awaitingFirst = live && turn.body.isEmpty()

    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "workChevron",
    )
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        if (awaitingFirst) {
            Row(
                Modifier
                    .padding(vertical = 12.dp)
                    .steppedShimmer(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Brain, null,
                    modifier = Modifier.size(14.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "正在思考…",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            return@Column
        }
        if (thought != null && (live || !expanded)) {
            // t3code ThreadWorkGroupToggle: the thought line toggles too.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggle)
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    HugeIcons.Brain, null,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(14.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    thought,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // t3code run-fold: the row splits into a toggle area (left) and the
        // TurnDiffStat pill (right) — separate hit targets.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        // No turn start (e.g. a remote turn we never saw the
                        // prompt for) → no elapsed; "0:00" would just freeze.
                        if (startedAt > 0L) {
                            "工作中 · ${elapsed / 60}:" +
                                (elapsed % 60).toString().padStart(2, '0')
                        } else {
                            "工作中"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).steppedShimmer(),
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
            }
            if (diffAdd > 0 || diffDel > 0) {
                TurnDiffStatPill(diffAdd = diffAdd, diffDel = diffDel, onClick = onShowDiff)
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
            WorkLogColumn(entries = entries, live = live)
        }
    }
}

/**
 * t3code TurnDiffStat (run-fold trailing): "+A −R ›" — its own hit target
 * (min 40dp), separate from the row's expand toggle.
 */
@Composable
private fun TurnDiffStatPill(
    diffAdd: Int,
    diffDel: Int,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (diffAdd > 0) {
            Text(
                "+$diffAdd",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = DevinGoColors.diffAdd,
                maxLines = 1,
            )
        }
        if (diffDel > 0) {
            Text(
                " −$diffDel",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = DevinGoColors.diffRemove,
                maxLines = 1,
            )
        }
        Icon(
            HugeIcons.ChevronRight,
            contentDescription = "查看本轮改动",
            modifier = Modifier.size(12.dp),
            tint = cs.onSurfaceVariant,
        )
    }
}

// ---- fresh-only entry motion -----------------------------------------------------

private const val FRESH_ENTRY_WINDOW_MS = 2_000L
private const val ENTRY_ANIM_MS = 220
private val ENTRY_RISE = 25.dp

/**
 * t3code's isFreshTimestamp + entering animation (ThreadFeed): items under
 * 2s old — and never replayed history — get a 220ms fade; the user bubble
 * also rises ENTRY_RISE like RN's FadeInDown. Replays, streaming growth
 * and remounts past the window compose already at rest: zero animation.
 */
@Composable
private fun FreshEntry(
    item: ChatItem,
    rise: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animate = !item.isReplay && !isReducedMotion() &&
        System.currentTimeMillis() - item.createdAtMs < FRESH_ENTRY_WINDOW_MS
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (animate) {
            progress.animateTo(1f, tween(ENTRY_ANIM_MS))
        } else {
            // The 2s window can lapse mid-fade — never park partially faded.
            progress.snapTo(1f)
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            if (rise) translationY = (1f - progress.value) * ENTRY_RISE.toPx()
        },
    ) {
        content()
    }
}

// ---- inline body items -----------------------------------------------------------

@Composable
private fun BodyItem(
    m: ChatItem,
    streaming: Boolean = false,
    meta: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (m.kind) {
        Kind.Agent -> AgentText(m, streaming, meta, modifier)
        Kind.Error -> ErrorLine(m.text, modifier)
        Kind.System -> SystemLine(m.text, modifier)
        Kind.Plan -> PlanCard(m, modifier)
        else -> Unit // Thought/Tool never render inline.
    }
}

@Composable
private fun AgentText(
    m: ChatItem,
    streaming: Boolean,
    meta: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        MarkdownBlock(
            content = m.text,
            // MarkdownBlock pads its content 4dp horizontally internally; pull
            // the block back so agent text shares the transcript's left edge.
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = (-4).dp)
                .padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            streaming = streaming,
        )
        // The settled-turn bar fades in when the run ends; a row composed
        // already-settled renders it instantly (no replayed animation).
        AnimatedVisibility(
            visible = meta != null,
            enter = fadeIn(tween(180)),
        ) {
            meta?.invoke()
        }
    }
}

/**
 * t3code's settled-turn bottom bar (ThreadFeed showAssistantMeta row):
 * copy the turn's whole reply, fork from its user prompt, the +A/−R diff
 * pill and the HH:mm completion time — bottom-left under the last agent
 * message of the turn.
 */
@Composable
private fun AssistantMetaBar(
    turn: Turn,
    timing: TurnTiming?,
    canFork: Boolean,
    onFork: () -> Unit,
    onShowDiff: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(turn.key) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200) // t3code COPY_FEEDBACK_DURATION_MS
            copied = false
        }
    }
    val reply = remember(turn.body) {
        turn.body.filter { it.kind == Kind.Agent }
            .joinToString("\n\n") { it.text }
            .trim()
    }
    val (diffAdd, diffDel) = turn.diffStats
    val timeLabel = timing?.end?.let { end ->
        remember(end) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(end))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetaAction(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("reply", reply)),
                    )
                }
                copied = true
            },
        ) {
            Icon(
                if (copied) Icons.Filled.Check else HugeIcons.Copy01,
                contentDescription = if (copied) "已复制" else "复制回复",
                modifier = Modifier.size(14.dp),
                tint = if (copied) DevinGoColors.diffAdd else cs.onSurfaceVariant,
            )
        }
        if (canFork) {
            MetaAction(onClick = onFork) {
                Icon(
                    HugeIcons.ArrowTurnForward,
                    contentDescription = "从此处分叉",
                    modifier = Modifier.size(14.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
        if (diffAdd > 0 || diffDel > 0) {
            // U5.2 wires the pill to the turn's inline WorkRow expansion
            // (the DiffCards); the dedicated diff sheet lands with N6.1.
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onShowDiff)
                    .padding(horizontal = 6.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (diffAdd > 0) {
                    Text(
                        "+$diffAdd",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = DevinGoColors.diffAdd,
                        maxLines = 1,
                    )
                }
                if (diffDel > 0) {
                    Text(
                        " −$diffDel",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = DevinGoColors.diffRemove,
                        maxLines = 1,
                    )
                }
                Icon(
                    HugeIcons.ChevronRight,
                    contentDescription = "查看本轮改动",
                    modifier = Modifier.size(12.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
        timeLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 36dp-square meta-bar touch target (t3code's replyActionSize hitbox). */
@Composable
private fun MetaAction(onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
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

/**
 * U5.4: a parked prompt's placeholder in the transcript — a translucent,
 * dashed-border echo of the user bubble it becomes when it drains (t3code
 * appends queued messages as pending feed entries the same way). Tap pulls
 * it into the composer for editing.
 */
@Composable
private fun QueuedPreviewBubble(q: QueuedPrompt, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = when {
        q.attachments.isEmpty() -> q.text
        q.text.isEmpty() -> "[${q.attachments.size} 张图片]"
        else -> "${q.text} [${q.attachments.size} 张图片]"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onClick),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = cs.onSurface.copy(alpha = 0.75f),
                    ),
                    modifier = Modifier
                        .drawBehind {
                            val r = CornerRadius(20.dp.toPx())
                            drawRoundRect(
                                color = cs.surfaceContainer.copy(alpha = 0.6f),
                                cornerRadius = r,
                            )
                            drawRoundRect(
                                color = cs.outline,
                                cornerRadius = r,
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(
                                            6.dp.toPx(),
                                            4.dp.toPx(),
                                        ),
                                    ),
                                ),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        HugeIcons.PencilEdit01, null,
                        modifier = Modifier.size(11.dp),
                        tint = cs.onSurfaceVariant,
                    )
                    Text(
                        "排队中 · 点击编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

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
                if (m.steered && !m.failed) {
                    // U5.4: mid-turn prompts wear the ↪ 已转向 corner badge.
                    Row(
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            HugeIcons.ArrowTurnForward, null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Text(
                            "已转向",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
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
