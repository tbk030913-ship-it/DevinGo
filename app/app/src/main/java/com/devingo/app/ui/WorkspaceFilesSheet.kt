package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.acp.FsDirEntry
import com.devingo.app.acp.FsDirListing
import com.devingo.app.acp.FsSearchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronRight
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Search01

// ---- W8.1's 文件 sheet -----------------------------------------------------
//
// A lazy workspace tree: one /fs/list dir-mode call per expanded folder,
// folders first, alpha inside a level, git-ignored entries dimmed. The top
// search box hits /fs/search directly (the @-picker's flat feed); a file
// tap opens SourceFileViewer, a dir tap jumps the tree to that path.

private const val SEARCH_DEBOUNCE_MS = 140L

/** One visible row — a tree entry or a status line inside an expanded dir. */
private data class TreeRow(
    /** Null for status rows (loading / error / empty). */
    val entry: FsDirEntry?,
    val depth: Int,
    val status: Status,
    /** The listed dir this status belongs to (entry rows: ""). */
    val dirPath: String = "",
) {
    enum class Status { Entry, Loading, Error, Empty }
    val key: String get() = entry?.relPath ?: "status:$dirPath"
}

/**
 * The 文件 sheet: search box over a lazy, expandable workspace tree.
 * [onListDir] is the store's `/fs/list` dir mode; [onSearch] the flat
 * `/fs/search` feed. [onOpenFile] opens the source viewer for a relPath.
 */
@Composable
internal fun WorkspaceFilesSheet(
    onSearch: suspend (String) -> List<FsSearchItem>,
    onListDir: suspend (String) -> FsDirListing,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<FsSearchItem>?>(null) }
    var searchFailed by remember { mutableStateOf(false) }

    // dirPath → its listed children; keys absent while unfetched.
    val children = remember { mutableStateMapOf<String, List<FsDirEntry>>() }
    val expanded = remember { mutableStateSetOf<String>() }
    val loading = remember { mutableStateSetOf<String>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    fun fetchDir(path: String) {
        if (!loading.add(path)) return
        scope.launch {
            try {
                val listing = onListDir(path)
                // Folders first, alpha within a level — the bridge already
                // sorts, client re-sorts to stay order-stable across impls.
                children[path] = listing.entries.sortedWith(
                    compareBy({ !it.isDir }, { it.name.lowercase() }),
                )
                errors.remove(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors[path] = e.message ?: "加载失败"
            } finally {
                loading -= path
            }
        }
    }
    LaunchedEffect(Unit) { fetchDir("") }

    // Search mode — same debounce as the @-picker.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            searchResults = null
            searchFailed = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        try {
            searchResults = onSearch(query)
            searchFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            searchResults = emptyList()
            searchFailed = true
        }
    }

    fun toggleDir(path: String) {
        if (!expanded.add(path)) {
            expanded -= path
            return
        }
        if (children[path] == null && errors[path] == null) fetchDir(path)
    }

    // A search-hit dir jump: expand every ancestor (fetching missing
    // levels) so the path lands visible in the tree.
    fun expandToDir(relPath: String) {
        query = ""
        var cur = ""
        for (seg in relPath.split('/')) {
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            if (expanded.add(cur) && children[cur] == null) fetchDir(cur)
        }
    }

    val rows by remember {
        derivedStateOf {
            val out = mutableListOf<TreeRow>()
            fun walk(dir: String, depth: Int) {
                val kids = children[dir]
                when {
                    kids == null && dir in loading ->
                        out += TreeRow(null, depth, TreeRow.Status.Loading, dir)
                    kids == null && errors[dir] != null ->
                        out += TreeRow(null, depth, TreeRow.Status.Error, dir)
                    kids != null && kids.isEmpty() ->
                        out += TreeRow(null, depth, TreeRow.Status.Empty, dir)
                    kids != null -> kids.forEach { e ->
                        out += TreeRow(e, depth, TreeRow.Status.Entry)
                        if (e.isDir && e.relPath in expanded) {
                            walk(e.relPath, depth + 1)
                        }
                    }
                }
            }
            walk("", 0)
            out
        }
    }

    DevinSheet(
        title = "工作区文件",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "搜索文件或目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.outline,
                )
            },
            leadingIcon = {
                Icon(
                    HugeIcons.Search01, null,
                    modifier = Modifier.size(18.dp),
                    tint = cs.onSurfaceVariant,
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = cs.outlineVariant,
                focusedContainerColor = cs.surfaceContainerLowest,
                unfocusedContainerColor = cs.surfaceContainerLowest,
            ),
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            if (query.isNotBlank()) {
                // Search mode — the flat /fs/search feed.
                val hits = searchResults
                when {
                    hits == null -> item("hint:loading") {
                        HintRow("正在搜索工作区…")
                    }
                    searchFailed -> item("hint:error") {
                        HintRow("无法搜索工作区文件")
                    }
                    hits.isEmpty() -> item("hint:empty") {
                        HintRow("没有匹配项")
                    }
                    else -> items(hits, key = { "hit:${it.relPath}" }) { item ->
                        SearchHitRow(
                            item = item,
                            onClick = {
                                if (item.isDir) {
                                    expandToDir(item.relPath)
                                } else {
                                    onOpenFile(item.relPath)
                                }
                            },
                        )
                    }
                }
            } else {
                items(rows, key = { it.key }) { row ->
                    when (row.status) {
                        TreeRow.Status.Entry -> {
                            val e = row.entry!!
                            TreeEntryRow(
                                entry = e,
                                depth = row.depth,
                                expanded = e.relPath in expanded,
                                loading = e.relPath in loading,
                                onClick = {
                                    if (e.isDir) {
                                        toggleDir(e.relPath)
                                    } else {
                                        onOpenFile(e.relPath)
                                    }
                                },
                            )
                        }
                        TreeRow.Status.Loading -> HintRow(
                            "加载中…",
                            depth = row.depth,
                        )
                        TreeRow.Status.Error -> ErrorDirRow(
                            message = errors[row.dirPath] ?: "加载失败",
                            depth = row.depth,
                            onRetry = { fetchDir(row.dirPath) },
                        )
                        TreeRow.Status.Empty -> HintRow("空目录", depth = row.depth)
                    }
                }
            }
        }
    }
}

