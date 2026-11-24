package com.devingo.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AtSign
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileText
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GitBranch
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Map
import me.rerere.hugeicons.stroke.MessagesSquare
import me.rerere.hugeicons.stroke.Scroll01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Terminal

// ---- composer context tokens (N6.2) ------------------------------------------
//
// The draft's wire text stays plain: `@{kind}:{ref}` inline tokens (e.g.
// `@file:app/build.gradle.kts`, `@rule:codegraph`, `@skill:review`,
// `@session:sid`) plus bare `@path` mentions are rendered by the native
// editor as accent chips — t3code's composer token model, reduced to
// presentation only. Chip removal deletes [start, end); tapping opens
// ComposerContextSheet.

internal data class ChipToken(
    val kind: String,
    /** Exact matched text — the span's validity check and removal range. */
    val source: String,
    /** Chip text: basename for file-ish kinds, ref for the rest. */
    val label: String,
    /** The token payload (path / id / url) — also the sheet's detail line. */
    val detail: String,
    val start: Int,
    val end: Int,
)

/** kind → accent (t3code's CONTEXT_CHIP_PRESENTATIONS sRGB set) + HugeIcons glyph. */
internal fun chipPresentation(kind: String): Pair<Int, ImageVector> = when (kind) {
    "file" -> 0xFF0090CD.toInt() to HugeIcons.File01
    "folder", "directory" -> 0xFF0090CD.toInt() to HugeIcons.Folder01
    "image" -> 0xFF0090CD.toInt() to HugeIcons.Image01
    "rule", "doc" -> 0xFFB87501.toInt() to HugeIcons.Scroll01
    "skill" -> 0xFFB261BE.toInt() to HugeIcons.Sparkles
    "plan" -> 0xFF3D8BFD.toInt() to HugeIcons.FileText
    "git" -> 0xFFF05033.toInt() to HugeIcons.GitBranch
    "codemap" -> 0xFF009F6E.toInt() to HugeIcons.Map
    "url" -> 0xFF009C96.toInt() to HugeIcons.Link01
    "web" -> 0xFF009C96.toInt() to HugeIcons.Globe02
    "session", "conversation" -> 0xFF7079E4.toInt() to HugeIcons.MessagesSquare
    "terminal" -> 0xFF009F6E.toInt() to HugeIcons.Terminal
    else -> 0xFF7F8793.toInt() to HugeIcons.AtSign
}

/**
 * `@{kind}:` token start — the ref runs to the next whitespace (or a known
 * path). The kind vocabulary covers devin's advertised mentionTypes
 * (file/directory/git/rule/skill/plan/conversation/terminal/codemap —
 * A7.3) plus the generic link-ish kinds.
 */
internal val kindedTokenStart = Regex(
    "@(file|folder|directory|rule|skill|plan|session|conversation|git|" +
        "codemap|url|image|doc|symbol|task|terminal|web|issue|" +
        "pull-request|review-comment):",
)

/**
 * Bare `@mention` — kept from the pre-chip styling rule: a post-whitespace
 * `@token` that looks path-ish (`/` or `.` inside) chips as a file.
 */
private val mentionTokenRegex = Regex("(^|\\s)(@\\S+)")

/** `#L10` / `#L10-L25` anchor a source quote pins after a file ref. */
private val lineAnchor = Regex("#L\\d+(?:-L\\d+)?")

private fun nonWhitespaceEnd(text: String, from: Int): Int {
    var e = from
    while (e < text.length && !text[e].isWhitespace()) e++
    return e
}

private fun basename(ref: String): String =
    ref.substringAfterLast('/').substringAfterLast('\\').ifEmpty { ref }

/**
 * Scan [text] for chip tokens — kinded `@{kind}:{ref}` first, then bare
 * `@path` mentions in the gaps (t3's mention kind folds into "file" here).
 * [knownFiles] is the workspace file list (/fs/list): a `@file:` ref whose
 * path contains spaces is matched against it longest-first; without it the
 * ref runs to the next whitespace.
 */
internal fun scanContextTokens(
    text: String,
    knownFiles: List<String> = emptyList(),
): List<ChipToken> {
    if (text.isEmpty()) return emptyList()
    val taken = mutableListOf<IntRange>()
    val out = mutableListOf<ChipToken>()
    for (m in kindedTokenStart.findAll(text)) {
        // A mid-word `@` (emails, handles) is not a token.
        if (m.range.first > 0 && !text[m.range.first - 1].isWhitespace()) continue
        val kind = m.groupValues[1]
        val refStart = m.range.last + 1
        var end = nonWhitespaceEnd(text, refStart)
        if ((kind == "file" || kind == "folder" || kind == "directory") &&
            knownFiles.isNotEmpty()
        ) {
            var best = -1
            for (f in knownFiles) {
                val fe = refStart + f.length
                if (f.length > best && fe <= text.length &&
                    text.startsWith(f, refStart) &&
                    (fe == text.length || text[fe].isWhitespace())
                ) {
                    best = f.length
                }
            }
            if (best > 0) end = refStart + best
        }
        // A source-viewer quote anchors the chip: @file:path#L10-L25 — the
        // anchor isn't part of the matched path but must ride the token.
        if (end > refStart) {
            lineAnchor.find(text, end)
                ?.takeIf { it.range.first == end }
                ?.let { end = it.range.last + 1 }
        }
        if (end <= refStart) continue
        val ref = text.substring(refStart, end)
        out += ChipToken(
            kind = kind,
            source = text.substring(m.range.first, end),
            label = if (kind == "file" || kind == "folder" ||
                kind == "directory" || kind == "image"
            ) {
                basename(ref)
            } else {
                ref
            },
            detail = ref,
            start = m.range.first,
            end = end,
        )
        taken += m.range.first..end
    }
    for (m in mentionTokenRegex.findAll(text)) {
        val g = m.groups[2] ?: continue
        val rest = g.value.removePrefix("@")
        if (rest.contains(':')) continue // kinded territory, handled above
        if (!rest.any { it == '/' || it == '\\' || it == '.' }) continue
        if (taken.any { g.range.first < it.last && g.range.last + 1 > it.first }) continue
        out += ChipToken(
            kind = "file",
            source = g.value,
            label = basename(rest),
            detail = rest,
            start = g.range.first,
            end = g.range.last + 1,
        )
    }
    return out.sortedBy { it.start }
}
