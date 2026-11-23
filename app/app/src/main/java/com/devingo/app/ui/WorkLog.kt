package com.devingo.app.ui

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.ChatItem
import com.devingo.app.Kind
import com.devingo.app.ToolDiff
import com.devingo.app.WorkEntry
import com.devingo.app.diffStatsOf
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.Expand
import me.rerere.hugeicons.stroke.File01

// ---- expanded work log ---------------------------------------------------------

// t3code ThreadWorkGroupList port (MIT):
//   .research/t3code-20261123/apps/mobile/src/features/threads/thread-work-log.tsx
//     (WORK_GROUP_MAX_HEIGHT, EdgeFade, maintainScrollAtEnd)
//   .research/t3code-20261123/apps/mobile/src/features/threads/thread-command-output.tsx
//     (FOLLOW_SLACK)
private val WORK_LOG_MAX_H = 320.dp
private val WORK_LOG_RELAXED_H = 460.dp
private val WORK_LOG_FADE_H = 20.dp
private val FOLLOW_SLACK = 8.dp

/**
 * The inline expansion under a turn's WorkRow: a 1dp rail at the left with
 * thought/tool steps indented 16dp from it, capped at [WORK_LOG_MAX_H]
 * ([WORK_LOG_RELAXED_H] once a row's detail or a DiffCard is expanded
 * inside) and scrolled internally. Top/bottom edge fades track the scroll
 * position like t3code's EdgeFade; while the user is parked at the bottom,
 * appending steps keep the tail pinned (maintainScrollAtEnd).
 */
@Composable
internal fun WorkLogColumn(
    entries: List<WorkEntry>,
    live: Boolean,
    modifier: Modifier = Modifier,
    /** N6.1: DiffCard's 全屏审查 affordance — opens the review sheet. */
    onReviewDiff: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // Row-detail expansion is lifted here so the container can relax its
    // cap while a detail card or diff is open inside.
    val expandedDetails = remember { mutableStateMapOf<Long, Boolean>() }
    val hasDiffs = remember(entries) {
        entries.any { it is WorkEntry.Step && it.item.diffs.isNotEmpty() }
    }
    val relaxed = hasDiffs || expandedDetails.values.any { it }
    val maxH by animateDpAsState(
        targetValue = if (relaxed) WORK_LOG_RELAXED_H else WORK_LOG_MAX_H,
        animationSpec = tween(200),
        label = "workLogMaxH",
    )

    val scroll = rememberScrollState()
    val fadePx = with(LocalDensity.current) { WORK_LOG_FADE_H.toPx() }
    val slackPx = with(LocalDensity.current) { FOLLOW_SLACK.toPx() }
    // t3code EdgeFade: each fade's alpha is the scrolled distance past its
    // edge over the fade height — off while content doesn't continue past it.
    val topFade by remember {
        derivedStateOf {
            val max = scroll.maxValue
            if (max <= 0 || max == Int.MAX_VALUE) {
                0f
            } else {
                (scroll.value / fadePx).coerceIn(0f, 1f)
            }
        }
    }
    val bottomFade by remember {
        derivedStateOf {
            val max = scroll.maxValue
            if (max <= 0 || max == Int.MAX_VALUE) {
                0f
            } else {
                ((max - scroll.value) / fadePx).coerceIn(0f, 1f)
            }
        }
    }
    // Follow the tail: re-evaluated live while the user scrolls (>FOLLOW_SLACK
    // off the bottom pauses follow, parking back at the bottom resumes it);
    // content growth without a scroll in flight leaves the flag alone.
    // Initial false — t3code opens the group at the top (reading order);
    // follow only engages once the user reaches the bottom.
    var stickToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow {
            Triple(scroll.value, scroll.maxValue, scroll.isScrollInProgress)
        }.collect { (v, max, inProgress) ->
            if (inProgress && max != Int.MAX_VALUE) {
                stickToEnd = max - v <= slackPx
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { max ->
            // Never queue a snap behind a user gesture — a scrollTo suspended
            // on the mutator mutex would land after their fling and yank the
            // view back to the end.
            if (max != Int.MAX_VALUE && max > 0 && stickToEnd &&
                !scroll.isScrollInProgress
            ) {
                scroll.scrollTo(max)
            }
        }
    }
    LaunchedEffect(stickToEnd) {
        if (stickToEnd && scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 7.dp)
            .drawBehind {
                drawRect(
                    color = cs.outlineVariant,
                    topLeft = Offset.Zero,
                    size = Size(1.dp.toPx(), size.height),
                )
            }
            .heightIn(max = maxH),
    ) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is WorkEntry.Step -> when (entry.item.kind) {
                        Kind.Thought -> ThoughtLogRow(
                            item = entry.item,
                            expanded = expandedDetails[entry.item.id] == true,
                            onToggleExpand = {
                                expandedDetails[entry.item.id] =
                                    expandedDetails[entry.item.id] != true
                            },
                        )
                        Kind.Tool -> ToolLogRow(
                            item = entry.item,
                            expanded = expandedDetails[entry.item.id] == true,
                            onToggleExpand = {
                                expandedDetails[entry.item.id] =
                                    expandedDetails[entry.item.id] != true
                            },
                            onReviewDiff = onReviewDiff,
                        )
                        else -> Unit
                    }
                    is WorkEntry.ToolGroup -> ToolGroupRow(
                        group = entry,
                        expandedDetails = expandedDetails,
                        onReviewDiff = onReviewDiff,
                    )
                }
            }
            if (entries.isEmpty()) {
                Text(
                    if (live) "Devin 正在思考…" else "没有过程记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (topFade > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WORK_LOG_FADE_H)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = topFade }
                    .background(
                        Brush.verticalGradient(
                            listOf(cs.surface, Color.Transparent),
                        ),
                    ),
            )
        }
        if (bottomFade > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WORK_LOG_FADE_H)
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = bottomFade }
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, cs.surface),
                        ),
                    ),
            )
        }
    }
}

