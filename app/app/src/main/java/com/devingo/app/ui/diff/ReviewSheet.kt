// Full-height native diff review sheet (N6.1) — Kotlin port of t3code's
// ReviewSheet.tsx + reviewFileVisibility.ts (MIT): a scope menu (本轮变更 /
// 本会话工具变更 / Git 未提交变更), 隐藏测试文件 toggle, file quick-jump,
// per-file Viewed checkboxes, and the Canvas diff view underneath. Line
// taps open the comment composer; long-press + tap selects a range.
// "分支对比" is intentionally absent: it needs a merge base and lands with
// the W8.2 git plumbing.

package com.devingo.app.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.devingo.app.SessionDiffs
import com.devingo.app.ToolDiff
import com.devingo.app.ui.DevinMenu
import com.devingo.app.ui.DevinMenuCheckItem
import com.devingo.app.ui.DevinMenuItem
import com.devingo.app.acp.GitDiffResult
import com.devingo.app.ui.DevinSheet
import com.devingo.app.ui.isReducedMotion
import com.devingo.app.ui.monoText
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.GitCommit
import me.rerere.hugeicons.stroke.SlidersHorizontal

/** How the sheet was opened — decides the initial scope. */
sealed interface ReviewRequest {
    /** Top-bar badge / DiffCard: the whole session's merged tool diffs. */
    data class Session(val filePath: String? = null) : ReviewRequest

    /** A turn's diff pill: scoped to that turn. */
    data class Turn(val turnKey: Long, val filePath: String? = null) : ReviewRequest

    /** W8.2: the Git sheet's "查看未提交改动" — repo worktree, not tool diffs. */
    data object Git : ReviewRequest
}

private sealed interface ReviewScope {
    data object Session : ReviewScope
    data object Git : ReviewScope
    data class Turn(val turnKey: Long) : ReviewScope
}

private sealed interface ReviewContent {
    data object Loading : ReviewContent
    data class Ready(val data: NativeReviewData, val truncated: Boolean = false) : ReviewContent
    data class Failed(val error: String) : ReviewContent
}

private fun ReviewScope.label(diffs: SessionDiffs): String = when (this) {
    ReviewScope.Session -> "本会话工具变更"
    ReviewScope.Git -> "Git 未提交变更"
    is ReviewScope.Turn -> {
        val idx = diffs.turnOrder.indexOf(turnKey)
        if (turnKey == diffs.latestTurnKey || idx < 0) "本轮变更" else "第 ${idx + 1} 轮变更"
    }
}

/** Compose theme → the canvas' opaque ARGB palette (t3's native theme seam). */
@Composable
private fun reviewDiffTheme(): DiffTheme {
    val cs = MaterialTheme.colorScheme
    val base = DiffTheme.fallback(if (isSystemInDarkTheme()) "dark" else "light")
    return base.copy(
        background = cs.surface.toArgb(),
        text = cs.onSurface.toArgb(),
        mutedText = cs.onSurfaceVariant.toArgb(),
        headerBackground = cs.surface.toArgb(),
        border = cs.outlineVariant.toArgb(),
        hunkBackground = cs.surfaceContainerHigh.toArgb(),
        hunkText = cs.onSurface.toArgb(),
        warningText = DevinGoColors.warning.toArgb(),
        warningBackground = DevinGoColors.warningContainer.toArgb(),
    )
}

private fun toggle(set: Set<String>, id: String): Set<String> =
    if (set.contains(id)) set - id else set + id