/** One tree row: indent + chevron (dirs) + icon + name; ignored → dimmed. */
@Composable
private fun TreeEntryRow(
    entry: FsDirEntry,
    depth: Int,
    expanded: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dimmed = entry.ignored
    val nameColor = if (dimmed) cs.onSurfaceVariant.copy(alpha = 0.55f) else cs.onSurface
    val iconTint = if (dimmed) {
        cs.onSurfaceVariant.copy(alpha = 0.45f)
    } else {
        cs.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = (depth * 14).dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chevron column keeps files and folders aligned.
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (entry.isDir) {
                Icon(
                    if (expanded) HugeIcons.ChevronDown else HugeIcons.ChevronRight,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = iconTint,
                )
            }
        }
        Icon(
            if (entry.isDir) HugeIcons.Folder01 else HugeIcons.File01,
            null,
            modifier = Modifier.size(16.dp),
            tint = iconTint,
        )
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(14.dp),
                strokeWidth = 1.5.dp,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** A flat /fs/search hit: icon + mono relPath (dirs get a trailing `/`). */
@Composable
private fun SearchHitRow(item: FsSearchItem, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDir) HugeIcons.Folder01 else HugeIcons.File01,
            null,
            modifier = Modifier.size(16.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            if (item.isDir) item.relPath + "/" else item.relPath,
            style = monoText(MaterialTheme.typography.bodyMedium),
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** Centred hint line for loading/empty states (depth-indented in trees). */
@Composable
private fun HintRow(text: String, depth: Int = 0) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A failed dir level: message + retry affordance. */
@Composable
private fun ErrorDirRow(message: String, depth: Int, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "无法加载目录 · $message",
            style = MaterialTheme.typography.bodySmall,
            color = cs.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            "重试",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurface,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceContainerHighest)
                .clickable(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