/** Collapsed "思考 · <40 chars>" row; expands to a scrollable thought body. */
@Composable
private fun ThoughtLogRow(
    item: ChatItem,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val rot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "thoughtChevron",
    )
    val preview = remember(item.text) {
        val p = item.text.replace(Regex("\\s+"), " ").trim()
        if (p.length > 40) p.take(40) + "…" else p
    }
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable(onClick = onToggleExpand),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (preview.isEmpty()) "思考" else "思考 · $preview",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                HugeIcons.ChevronRight, null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rot),
                tint = cs.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            // t3code ThreadReasoningGroup: the body scrolls inside a capped
            // box; the bottom edge fade tracks remaining scroll distance
            // (opacity = remaining / fadeHeight).
            val scroll = rememberScrollState()
            val fadePx = with(LocalDensity.current) { 24.dp.toPx() }
            val fadeAlpha by remember {
                derivedStateOf {
                    val max = scroll.maxValue
                    if (max <= 0 || max == Int.MAX_VALUE) {
                        0f
                    } else {
                        ((max - scroll.value) / fadePx).coerceIn(0f, 1f)
                    }
                }
            }
            Box {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(scroll)
                        .padding(bottom = 6.dp),
                )
                if (fadeAlpha > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = fadeAlpha }
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, cs.surface),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

/** Row-flash duration on copy — t3code COPY_FEEDBACK_DURATION_MS. */
private const val COPY_FEEDBACK_MS = 1_200L