@Composable
fun ReviewSheet(
    diffs: SessionDiffs,
    request: ReviewRequest,
    fetchGitDiff: suspend () -> GitDiffResult,
    onSubmitComment: (ReviewSelection, String) -> Unit,
    /** W8.2: the control-bar Git icon — opens the commit sheet (null hides it). */
    onOpenCommit: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val theme = reviewDiffTheme()
    val reducedMotion = isReducedMotion()

    var reviewScope by remember(request) {
        mutableStateOf<ReviewScope>(
            when (request) {
                is ReviewRequest.Turn ->
                    if (diffs.byTurn.containsKey(request.turnKey)) {
                        ReviewScope.Turn(request.turnKey)
                    } else {
                        ReviewScope.Session
                    }
                is ReviewRequest.Session -> ReviewScope.Session
                is ReviewRequest.Git -> ReviewScope.Git
            },
        )
    }
    var hideTests by remember { mutableStateOf(false) }
    var collapsedFileIds by remember { mutableStateOf(setOf<String>()) }
    var viewedFileIds by remember { mutableStateOf(setOf<String>()) }
    var selectedRowIds by remember { mutableStateOf(setOf<String>()) }
    // fileId → line index: the long-pressed range anchor awaiting its end.
    var pendingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var commentSelection by remember { mutableStateOf<ReviewSelection?>(null) }
    var scopeMenuOpen by remember { mutableStateOf(false) }
    // Git scope only: the bridge capped the patch at 4 MiB.
    var gitTruncated by remember { mutableStateOf(false) }
    var fileMenuOpen by remember { mutableStateOf(false) }
    // A file the canvas should bring into view once rows land (menu jump /
    // DiffCard entry). Consumed in the AndroidView update block.
    var scrollRequest by remember { mutableStateOf<String?>(null) }
    val initialFilePath = when (request) {
        is ReviewRequest.Session -> request.filePath
        is ReviewRequest.Turn -> request.filePath
        is ReviewRequest.Git -> null
    }

    // Scope switch also clears per-file folds/selection — the new scope has
    // a different file set (t3's visibility state is per-section).
    fun setScope(next: ReviewScope) {
        if (reviewScope == next) return
        reviewScope = next
        collapsedFileIds = emptySet()
        viewedFileIds = emptySet()
        selectedRowIds = emptySet()
        pendingAnchor = null
    }

    val content by produceState<ReviewContent>(
        initialValue = ReviewContent.Loading,
        reviewScope, hideTests, diffs,
    ) {
        value = withContext(Dispatchers.Default) {
            try {
                val files = when (val s = reviewScope) {
                    ReviewScope.Session -> buildReviewFilesFromToolDiffs(
                        diffs.files.map { ToolDiff(it.path, it.oldText, it.newText) },
                    )
                    is ReviewScope.Turn -> buildReviewFilesFromToolDiffs(
                        diffs.byTurn[s.turnKey].orEmpty()
                            .map { ToolDiff(it.path, it.oldText, it.newText) },
                    )
                    ReviewScope.Git -> {
                        val res = fetchGitDiff()
                        gitTruncated = res.truncated
                        parseGitPatch(res.patch)
                    }
                }
                val filtered =
                    if (hideTests) files.filter { !isTestChangedFile(it.path) } else files
                ReviewContent.Ready(
                    buildNativeReviewData(filtered),
                    // The flag belongs to the Git fetch — don't carry it
                    // over when the user switches back to a tool scope.
                    truncated = reviewScope == ReviewScope.Git && gitTruncated,
                )
            } catch (e: Exception) {
                ReviewContent.Failed(e.message ?: "无法读取变更")
            }
        }
    }
    val data = (content as? ReviewContent.Ready)?.data

    // Keep folds/viewed ids pointing at real files across scope switches.
    LaunchedEffect(data?.files) {
        val ids = data?.files?.mapTo(HashSet()) { it.id }.orEmpty()
        collapsedFileIds = collapsedFileIds intersect ids
        viewedFileIds = viewedFileIds intersect ids
    }

    fun clearSelection() {
        selectedRowIds = emptySet()
        pendingAnchor = null
    }

    fun openComment(fileId: String, startIndex: Int, endIndex: Int) {
        val lines = data?.linesByFileId?.get(fileId) ?: return
        if (lines.isEmpty()) return
        val start = minOf(startIndex, endIndex).coerceIn(0, lines.lastIndex)
        val end = maxOf(startIndex, endIndex).coerceIn(0, lines.lastIndex)
        val sel = ReviewSelection(
            filePath = data.files.firstOrNull { it.id == fileId }?.path ?: return,
            lines = lines,
            startIndex = start,
            endIndex = end,
        )
        selectedRowIds = sel.selected.mapTo(HashSet()) { it.rowId }
        pendingAnchor = null
        commentSelection = sel
    }

    fun handlePress(press: DiffLinePress) {
        val target = data?.commentTargetsByRowId?.get(press.rowId) ?: return
        when (press.gesture) {
            "longPress" -> {
                pendingAnchor = press.fileId to target.lineIndex
                selectedRowIds = setOf(press.rowId)
            }
            else -> {
                val anchor = pendingAnchor
                if (anchor != null && anchor.first == press.fileId) {
                    openComment(press.fileId, anchor.second, target.lineIndex)
                } else {
                    openComment(press.fileId, target.lineIndex, target.lineIndex)
                }
            }
        }
    }

    DevinSheet(
        title = "代码审查",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- control bar -------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    TextButton(onClick = { scopeMenuOpen = true }) {
                        Text(
                            reviewScope.label(diffs),
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            HugeIcons.ChevronDown, null,
                            modifier = Modifier.size(14.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    DevinMenu(
                        expanded = scopeMenuOpen,
                        onDismissRequest = { scopeMenuOpen = false },
                    ) {
                        DevinMenuCheckItem(
                            "本轮变更",
                            checked = reviewScope is ReviewScope.Turn,
                            enabled = diffs.latestTurnKey != null,
                            onClick = {
                                scopeMenuOpen = false
                                diffs.latestTurnKey?.let { setScope(ReviewScope.Turn(it)) }
                            },
                        )
                        DevinMenuCheckItem(
                            "本会话工具变更",
                            checked = reviewScope == ReviewScope.Session,
                            enabled = diffs.files.isNotEmpty(),
                            onClick = {
                                scopeMenuOpen = false
                                setScope(ReviewScope.Session)
                            },
                        )
                        DevinMenuCheckItem(
                            "Git 未提交变更",
                            checked = reviewScope == ReviewScope.Git,
                            onClick = {
                                scopeMenuOpen = false
                                setScope(ReviewScope.Git)
                            },
                        )
                    }
                }
                data?.let {
                    Text(
                        "+${it.additions} −${it.deletions}",
                        style = monoText(MaterialTheme.typography.labelSmall),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { hideTests = !hideTests },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.SlidersHorizontal, "隐藏测试文件",
                        modifier = Modifier.size(18.dp),
                        tint = if (hideTests) cs.primary else cs.onSurfaceVariant,
                    )
                }
                // W8.2: the Git icon opens the commit sheet (stacks on top).
                if (onOpenCommit != null) {
                    IconButton(
                        onClick = onOpenCommit,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            HugeIcons.GitCommit, "提交改动",
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { fileMenuOpen = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            HugeIcons.File01, "文件",
                            modifier = Modifier.size(18.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    DevinMenu(
                        expanded = fileMenuOpen,
                        onDismissRequest = { fileMenuOpen = false },
                    ) {
                        data?.files?.forEach { file ->
                            DevinMenuItem(
                                text = file.path.substringAfterLast('/'),
                                subtitle = file.path,
                                onClick = {
                                    fileMenuOpen = false
                                    collapsedFileIds = collapsedFileIds - file.id
                                    scrollRequest = file.id
                                },
                                trailing = {
                                    Text(
                                        "+${file.additions} −${file.deletions}",
                                        style = monoText(MaterialTheme.typography.labelSmall),
                                        color = cs.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            // ---- range hint ---------------------------------------------------
            if (pendingAnchor != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainerHigh)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "选择范围终点",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { clearSelection() }) {
                        Text("取消", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            // ---- truncation notice --------------------------------------------
            if ((content as? ReviewContent.Ready)?.truncated == true) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "变更过大，diff 已截断显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            // ---- canvas --------------------------------------------------------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val c = content) {
                    ReviewContent.Loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    is ReviewContent.Failed -> Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    is ReviewContent.Ready -> {
                        if (c.data.rows.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "没有变更",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        } else {
                        val resetKey = "$reviewScope|${System.identityHashCode(c.data.rows)}"
                        val initialFileId = remember(c.data, initialFilePath) {
                            c.data.files.firstOrNull { it.path == initialFilePath }?.id
                        }
                        AndroidView(
                            factory = { ctx ->
                                ReviewDiffView(ctx).apply {
                                    onToggleFile = { id ->
                                        collapsedFileIds = toggle(collapsedFileIds, id)
                                    }
                                    onToggleViewedFile = { id ->
                                        val viewed = toggle(viewedFileIds, id)
                                        viewedFileIds = viewed
                                        // Viewed files fold like t3code's
                                        // reviewFileVisibility; unviewing
                                        // leaves the fold where the user put it.
                                        if (viewed.contains(id)) {
                                            collapsedFileIds = collapsedFileIds + id
                                        }
                                    }
                                    onPressLine = { press -> handlePress(press) }
                                    setTheme(theme)
                                    setStyle(
                                        DiffStyle.defaults(
                                            ctx.resources.displayMetrics.density,
                                        ),
                                    )
                                    initialFileId?.let { setInitialFileId(it) }
                                }
                            },
                            update = { view ->
                                view.setTheme(theme)
                                view.setContentResetKey(resetKey)
                                view.setCollapsedFileIds(collapsedFileIds)
                                view.setViewedFileIds(viewedFileIds)
                                view.setSelectedRowIds(selectedRowIds)
                                view.setData(c.data.rows)
                                scrollRequest?.let { fileId ->
                                    view.scrollToFile(fileId, animated = !reducedMotion)
                                    scrollRequest = null
                                }
                            },
                            onRelease = { view -> view.cleanup() },
                            modifier = Modifier.fillMaxSize(),
                        )
                        }
                    }
                }
            }
        }
    }

    commentSelection?.let { sel ->
        ReviewCommentSheet(
            selection = sel,
            onSubmit = { comment ->
                commentSelection = null
                clearSelection()
                onSubmitComment(sel, comment)
            },
            onDismiss = {
                commentSelection = null
                clearSelection()
            },
        )
    }
}
