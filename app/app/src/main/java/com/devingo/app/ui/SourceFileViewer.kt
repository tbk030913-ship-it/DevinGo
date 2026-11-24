package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.acp.FsReadResult
import kotlinx.coroutines.CancellationException
import me.rerere.bettertext.highlight.rememberHighlightedCode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.QuoteUp
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.LocalDarkMode

// ---- W8.1's source viewer ---------------------------------------------------
//
// Full-height DevinSheet over one workspace file: line-number gutter,
// bettertext syntax highlighting, a `#L123` deep-link scroll + highlight at
// 10% theme alpha, and long-press-drag line selection whose 「引用第 X–Y 行
// 到对话」 action hands `@file:path#L10-L25` back to the composer draft.

/** What the viewer opens on — [line] is the optional 1-based #L anchor. */
internal data class SourceTarget(val path: String, val line: Int? = null)

/** Per-line display cap — bounds the layout cost of minified sources. */
private const val SOURCE_LINE_DISPLAY_CAP = 2_000

/** file extension → the bettertext language name (subset it ships). */
private fun languageForPath(path: String): String {
    val base = path.substringAfterLast('/')
    if (base.equals("dockerfile", true) ||
        base.startsWith("dockerfile.", ignoreCase = true)
    ) {
        return "dockerfile"
    }
    if (base.equals("cmakelists.txt", true)) return "cmake"
    return when (base.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "py", "pyw" -> "python"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx", "mts", "cts" -> "typescript"
        "go" -> "go"
        "rs" -> "rust"
        "c", "h" -> "c"
        "cc", "cpp", "cxx", "hpp", "hh" -> "cpp"
        "cs" -> "csharp"
        "css" -> "css"
        "dart" -> "dart"
        "json" -> "json"
        "md", "markdown" -> "markdown"
        "php" -> "php"
        "ps1", "psm1" -> "powershell"
        "rb" -> "ruby"
        "sh", "bash", "zsh" -> "bash"
        "sql" -> "sql"
        "swift" -> "swift"
        "xml", "html", "htm", "svg" -> "xml"
        "yaml", "yml" -> "yaml"
        "toml", "ini", "cfg", "conf" -> "ini"
        "properties" -> "properties"
        "lua" -> "lua"
        "glsl", "vert", "frag" -> "glsl"
        "cmake" -> "cmake"
        "diff", "patch" -> "diff"
        "tex" -> "latex"
        else -> "plaintext"
    }
}

/**
 * The viewer: [target] names the workspace-relative file (+ optional #L
 * anchor); [onLoad] is the store's `/fs/read`; [onQuote] returns a selected
 * 1-based line range for the composer draft.
 */