/**
 * Tool step: one-line title (mono for execute), trailing spinner/error, tap
 * expands the detail (exec calls get the ANSI [CommandOutputBox]), long-press
 * copies command + output (green "已复制" flash). Every reported diff renders
 * as a [DiffCard] underneath.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolLogRow(
    item: ChatItem,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onReviewDiff: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val running = item.status == "in_progress"
    val detail = item.detail?.takeIf { it.isNotBlank() }
    val hasDetail = detail != null
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_FEEDBACK_MS)
            copied = false
        }
    }
    val copyText = remember(item.text, detail) {
        listOfNotNull(
            item.text.ifBlank { null },
            detail?.let { stripAnsi(foldCarriageReturn(it)) },
        ).joinToString("\n")
    }
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(
                    onClick = { if (hasDetail) onToggleExpand() },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("tool", copyText)),
                            )
                        }
                        copied = true
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.text.ifBlank { toolKindLabel(item.toolKind) },
                style = if (item.toolKind == "execute") {
                    monoText(MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (copied) {
                Text(
                    "已复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = DevinGoColors.diffAdd,
                    maxLines = 1,
                )
            } else when {
                running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
                item.status == "failed" -> Icon(
                    Icons.Filled.Close, "失败",
                    modifier = Modifier.size(14.dp),
                    tint = cs.error,
                )
                hasDetail -> {
                    val rot by animateFloatAsState(
                        if (expanded) 180f else 0f,
                        label = "toolChevron",
                    )
                    Icon(
                        Icons.Filled.KeyboardArrowDown, null,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(rot),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        }
        if (hasDetail) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                if (item.toolKind == "execute") {
                    CommandOutputBox(item = item, detail = detail)
                } else {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = cs.surfaceContainerLowest,
                        border = androidx.compose.foundation.BorderStroke(
                            Hairline, cs.outlineVariant,
                        ),
                    ) {
                        Text(
                            detail.lineSequence().take(40).joinToString("\n"),
                            style = monoText(
                                MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                            ),
                            color = cs.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
        if (item.diffs.isNotEmpty()) {
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.diffs.forEach { diff ->
                    DiffCard(
                        diff = diff,
                        memoKey = item.id,
                        onReview = { onReviewDiff(diff.path) },
                    )
                }
            }
        }
    }
}

// ---- command output (execute rows) -------------------------------------------

private val COMMAND_OUT_MAX_H = 240.dp
private const val COMMAND_OUT_MAX_CHARS = 4_000
private const val COMMAND_OUT_MAX_LINES = 200

/**
 * t3code ThreadCommandOutput port: a capped, internally scrolling mono box.
 * The window keeps the trailing [COMMAND_OUT_MAX_CHARS] chars /
 * [COMMAND_OUT_MAX_LINES] lines with an "Earlier output not shown" marker
 * when the head was dropped; ANSI SGR colors/bold render via
 * [ansiToAnnotatedString]. While the call runs the box follows appended
 * output — scrolling >[FOLLOW_SLACK] off the bottom pauses, parking back
 * resumes. A non-zero exit prints a red `exit N` line.
 */
