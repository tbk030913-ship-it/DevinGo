package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.ChatViewModel.ChatItem
import com.devingo.app.ChatViewModel.Kind
import com.devingo.app.ChatViewModel.ToolDiff
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.File01

// ---- expanded work log ---------------------------------------------------------

/**
 * The inline expansion under a turn's WorkRow: a 1dp rail at the left with
 * thought/tool steps indented 16dp from it. Replaces the old WorkSheet.
 */
@Composable
internal fun WorkLogColumn(
    items: List<ChatItem>,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
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
            },
    ) {
        Column(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                when (item.kind) {
                    Kind.Thought -> ThoughtLogRow(item)
                    Kind.Tool -> ToolLogRow(item)
                    else -> Unit
                }
            }
            if (items.isEmpty()) {
                Text(
                    if (live) "Devin 正在思考…" else "没有过程记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** Collapsed-by-default "思考" row; expands to the thought body. */
@Composable
private fun ThoughtLogRow(item: ChatItem) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(item.id) { mutableStateOf(false) }
    val rot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "thoughtChevron",
    )
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "思考",
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
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * Tool step: one-line title (mono for execute), trailing spinner/error, tap
 * expands the mono detail card when present, and every reported diff renders
 * as a [DiffCard] underneath.
 */
@Composable
private fun ToolLogRow(item: ChatItem) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(item.id) { mutableStateOf(false) }
    val running = item.status == "in_progress"
    val detail = item.detail?.takeIf { it.isNotBlank() }
    val hasDetail = detail != null
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier),
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
            when {
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
        if (item.diffs.isNotEmpty()) {
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.diffs.forEach { diff ->
                    DiffCard(diff = diff, memoKey = item.id)
                }
            }
        }
    }
}

// ---- diff card --------------------------------------------------------------------

/** Multiset line difference → (added, removed); spec §B stats rule. */
internal fun diffStatsOf(diffs: List<ToolDiff>): Pair<Int, Int> {
    var added = 0
    var removed = 0
    diffs.forEach { d ->
        val oldLines = d.oldText?.lineSequence()?.toList().orEmpty()
        val newLines = d.newText.lineSequence().toList()
        val oldLeft = oldLines.groupingBy { it }.eachCount().toMutableMap()
        newLines.forEach { line ->
            val c = oldLeft[line] ?: 0
            if (c > 0) oldLeft[line] = c - 1 else added++
        }
        val newLeft = newLines.groupingBy { it }.eachCount().toMutableMap()
        oldLines.forEach { line ->
            val c = newLeft[line] ?: 0
            if (c > 0) newLeft[line] = c - 1 else removed++
        }
    }
    return added to removed
}

/** Turn-level +A/−R shown on the WorkRow header. */
internal fun workDiffStats(items: List<ChatItem>): Pair<Int, Int> =
    diffStatsOf(items.flatMap { it.diffs })

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
internal fun DiffCard(diff: ToolDiff, memoKey: Long, modifier: Modifier = Modifier) {
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