@Composable
internal fun SourceFileViewer(
    target: SourceTarget,
    onLoad: suspend (String) -> FsReadResult,
    onQuote: (path: String, start: Int, end: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val palette = if (LocalDarkMode.current) AtomOneDarkPalette else AtomOneLightPalette

    var content by remember(target.path) { mutableStateOf<String?>(null) }
    var truncated by remember(target.path) { mutableStateOf(false) }
    var binaryMime by remember(target.path) { mutableStateOf<String?>(null) }
    var error by remember(target.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(target.path) {
        try {
            val r = onLoad(target.path)
            if (r.binary) {
                binaryMime = r.mime ?: "application/octet-stream"
            } else {
                content = r.content.orEmpty()
                truncated = r.truncated
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }
    // A trailing newline would mint a phantom empty last row.
    val lines = remember(content) { content?.removeSuffix("\n")?.lines().orEmpty() }
    val language = remember(target.path) { languageForPath(target.path) }
    val listState = rememberLazyListState()

    // #L anchor: scroll once content lands; the line keeps a 10% accent.
    var anchorLine by remember(target.path, target.line) {
        mutableIntStateOf(target.line ?: 0)
    }
    LaunchedEffect(content) {
        if (content == null || anchorLine <= 0 || lines.isEmpty()) {
            return@LaunchedEffect
        }
        listState.scrollToItem((anchorLine - 1).coerceIn(0, lines.lastIndex))
    }

    // Long-press-drag line selection — 1-based, anchor..end.
    var selAnchor by remember(target.path) { mutableIntStateOf(0) }
    var selEnd by remember(target.path) { mutableIntStateOf(0) }
    val selecting = selAnchor > 0
    val selLo = minOf(selAnchor, selEnd)
    val selHi = maxOf(selAnchor, selEnd)

    fun lineAt(y: Float): Int? {
        val info = listState.layoutInfo
        return info.visibleItemsInfo
            .firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?.index?.plus(1)
    }

    // Edge auto-scroll: a drag held near the viewport edge keeps the list
    // moving and the selection following the finger (W8.1 review).
    var dragging by remember(target.path) { mutableStateOf(false) }
    var dragY by remember(target.path) { mutableStateOf(0f) }
    val edgePx = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(dragging) {
        while (dragging) {
            val vh = listState.layoutInfo.viewportSize.height.toFloat()
            val delta = when {
                dragY < edgePx -> -16f
                dragY > vh - edgePx -> 16f
                else -> 0f
            }
            if (delta != 0f) {
                listState.scroll { scrollBy(delta) }
                // The row under the finger changed — keep extending.
                lineAt(dragY)?.let { selEnd = it }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    DevinSheet(
        title = target.path,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                content != null -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(target.path) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    dragY = offset.y
                                    dragging = true
                                    lineAt(offset.y)?.let {
                                        selAnchor = it
                                        selEnd = it
                                    }
                                },
                                onDrag = { change, _ ->
                                    dragY = change.position.y
                                    lineAt(change.position.y)?.let { selEnd = it }
                                    change.consume()
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = {
                                    dragging = false
                                    selAnchor = 0
                                    selEnd = 0
                                },
                            )
                        },
                ) {
                    items(
                        count = lines.size,
                        key = { it + 1 },
                    ) { index ->
                        val ln = index + 1
                        val lineBg = when {
                            selecting && ln in selLo..selHi ->
                                cs.primary.copy(alpha = 0.10f)
                            ln == anchorLine -> cs.primary.copy(alpha = 0.10f)
                            else -> null
                        }
                        SourceLine(
                            number = ln,
                            digits = lines.size.toString().length,
                            text = lines[index],
                            language = language,
                            palette = palette,
                            background = lineBg,
                            // Only interactive while a selection is live —
                            // a tap then re-sets its end (extend/shrink).
                            onClick = if (selecting) ({ selEnd = ln }) else null,
                        )
                    }
                }
                binaryMime != null -> ViewerNotice(
                    icon = { Icon(HugeIcons.File01, null, Modifier.size(28.dp), tint = cs.onSurfaceVariant) },
                    text = "二进制文件 · $binaryMime",
                )
                error != null -> ViewerNotice(text = "读取失败：$error")
                else -> ViewerNotice(loading = true)
            }
            if (truncated && content != null) {
                Text(
                    "文件过大，仅显示前 1MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceContainerHighest)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (selecting && lines.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                    shape = CircleShape,
                    color = cs.surfaceContainerLowest,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            HugeIcons.Cancel01, "清除选择",
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable {
                                    selAnchor = 0
                                    selEnd = 0
                                }
                                .padding(5.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        Surface(
                            onClick = {
                                onQuote(target.path, selLo, selHi)
                            },
                            shape = CircleShape,
                            color = cs.primary,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    HugeIcons.QuoteUp, null,
                                    modifier = Modifier.size(14.dp),
                                    tint = cs.onPrimary,
                                )
                                Text(
                                    if (selLo == selHi) {
                                        "引用第 $selLo 行到对话"
                                    } else {
                                        "引用第 $selLo–$selHi 行到对话"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = cs.onPrimary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One code line: gutter number + syntax-highlighted text, soft-wrapped. */
@Composable
private fun SourceLine(
    number: Int,
    digits: Int,
    text: String,
    language: String,
    palette: me.rerere.bettertext.highlight.HighlightTextColorPalette,
    background: androidx.compose.ui.graphics.Color?,
    onClick: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (background != null) Modifier.background(background) else Modifier,
            )
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(vertical = 1.dp),
    ) {
        Text(
            number.toString().padStart(digits, ' '),
            style = monoText(MaterialTheme.typography.bodySmall).copy(fontSize = 11.sp),
            color = cs.onSurfaceVariant.copy(alpha = 0.4f),
            textAlign = TextAlign.End,
            softWrap = false,
            modifier = Modifier.padding(end = 12.dp),
        )
        // A single minified line can carry the file's whole 1 MiB — a
        // MultiParagraph that size stalls Main for seconds (W8.1 review).
        val shown = text.take(SOURCE_LINE_DISPLAY_CAP)
        Text(
            rememberHighlightedCode(shown.ifEmpty { " " }, language, palette),
            style = monoText(MaterialTheme.typography.bodySmall).copy(fontSize = 12.sp),
            softWrap = true,
        )
        if (shown.length < text.length) {
            Text(
                " ⋯",
                style = monoText(MaterialTheme.typography.bodySmall).copy(fontSize = 12.sp),
                color = cs.outline,
                softWrap = false,
            )
        }
    }
}

/** Centred state block — spinner, binary marker, or error message. */
@Composable
private fun ViewerNotice(
    icon: (@Composable () -> Unit)? = null,
    text: String = "",
    loading: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = cs.onSurfaceVariant,
            )
            icon != null -> icon()
        }
        if (text.isNotEmpty()) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