@Composable
private fun CommandOutputBox(
    item: ChatItem,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val (window, headDropped) = remember(detail) {
        val folded = foldCarriageReturn(detail)
        var tail = folded.takeLast(COMMAND_OUT_MAX_CHARS)
        // The char cap can slice an ANSI sequence at the window start —
        // drop the leading fragment (an escape tail can't be styled).
        if (tail.length < folded.length) {
            tail = LEADING_ESC_FRAGMENT.replace(tail, "")
        }
        val lines = tail.split('\n')
        val kept = if (lines.size > COMMAND_OUT_MAX_LINES) {
            lines.takeLast(COMMAND_OUT_MAX_LINES)
        } else {
            lines
        }
        (kept.joinToString("\n")) to
            (tail.length < folded.length || kept.size < lines.size)
    }
    val styled = remember(window) { ansiToAnnotatedString(window) }
    val scroll = rememberScrollState()
    val slackPx = with(LocalDensity.current) { FOLLOW_SLACK.toPx() }
    var follow by remember { mutableStateOf(true) }
    // t3code's onScroll: follow holds while the offset is within FOLLOW_SLACK
    // of the end. Evaluated only on scroll positions the user is driving;
    // pure content growth (maxValue change, no scroll) leaves it untouched.
    LaunchedEffect(scroll) {
        snapshotFlow {
            Triple(scroll.value, scroll.maxValue, scroll.isScrollInProgress)
        }.collect { (v, max, inProgress) ->
            if (inProgress && max != Int.MAX_VALUE) {
                follow = max - v <= slackPx
            }
        }
    }
    // t3code's onContentSizeChange → scrollToEnd while following — but never
    // queue a snap behind a user gesture (see WorkLogColumn).
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { max ->
            if (max != Int.MAX_VALUE && max > 0 && follow &&
                !scroll.isScrollInProgress
            ) {
                scroll.scrollTo(max)
            }
        }
    }
    LaunchedEffect(follow) {
        if (follow && scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            scroll.scrollTo(scroll.maxValue)
        }
    }
    Column(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = cs.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(
                Hairline, cs.outlineVariant,
            ),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = COMMAND_OUT_MAX_H)
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (headDropped || item.detailTruncated) {
                    Text(
                        "Earlier output not shown",
                        style = monoText(
                            MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        ),
                        color = cs.outline,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    styled,
                    style = monoText(
                        MaterialTheme.typography.labelSmall.copy(fontSize = 12.5.sp),
                    ),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val exitCode = item.exitCode
        if (exitCode != null && exitCode != 0) {
            Text(
                "exit $exitCode",
                style = monoText(
                    MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                ),
                color = cs.error,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
    }
}

// ---- same-kind tool group -----------------------------------------------------

/** Summary line for a collapsed group ("读取了 6 个文件" — t3code port). */
private fun toolGroupSummary(group: WorkEntry.ToolGroup): String =
    when (group.toolKind) {
        "read" -> "读取了 ${group.items.size} 个文件"
        "search" -> "搜索了 ${group.items.size} 处代码"
        "fetch" -> "抓取了 ${group.items.size} 个网页"
        else -> "调用了 ${group.items.size} 次工具"
    }

/**
 * Collapsed-by-default row for ≥2 consecutive same-kind read-only tool
 * calls (t3code ThreadWorkGroupToggle): category icon + one-line summary +
 * chevron. While the group's tail call is still running the summary gets
 * the stepped shimmer; tapping expands to the member rows.
 */
@Composable
private fun ToolGroupRow(
    group: WorkEntry.ToolGroup,
    expandedDetails: SnapshotStateMap<Long, Boolean>,
    onReviewDiff: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(group.key) { mutableStateOf(false) }
    val running = group.items.last().status == "in_progress"
    val rot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "groupChevron",
    )
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .then(if (running) Modifier.steppedShimmer() else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    toolKindIcon(group.toolKind), null,
                    modifier = Modifier.size(14.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    toolGroupSummary(group),
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowDown, null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rot),
                tint = cs.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier.padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                group.items.forEach { member ->
                    ToolLogRow(
                        item = member,
                        expanded = expandedDetails[member.id] == true,
                        onToggleExpand = {
                            expandedDetails[member.id] =
                                expandedDetails[member.id] != true
                        },
                        onReviewDiff = onReviewDiff,
                    )
                }
            }
        }
    }
}

// ---- diff card --------------------------------------------------------------------

private enum class DiffKind { CONTEXT, ADD, DEL }

private data class DiffRow(
    val kind: DiffKind,
    val text: String,
    val oldNo: Int = 0,
    val newNo: Int = 0,
)

private sealed interface DiffDisplay {
    data class Line(val row: DiffRow) : DiffDisplay
    data object Separator : DiffDisplay
}

private const val DIFF_MAX_BODY_LINES = 4000
private const val DIFF_LCS_LIMIT = 1500
private const val DIFF_COLLAPSED_LINES = 6
private const val DIFF_EXPANDED_LINES = 400
private const val DIFF_CONTEXT = 2

/**
 * One file diff: header (icon, basename, +A/−R stats) over a mono line body.
 * The LCS runs off the main thread and is memoized per
 * (item id, path, old/new length); bodies over 4000 total lines hide.
 */
@Composable
internal fun DiffCard(
    diff: ToolDiff,
    memoKey: Long,
    modifier: Modifier = Modifier,
    /** N6.1: 全屏审查 affordance — opens the review sheet on this file. */
    onReview: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val oldLines = diff.oldText?.lineSequence()?.count() ?: 0
    val newCount = remember(diff.newText) { diff.newText.lineSequence().count() }
    val tooLarge = oldLines + newCount > DIFF_MAX_BODY_LINES
    val (added, removed) = remember(diff.path, oldLines, newCount) {
        diffStatsOf(listOf(diff))
    }
    val basename = diff.path.replace('\\', '/').substringAfterLast('/')
        .ifBlank { diff.path }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(Hairline, cs.outlineVariant),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.File01, null,
                    modifier = Modifier.size(16.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    basename,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = diff.path },
                )
                if (added > 0) {
                    Text(
                        "+$added",
                        style = MaterialTheme.typography.labelMedium,
                        color = DevinGoColors.diffAdd,
                        maxLines = 1,
                    )
                }
                if (removed > 0) {
                    Text(
                        " −$removed",
                        style = MaterialTheme.typography.labelMedium,
                        color = DevinGoColors.diffRemove,
                        maxLines = 1,
                    )
                }
                Icon(
                    HugeIcons.Expand, "全屏审查",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onReview)
                        .padding(7.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
            if (tooLarge) {
                Text(
                    "文件较大，未显示差异",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                DiffBody(diff, memoKey)
            }
        }
    }
}

/** Collapsible hunked body: first 6 rows + fade, or up to 400 + remainder. */
@Composable
private fun DiffBody(diff: ToolDiff, memoKey: Long) {
    val cs = MaterialTheme.colorScheme
    val rows by produceState<List<DiffRow>?>(
        initialValue = null,
        memoKey,
        diff.path,
        diff.oldText?.length ?: -1,
        diff.newText.length,
    ) {
        value = withContext(Dispatchers.Default) {
            computeDiffRows(diff.oldText, diff.newText)
        }
    }
    val all = rows
    if (all == null) {
        // Line-count placeholder while the LCS runs off the main thread.
        val lines = remember(diff.newText) { diff.newText.lineSequence().count() }
        Text(
            "差异计算中 · $lines 行",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }
    val display = remember(all) { hunkRows(all) }
    var expanded by remember(memoKey, diff.path) { mutableStateOf(false) }
    val shown = if (expanded) {
        display.take(DIFF_EXPANDED_LINES)
    } else {
        display.take(DIFF_COLLAPSED_LINES)
    }
    val hidden = display.size - shown.size

    Column(Modifier.fillMaxWidth()) {
        Box {
            DiffLines(shown)
            if (!expanded && display.isNotEmpty()) {
                // Bottom fade hinting more content below.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, cs.surfaceContainerLowest),
                            ),
                        ),
                )
            }
        }
        if (!expanded) {
            Text(
                "展开 · ${display.size} 行",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else if (hidden > 0) {
            Text(
                "… 还有 $hidden 行",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Mono line body with a fixed-width right-aligned number column. */
@Composable
private fun DiffLines(display: List<DiffDisplay>) {
    val cs = MaterialTheme.colorScheme
    val rows = display.filterIsInstance<DiffDisplay.Line>().map { it.row }
    if (rows.isEmpty()) return
    val maxNo = rows.maxOf { maxOf(it.oldNo, it.newNo) }
    val numWidth = with(LocalDensity.current) {
        (maxNo.toString().length * 6.8f).sp.toDp()
    } + 10.dp
    val codeStyle = monoText(
        MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
    )
    val numStyle = monoText(
        MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
    )
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            display.forEach { item ->
                when (item) {
                    is DiffDisplay.Separator -> Text(
                        "⋯",
                        style = numStyle,
                        color = cs.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                    is DiffDisplay.Line -> DiffCodeLine(
                        row = item.row,
                        cs = cs,
                        codeStyle = codeStyle,
                        numStyle = numStyle,
                        numWidth = numWidth,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffCodeLine(
    row: DiffRow,
    cs: androidx.compose.material3.ColorScheme,
    codeStyle: androidx.compose.ui.text.TextStyle,
    numStyle: androidx.compose.ui.text.TextStyle,
    numWidth: androidx.compose.ui.unit.Dp,
) {
    val (bg, bar) = when (row.kind) {
        DiffKind.ADD -> DevinGoColors.diffAdd.copy(alpha = 0.12f) to DevinGoColors.diffAdd
        DiffKind.DEL -> DevinGoColors.diffRemove.copy(alpha = 0.10f) to DevinGoColors.diffRemove
        DiffKind.CONTEXT -> Color.Transparent to Color.Transparent
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // 2dp kind bar keeps the number column aligned on context rows.
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(bar),
        )
        Text(
            when (row.kind) {
                DiffKind.DEL -> row.oldNo.takeIf { it > 0 }?.toString().orEmpty()
                else -> row.newNo.takeIf { it > 0 }?.toString().orEmpty()
            },
            style = numStyle,
            color = cs.outline,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(numWidth)
                .padding(end = 8.dp),
        )
        Text(
            row.text,
            style = codeStyle,
            color = cs.onSurface,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .background(bg)
                .padding(horizontal = 8.dp),
        )
    }
}

// ---- diff engine -------------------------------------------------------------------

/** Aligned old→new line diff with ±2 context hunks and ⋯ separators. */
private fun computeDiffRows(oldText: String?, newText: String): List<DiffRow> {
    val a = oldText?.lineSequence()?.toList().orEmpty()
    val b = newText.lineSequence().toList()
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var aEnd = a.size
    var bEnd = b.size
    while (aEnd > start && bEnd > start && a[aEnd - 1] == b[bEnd - 1]) {
        aEnd--
        bEnd--
    }
    val rows = mutableListOf<DiffRow>()
    for (i in 0 until start) {
        rows += DiffRow(DiffKind.CONTEXT, a[i], oldNo = i + 1, newNo = i + 1)
    }
    val midA = a.subList(start, aEnd)
    val midB = b.subList(start, bEnd)
    var oldNo = start + 1
    var newNo = start + 1
    for (op in lcsOps(midA, midB)) {
        when (op.kind) {
            DiffKind.CONTEXT -> {
                rows += DiffRow(op.kind, op.text, oldNo, newNo)
                oldNo++; newNo++
            }
            DiffKind.DEL -> {
                rows += DiffRow(op.kind, op.text, oldNo = oldNo)
                oldNo++
            }
            DiffKind.ADD -> {
                rows += DiffRow(op.kind, op.text, newNo = newNo)
                newNo++
            }
        }
    }
    // Suffix context: new index shifts by the size delta.
    val shift = b.size - a.size
    for (i in aEnd until a.size) {
        rows += DiffRow(DiffKind.CONTEXT, a[i], oldNo = i + 1, newNo = i + 1 + shift)
    }
    return rows
}

private data class DiffOp(val kind: DiffKind, val text: String)

/** LCS backtrack for middles ≤ DIFF_LCS_LIMIT lines; else del+add block. */
private fun lcsOps(a: List<String>, b: List<String>): List<DiffOp> {
    if (a.isEmpty()) return b.map { DiffOp(DiffKind.ADD, it) }
    if (b.isEmpty()) return a.map { DiffOp(DiffKind.DEL, it) }
    if (a.size + b.size > DIFF_LCS_LIMIT) {
        return a.map { DiffOp(DiffKind.DEL, it) } +
            b.map { DiffOp(DiffKind.ADD, it) }
    }
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }
    val out = mutableListOf<DiffOp>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out += DiffOp(DiffKind.CONTEXT, a[i]); i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += DiffOp(DiffKind.DEL, a[i]); i++
            }
            else -> {
                out += DiffOp(DiffKind.ADD, b[j]); j++
            }
        }
    }
    while (i < n) { out += DiffOp(DiffKind.DEL, a[i]); i++ }
    while (j < m) { out += DiffOp(DiffKind.ADD, b[j]); j++ }
    return out
}

/** Keep ±2 context around every change; join hunks with a ⋯ separator. */
private fun hunkRows(rows: List<DiffRow>): List<DiffDisplay> {
    if (rows.isEmpty()) return emptyList()
    val keep = BooleanArray(rows.size)
    rows.forEachIndexed { i, r ->
        if (r.kind != DiffKind.CONTEXT) {
            val lo = (i - DIFF_CONTEXT).coerceAtLeast(0)
            val hi = (i + DIFF_CONTEXT).coerceAtMost(rows.size - 1)
            for (j in lo..hi) keep[j] = true
        }
    }
    val out = mutableListOf<DiffDisplay>()
    var pendingSep = false
    rows.forEachIndexed { i, r ->
        if (!keep[i]) {
            if (out.isNotEmpty()) pendingSep = true
            return@forEachIndexed
        }
        if (pendingSep) {
            out += DiffDisplay.Separator
            pendingSep = false
        }
        out += DiffDisplay.Line(r)
    }
    return out
}
