// Diff → native-row pipeline for the review sheet — a Kotlin port of the
// t3code (MIT) review model pieces:
//   * nativeReviewDiffAdapter.ts — native row construction, word-diff
//     pairing caps, notices and comment targets
//   * reviewModel.ts            — renderable row semantics (context /
//     change segments with index-paired deletions/additions)
//   * reviewWordDiffs.ts        — computeWordAltDiffRanges span merging
//   * diffCutLines.ts           — cut-line note detection
//   * changedFileKind.ts        — isTestChangedFile classification
// Line and word diffing use a token LCS where t3code uses jsdiff/pierre —
// both produce minimal-edit alignments; the pairing contract (deletion i ↔
// addition i inside a change segment) is identical.

package com.devingo.app.ui.diff

import com.devingo.app.ToolDiff

// ---- public result ---------------------------------------------------------

/** A file row's comment target: the data the comment sheet needs to map a
 * native row back onto source lines and to extend a selection into a range. */
data class ReviewCommentTarget(
    val filePath: String,
    /** The file's line rows in order (same objects as [lines]). */
    val lineIndex: Int,
)

data class ReviewLineInfo(
    val rowId: String,
    val content: String,
    val change: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

data class ReviewFileSummary(
    val id: String,
    val path: String,
    val additions: Int,
    val deletions: Int,
)

data class NativeReviewData(
    val rows: List<DiffRow>,
    val files: List<ReviewFileSummary>,
    val commentTargetsByRowId: Map<String, ReviewCommentTarget>,
    /** fileId → that file's line rows in order — the range-comment source. */
    val linesByFileId: Map<String, List<ReviewLineInfo>>,
    val additions: Int,
    val deletions: Int,
)

// ---- renderable source model ------------------------------------------------

internal data class ReviewSourceLine(
    val change: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String,
)

internal sealed interface ReviewSourceRow {
    data class Hunk(val header: String, val context: String?) : ReviewSourceRow

    /** A contiguous context line. */
    data class Context(val line: ReviewSourceLine) : ReviewSourceRow

    /** A change block: deletions then additions, paired index-wise. */
    data class Segment(
        val deletions: List<ReviewSourceLine>,
        val additions: List<ReviewSourceLine>,
    ) : ReviewSourceRow
}

internal data class ReviewDiffFile(
    val id: String,
    val path: String,
    val previousPath: String?,
    /** modified | new | deleted | rename-pure | rename-changed */
    val changeType: String,
    val additions: Int,
    val deletions: Int,
    val rows: List<ReviewSourceRow>,
    val noEOFCRAdditions: Boolean = false,
    val noEOFCRDeletions: Boolean = false,
    /** Non-renderable content note (binary/unsupported). */
    val notice: String? = null,
)

private const val NATIVE_REVIEW_MAX_FILE_ROWS = 100_000
private const val NATIVE_REVIEW_MAX_WORD_DIFF_RANGE_COUNT = 4
private const val NATIVE_REVIEW_MAX_WORD_DIFF_COVERAGE = 0.45
private const val NATIVE_REVIEW_MAX_WORD_DIFF_COMPARISONS = 1_000

// ---- native row construction (nativeReviewDiffAdapter.kt port) -------------

private fun mapChangeType(changeType: String): String = when (changeType) {
    "modified", "new", "deleted", "rename-pure", "rename-changed" -> changeType
    else -> "modified"
}

private fun noticeRow(fileId: String, suffix: String, text: String): DiffRow =
    DiffRow(
        kind = "notice",
        id = "$fileId:notice:$suffix",
        fileId = fileId,
        filePath = "",
        previousPath = null,
        changeType = "",
        additions = 0,
        deletions = 0,
        text = text,
        content = "",
        change = "context",
        oldLineNumber = null,
        newLineNumber = null,
        wordDiffRanges = emptyList(),
        commentText = "",
        commentRangeLabel = "",
        commentSectionTitle = "",
    )

private fun noticeRowsForFile(file: ReviewDiffFile): List<DiffRow> {
    if (file.notice != null) {
        return listOf(noticeRow(file.id, "loading", file.notice))
    }
    if (file.rows.isNotEmpty()) return emptyList()
    if (file.changeType == "rename-pure") {
        return listOf(
            noticeRow(file.id, "rename", "This file was renamed without modifications."),
        )
    }
    return emptyList()
}

private val WS = Regex("\\s")

private fun trimWordDiffRanges(
    content: String,
    ranges: List<DiffWordDiffRange>,
): List<DiffWordDiffRange> = buildList {
    for (range in ranges) {
        var start = range.start.coerceAtLeast(0)
        var end = range.end.coerceAtMost(content.length)
        while (start < end && content[start].isWhitespace()) start += 1
        while (end > start && content[end - 1].isWhitespace()) end -= 1
        if (end > start) add(DiffWordDiffRange(start, end))
    }
}

private fun nonWhitespaceLength(value: String): Int = value.count { !it.isWhitespace() }

private fun shouldUseWordDiffRanges(content: String, ranges: List<DiffWordDiffRange>): Boolean {
    if (ranges.isEmpty() || ranges.size > NATIVE_REVIEW_MAX_WORD_DIFF_RANGE_COUNT) return false
    val meaningfulLength = nonWhitespaceLength(content)
    if (meaningfulLength == 0) return false
    val highlightedLength = ranges.sumOf { range ->
        nonWhitespaceLength(content.substring(range.start, range.end.coerceAtMost(content.length)))
    }
    return highlightedLength.toDouble() / meaningfulLength <= NATIVE_REVIEW_MAX_WORD_DIFF_COVERAGE
}

internal fun buildNativeReviewData(files: List<ReviewDiffFile>): NativeReviewData {
    val rows = ArrayList<DiffRow>()
    val summaries = ArrayList<ReviewFileSummary>(files.size)
    val commentTargets = LinkedHashMap<String, ReviewCommentTarget>()
    val linesByFileId = LinkedHashMap<String, List<ReviewLineInfo>>()
    var totalAdds = 0
    var totalDels = 0

    files.forEach { file ->
        summaries += ReviewFileSummary(file.id, file.path, file.additions, file.deletions)
        totalAdds += file.additions
        totalDels += file.deletions

        rows += DiffRow(
            kind = "file",
            id = "${file.id}:header",
            fileId = file.id,
            filePath = file.path,
            previousPath = file.previousPath,
            changeType = mapChangeType(file.changeType),
            additions = file.additions,
            deletions = file.deletions,
            text = "",
            content = "",
            change = "context",
            oldLineNumber = null,
            newLineNumber = null,
            wordDiffRanges = emptyList(),
            commentText = "",
            commentRangeLabel = "",
            commentSectionTitle = "",
        )

        val fileRowStart = rows.size
        val lineRows = ArrayList<ReviewLineInfo>()
        // deletion native-row index → addition native-row index (word pairing).
        val pairs = LinkedHashMap<Int, Int>()
        val sourceRows =
            if (file.rows.size > NATIVE_REVIEW_MAX_FILE_ROWS) {
                file.rows.subList(0, NATIVE_REVIEW_MAX_FILE_ROWS)
            } else {
                file.rows
            }
        var rowIndex = 0
        sourceRows.forEach { src ->
            when (src) {
                is ReviewSourceRow.Hunk -> {
                    rows += DiffRow(
                        kind = "hunk",
                        id = "${file.id}:hunk:${rowIndex}",
                        fileId = file.id,
                        filePath = file.path,
                        previousPath = null,
                        changeType = "",
                        additions = 0,
                        deletions = 0,
                        text = if (src.context != null) "${src.header} ${src.context}" else src.header,
                        content = "",
                        change = "context",
                        oldLineNumber = null,
                        newLineNumber = null,
                        wordDiffRanges = emptyList(),
                        commentText = "",
                        commentRangeLabel = "",
                        commentSectionTitle = "",
                    )
                    rowIndex += 1
                }
                is ReviewSourceRow.Context -> {
                    val (rowId, info) = mapLineRow(file, src.line, rowIndex)
                    rows += rowFromLine(file, rowId, src.line)
                    lineRows += info
                    rowIndex += 1
                }
                is ReviewSourceRow.Segment -> {
                    val delRowIndexes = ArrayList<Int>(src.deletions.size)
                    src.deletions.forEach { line ->
                        delRowIndexes += rows.size
                        val (rowId, info) = mapLineRow(file, line, rowIndex)
                        rows += rowFromLine(file, rowId, line)
                        lineRows += info
                        rowIndex += 1
                    }
                    src.additions.forEachIndexed { i, line ->
                        if (i < src.deletions.size) {
                            pairs[delRowIndexes[i]] = rows.size
                        }
                        val (rowId, info) = mapLineRow(file, line, rowIndex)
                        rows += rowFromLine(file, rowId, line)
                        lineRows += info
                        rowIndex += 1
                    }
                }
            }
        }
        lineRows.forEachIndexed { index, info ->
            commentTargets[info.rowId] = ReviewCommentTarget(file.path, index)
        }
        linesByFileId[file.id] = lineRows

        if (sourceRows.size != file.rows.size) {
            rows += noticeRow(file.id, "truncated", "This diff is too large to show in full.")
        }
        rows += noticeRowsForFile(file)
        if (file.noEOFCRAdditions || file.noEOFCRDeletions) {
            val text = when {
                file.noEOFCRAdditions && file.noEOFCRDeletions ->
                    "Old and new files have no newline at end of file."
                file.noEOFCRAdditions -> "New file has no newline at end of file."
                else -> "Old file has no newline at end of file."
            }
            rows += noticeRow(file.id, "eof", text)
        }

        // Word-diff pass (addNativeWordDiffRanges port).
        var comparisonCount = 0
        for ((delIdx, addIdx) in pairs) {
            if (comparisonCount >= NATIVE_REVIEW_MAX_WORD_DIFF_COMPARISONS) break
            val deletedRow = rows[delIdx]
            val addedRow = rows[addIdx]
            if (deletedRow.content.isEmpty() || addedRow.content.isEmpty()) continue
            comparisonCount += 1
            val ranges = computeWordAltDiffRanges(deletedRow.content, addedRow.content)
            val deletionRanges = trimWordDiffRanges(deletedRow.content, ranges.first)
            val additionRanges = trimWordDiffRanges(addedRow.content, ranges.second)
            if (shouldUseWordDiffRanges(deletedRow.content, deletionRanges)) {
                rows[delIdx] = deletedRow.copy(wordDiffRanges = deletionRanges)
            }
            if (shouldUseWordDiffRanges(addedRow.content, additionRanges)) {
                rows[addIdx] = addedRow.copy(wordDiffRanges = additionRanges)
            }
        }
        // A merged no-op diff (old == new) is header-only — legal to show.
    }

    return NativeReviewData(
        rows = rows,
        files = summaries,
        commentTargetsByRowId = commentTargets,
        linesByFileId = linesByFileId,
        additions = totalAdds,
        deletions = totalDels,
    )
}

private fun mapLineRow(
    file: ReviewDiffFile,
    line: ReviewSourceLine,
    rowIndex: Int,
): Pair<String, ReviewLineInfo> {
    val rowId = "${file.id}:line:${rowIndex}"
    return rowId to ReviewLineInfo(
        rowId = rowId,
        content = line.content,
        change = line.change,
        oldLineNumber = line.oldLineNumber,
        newLineNumber = line.newLineNumber,
    )
}

private fun rowFromLine(file: ReviewDiffFile, rowId: String, line: ReviewSourceLine): DiffRow =
    DiffRow(
        kind = "line",
        id = rowId,
        fileId = file.id,
        filePath = file.path,
        previousPath = null,
        changeType = "",
        additions = 0,
        deletions = 0,
        text = "",
        content = line.content,
        change = line.change,
        oldLineNumber = line.oldLineNumber,
        newLineNumber = line.newLineNumber,
        wordDiffRanges = emptyList(),
        commentText = "",
        commentRangeLabel = "",
        commentSectionTitle = "",
        noteStart = cutLineNoteStart(line.content),
    )

// ---- cut lines (diffCutLines.kt port) --------------------------------------

private val CUT_LINE_NOTE = Regex(" …\\[[\\d,]+ more characters\\]$")

/** Where a cut line's note starts, past its leading space, or null. */
internal fun cutLineNoteStart(line: String): Int? {
    if (!line.endsWith("more characters]")) return null
    val match = CUT_LINE_NOTE.find(line) ?: return null
    return match.range.first + 1
}

// ---- test-file classification (changedFileKind.kt port) ---------------------

private val GENERATED_FILES = setOf(
    "bun.lock", "bun.lockb", "cargo.lock", "composer.lock", "deno.lock",
    "flake.lock", "gemfile.lock", "go.sum", "gradle.lockfile", "mix.lock",
    "npm-shrinkwrap.json", "package-lock.json", "packages.lock.json",
    "pipfile.lock", "pnpm-lock.yaml", "podfile.lock", "poetry.lock",
    "pubspec.lock", "uv.lock", "yarn.lock",
)
private val GENERATED_NAME = Regex(
    "(\\.generated\\.|\\.gen\\.|\\.g\\.dart$|\\.pb\\.go$|_pb2(_grpc)?\\.py$" +
        "|\\.pb\\.(cc|h)$|\\.min\\.(js|css)$|\\.(js|css)\\.map$)",
)
private val GENERATED_DIRS = setOf("__generated__", "_generated", "generated")
private val MOCK_NAME = Regex(
    "(\\.snap$|\\.(mocks?|fixtures?|stubs?|fakes?)\\.[^.]+$" +
        "|^(mocks?|fixtures?|stubs?|fakes?)([-_.A-Z]|$))",
)
private val MOCK_DIRS = setOf(
    "__fixtures__", "__mocks__", "__snapshots__", "fakes",
    "fixture", "fixtures", "mock", "mocks", "stubs", "testdata",
)
private val TEST_NAME = Regex(
    "(\\.(test|spec|e2e|eval|stories)\\.[^.]+$|_(test|spec)\\.[^.]+$" +
        "|^test_.+\\.py$|^conftest\\.py$|^(vitest|jest)\\.setup\\.[^.]+$)",
    RegexOption.IGNORE_CASE,
)
private val CLASS_TEST_NAME = Regex("(Tests?|Spec)\\.(java|kt|kts|scala|groovy|cs|php|swift|m)$")
private val TEST_DIRS = setOf(
    "__tests__", "__test__", "cypress", "e2e", "evals",
    "spec", "specs", "test", "tests",
)

/** tests + mocks — t3code's isTestChangedFile, the 隐藏测试文件 toggle. */
internal fun isTestChangedFile(path: String): Boolean {
    val segments = path.replace('\\', '/').split('/')
    val name = segments.lastOrNull().orEmpty()
    val dirs = segments.dropLast(1).map { it.lowercase() }
    fun inDir(set: Set<String>) = dirs.any { it in set }
    if (GENERATED_FILES.contains(name.lowercase()) || GENERATED_NAME.containsMatchIn(name) ||
        inDir(GENERATED_DIRS)
    ) {
        return false
    }
    return MOCK_NAME.containsMatchIn(name) || inDir(MOCK_DIRS) ||
        TEST_NAME.containsMatchIn(name) || CLASS_TEST_NAME.containsMatchIn(name) ||
        inDir(TEST_DIRS)
}

// ---- old/new text → file rows ----------------------------------------------

private const val CONTEXT_LINES = 3
/**
 * Above this cell budget the LCS falls back to delete-all + add-all for the
 * divergent middle — still a correct diff, just not minimal.
 */
private const val LCS_MAX_CELLS = 50_000_000L

/** Merge a file's tool diffs and render old/new as hunks (ToolDiff scope). */
internal fun buildReviewFileFromTexts(
    id: String,
    path: String,
    oldText: String?,
    newText: String,
): ReviewDiffFile {
    val oldLines = oldText?.removeSuffix("\n")?.takeIf { it.isNotEmpty() || oldText.isNotEmpty() }
        ?.split("\n").orEmpty()
    val newLines = newText.removeSuffix("\n").takeIf { it.isNotEmpty() || newText.isNotEmpty() }
        ?.split("\n").orEmpty()
    val ops = lcsOps(oldLines, newLines)
    val rows = hunksFromOps(ops, oldLines, newLines)
    var adds = 0
    var dels = 0
    ops.forEach {
        if (it.kind == DiffOpKind.ADD) adds++ else if (it.kind == DiffOpKind.DELETE) dels++
    }
    val changeType = when {
        oldText == null -> "new"
        newLines.isEmpty() && oldLines.isNotEmpty() -> "deleted"
        else -> "modified"
    }
    return ReviewDiffFile(
        id = id,
        path = path,
        previousPath = null,
        changeType = changeType,
        additions = adds,
        deletions = dels,
        rows = rows,
        noEOFCRDeletions = oldText != null && oldText.isNotEmpty() && !oldText.endsWith("\n"),
        noEOFCRAdditions = newText.isNotEmpty() && !newText.endsWith("\n"),
    )
}

private enum class DiffOpKind { CONTEXT, DELETE, ADD }

private data class DiffOp(
    val kind: DiffOpKind,
    val oldIndex: Int?, // 0-based line index into the old lines
    val newIndex: Int?,
)

private fun lcsOps(a: List<String>, b: List<String>): List<DiffOp> {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (
        suffix < a.size - prefix && suffix < b.size - prefix &&
        a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
    ) {
        suffix++
    }
    val out = ArrayList<DiffOp>(prefix + suffix + 16)
    for (i in 0 until prefix) out += DiffOp(DiffOpKind.CONTEXT, i, i)
    val am = a.subList(prefix, a.size - suffix)
    val bm = b.subList(prefix, b.size - suffix)
    when {
        am.isEmpty() -> bm.forEachIndexed { i, _ ->
            out += DiffOp(DiffOpKind.ADD, null, prefix + i)
        }
        bm.isEmpty() -> am.forEachIndexed { i, _ ->
            out += DiffOp(DiffOpKind.DELETE, prefix + i, null)
        }
        am.size.toLong() * bm.size.toLong() > LCS_MAX_CELLS -> {
            am.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.DELETE, prefix + i, null) }
            bm.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.ADD, null, prefix + i) }
        }
        else -> lcsOpsMiddle(am, bm, out, prefix)
    }
    for (i in 0 until suffix) {
        out += DiffOp(DiffOpKind.CONTEXT, a.size - suffix + i, b.size - suffix + i)
    }
    return out
}

/** Hirschberg LCS: O(m) space, emits ops consuming a/b in order. */
private fun lcsOpsMiddle(
    a: List<String>,
    b: List<String>,
    out: MutableList<DiffOp>,
    base: Int,
) {
    if (a.isEmpty()) {
        b.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.ADD, null, base + i) }
        return
    }
    if (b.isEmpty()) {
        a.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.DELETE, base + i, null) }
        return
    }
    if (a.size == 1) {
        val idx = b.indexOf(a[0])
        if (idx < 0) {
            out += DiffOp(DiffOpKind.DELETE, base, null)
            b.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.ADD, null, base + i) }
        } else {
            repeat(idx) { out += DiffOp(DiffOpKind.ADD, null, base + it) }
            out += DiffOp(DiffOpKind.CONTEXT, base, base + idx)
            for (i in idx + 1 until b.size) {
                out += DiffOp(DiffOpKind.ADD, null, base + i)
            }
        }
        return
    }
    if (b.size == 1) {
        val idx = a.indexOf(b[0])
        if (idx < 0) {
            a.forEachIndexed { i, _ -> out += DiffOp(DiffOpKind.DELETE, base + i, null) }
            out += DiffOp(DiffOpKind.ADD, null, base)
        } else {
            repeat(idx) { out += DiffOp(DiffOpKind.DELETE, base + it, null) }
            out += DiffOp(DiffOpKind.CONTEXT, base + idx, base)
            for (i in idx + 1 until a.size) {
                out += DiffOp(DiffOpKind.DELETE, base + i, null)
            }
        }
        return
    }
    val mid = a.size / 2
    val l1 = lcsLengths(a.subList(0, mid), b)
    val l2 = lcsLengths(a.subList(mid, a.size).asReversed(), b.asReversed())
    var bestK = 0
    var best = -1
    for (k in 0..b.size) {
        val v = l1[k] + l2[b.size - k]
        if (v > best) {
            best = v
            bestK = k
        }
    }
    lcsOpsMiddle(a.subList(0, mid), b.subList(0, bestK), out, base)
    lcsOpsMiddle(a.subList(mid, a.size), b.subList(bestK, b.size), out, base + mid)
}

/** One rolling LCS-length row: result[i] = |LCS(a, b[0..i))|. */
private fun lcsLengths(a: List<String>, b: List<String>): IntArray {
    val dp = IntArray(b.size + 1)
    for (x in a) {
        var prev = 0
        for (j in 1..b.size) {
            val t = dp[j]
            dp[j] = if (x == b[j - 1]) prev + 1 else maxOf(dp[j], dp[j - 1])
            prev = t
        }
    }
    return dp
}

private fun hunkHeaderText(oldStart: Int, oldCount: Int, newStart: Int, newCount: Int): String {
    fun side(start: Int, count: Int) = if (count == 1) "$start" else "$start,$count"
    return "@@ -${side(oldStart, oldCount)} +${side(newStart, newCount)} @@"
}

/** Group the op stream into hunks with [CONTEXT_LINES] context, git-style. */
private fun hunksFromOps(
    ops: List<DiffOp>,
    oldLines: List<String>,
    newLines: List<String>,
): List<ReviewSourceRow> {
    // Change blocks = maximal non-context runs.
    val blocks = ArrayList<Pair<Int, Int>>() // [start, end) op indices
    var i = 0
    while (i < ops.size) {
        if (ops[i].kind != DiffOpKind.CONTEXT) {
            var end = i + 1
            while (end < ops.size && ops[end].kind != DiffOpKind.CONTEXT) end++
            blocks += i to end
            i = end
        } else {
            i++
        }
    }
    if (blocks.isEmpty()) return emptyList()

    // Merge change blocks whose context gap is ≤ 2*CONTEXT_LINES.
    val merged = ArrayList<Pair<Int, Int>>()
    for (b in blocks) {
        val last = merged.lastOrNull()
        if (last != null && b.first - last.second <= CONTEXT_LINES * 2) {
            merged[merged.lastIndex] = last.first to b.second
        } else {
            merged += b
        }
    }

    val rows = ArrayList<ReviewSourceRow>()
    merged.forEachIndexed { mi, (rangeStart, rangeEnd) ->
        val prevEnd = merged.getOrNull(mi - 1)?.second ?: 0
        val nextStart = merged.getOrNull(mi + 1)?.first ?: ops.size
        val start = maxOf(rangeStart - CONTEXT_LINES, prevEnd)
        val end = minOf(rangeEnd + CONTEXT_LINES, nextStart, ops.size)

        // A pure-add hunk has no oldIndex ops (pure-delete: no newIndex) —
        // the missing side is 0 per git's `@@ -0,0 +1,N @@` convention.
        val oldStart = (ops.subList(start, end)
            .firstNotNullOfOrNull { it.oldIndex } ?: -1) + 1
        val newStart = (ops.subList(start, end)
            .firstNotNullOfOrNull { it.newIndex } ?: -1) + 1
        var oldCount = 0
        var newCount = 0
        for (k in start until end) {
            when (ops[k].kind) {
                DiffOpKind.CONTEXT -> {
                    oldCount++
                    newCount++
                }
                DiffOpKind.DELETE -> oldCount++
                DiffOpKind.ADD -> newCount++
            }
        }
        rows += ReviewSourceRow.Hunk(hunkHeaderText(oldStart, oldCount, newStart, newCount), null)

        var k = start
        while (k < end) {
            val op = ops[k]
            if (op.kind == DiffOpKind.CONTEXT) {
                rows += ReviewSourceRow.Context(
                    ReviewSourceLine(
                        change = "context",
                        oldLineNumber = (op.oldIndex ?: 0) + 1,
                        newLineNumber = (op.newIndex ?: 0) + 1,
                        content = newLines[op.newIndex ?: 0],
                    ),
                )
                k++
            } else {
                var segEnd = k + 1
                while (segEnd < end && ops[segEnd].kind != DiffOpKind.CONTEXT) segEnd++
                val dels = ArrayList<ReviewSourceLine>()
                val adds = ArrayList<ReviewSourceLine>()
                for (j in k until segEnd) {
                    val o = ops[j]
                    if (o.kind == DiffOpKind.DELETE) {
                        dels += ReviewSourceLine(
                            change = "delete",
                            oldLineNumber = (o.oldIndex ?: 0) + 1,
                            newLineNumber = null,
                            content = oldLines[o.oldIndex ?: 0],
                        )
                    } else {
                        adds += ReviewSourceLine(
                            change = "add",
                            oldLineNumber = null,
                            newLineNumber = (o.newIndex ?: 0) + 1,
                            content = newLines[o.newIndex ?: 0],
                        )
                    }
                }
                rows += ReviewSourceRow.Segment(dels, adds)
                k = segEnd
            }
        }
    }
    return rows
}

// ---- unified patch parsing (git diff) ---------------------------------------

private val HUNK_HEADER = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@( (.*))?$")

// `diff --git a/x b/y` (each side possibly C-quoted) — binary files carry
// their paths here only.
private val GIT_HEADER =
    Regex("""diff --git ("(?:[^"\\]|\\.)*"|a/\S+) ("(?:[^"\\]|\\.)*"|b/\S+)""")

/** Parse `git diff` output into renderable files. */
internal fun parseGitPatch(patch: String): List<ReviewDiffFile> {
    val files = ArrayList<ReviewDiffFile>()
    val lines = patch.replace("\r\n", "\n").split("\n")
    var i = 0
    var fileIndex = 0
    while (i < lines.size) {
        val line = lines[i]
        if (!line.startsWith("diff --git ")) {
            i++
            continue
        }
        fileIndex++
        // Binary entries carry no ---/+++ lines — the diff --git header is
        // their only path record, so capture it up front.
        val hdr = GIT_HEADER.find(line)
        i++
        var newPath = ""
        var prevPath: String? = null
        // The `--- a/x` side — the only path a deleted file carries.
        var minusPath: String? = null
        var inHunk = false
        var changeType = "modified"
        var notice: String? = null
        val rows = ArrayList<ReviewSourceRow>()
        var adds = 0
        var dels = 0
        var eofAdd = false
        var eofDel = false
        var lastMarker: Char? = null
        var oldLine = 0
        var newLine = 0
        var seenPlusPlusPlus = false

        while (i < lines.size && !lines[i].startsWith("diff --git ")) {
            val l = lines[i]
            when {
                l.startsWith("new file mode") -> changeType = "new"
                l.startsWith("deleted file mode") -> changeType = "deleted"
                l.startsWith("rename from ") -> prevPath = l.removePrefix("rename from ")
                l.startsWith("rename to ") -> {
                    newPath = l.removePrefix("rename to ")
                    changeType = "rename-pure"
                }
                l.startsWith("similarity index 100%") ||
                    l.startsWith("Binary files ") ||
                    l.startsWith("GIT binary patch") -> {
                    if (l.startsWith("Binary") || l.startsWith("GIT binary")) {
                        notice = "Unsupported format. Diff contents are not available."
                    }
                }
                // ---/+++ lines inside a hunk are content, not headers —
                // a deleted "-- x" line or an added "++ y" must not be
                // swallowed. The real headers only precede the first @@.
                !inHunk && l.startsWith("--- ") -> {
                    val p = l.removePrefix("--- ")
                    if (p != "/dev/null") minusPath = stripPatchPrefix(p)
                }
                !inHunk && l.startsWith("+++ ") -> {
                    seenPlusPlusPlus = true
                    val p = l.removePrefix("+++ ")
                    if (p != "/dev/null") newPath = stripPatchPrefix(p)
                }
                l.startsWith("@@ ") -> {
                    inHunk = true
                    val m = HUNK_HEADER.find(l)
                    if (m != null) {
                        oldLine = m.groupValues[1].toInt()
                        newLine = m.groupValues[3].toInt()
                        val ctx = m.groupValues[7].takeIf { it.isNotEmpty() }
                        rows += ReviewSourceRow.Hunk(
                            header = "@@ -${m.groupValues[1]},${m.groupValues[2].ifEmpty { "1" }} " +
                                "+${m.groupValues[3]},${m.groupValues[4].ifEmpty { "1" }} @@",
                            context = ctx,
                        )
                    }
                }
                l.startsWith("\\") -> {
                    when (lastMarker) {
                        '+' -> eofAdd = true
                        '-' -> eofDel = true
                        ' ' -> {
                            eofAdd = true
                            eofDel = true
                        }
                        else -> Unit
                    }
                }
                l.isNotEmpty() -> {
                    when (l[0]) {
                        ' ' -> {
                            rows += ReviewSourceRow.Context(
                                ReviewSourceLine(
                                    change = "context",
                                    oldLineNumber = oldLine,
                                    newLineNumber = newLine,
                                    content = l.substring(1),
                                ),
                            )
                            oldLine++
                            newLine++
                            lastMarker = ' '
                        }
                        '-' -> {
                            // Coalesce the pending change segment.
                            val prev = rows.lastOrNull() as? ReviewSourceRow.Segment
                            if (prev != null && prev.additions.isEmpty()) {
                                rows[rows.lastIndex] = prev.copy(
                                    deletions = prev.deletions + ReviewSourceLine(
                                        change = "delete",
                                        oldLineNumber = oldLine,
                                        newLineNumber = null,
                                        content = l.substring(1),
                                    ),
                                )
                            } else {
                                rows += ReviewSourceRow.Segment(
                                    deletions = listOf(
                                        ReviewSourceLine(
                                            change = "delete",
                                            oldLineNumber = oldLine,
                                            newLineNumber = null,
                                            content = l.substring(1),
                                        ),
                                    ),
                                    additions = emptyList(),
                                )
                            }
                            dels++
                            oldLine++
                            lastMarker = '-'
                        }
                        '+' -> {
                            val prev = rows.lastOrNull() as? ReviewSourceRow.Segment
                            if (prev != null) {
                                rows[rows.lastIndex] = prev.copy(
                                    additions = prev.additions + ReviewSourceLine(
                                        change = "add",
                                        oldLineNumber = null,
                                        newLineNumber = newLine,
                                        content = l.substring(1),
                                    ),
                                )
                            } else {
                                rows += ReviewSourceRow.Segment(
                                    deletions = emptyList(),
                                    additions = listOf(
                                        ReviewSourceLine(
                                            change = "add",
                                            oldLineNumber = null,
                                            newLineNumber = newLine,
                                            content = l.substring(1),
                                        ),
                                    ),
                                )
                            }
                            adds++
                            newLine++
                            lastMarker = '+'
                        }
                    }
                }
            }
            i++
        }
        if (!seenPlusPlusPlus && prevPath == null && minusPath == null) {
            // Only a binary/unsupported entry reaches here with a notice —
            // surface it under the header's paths instead of dropping it.
            if (notice == null || hdr == null) continue
            minusPath = stripPatchPrefix(hdr.groupValues[1])
            newPath = stripPatchPrefix(hdr.groupValues[2])
        }
        if (prevPath != null && changeType == "rename-pure" && rows.isNotEmpty()) {
            changeType = "rename-changed"
        }
        // Deleted files have no +++ path; renamed files carry rename from.
        val path = newPath.ifEmpty { prevPath ?: minusPath.orEmpty() }
        if (path.isEmpty()) continue
        files += ReviewDiffFile(
            // Path-keyed ids: viewed/collapse state survives rebuilds and
            // scope switches instead of aliasing a positional index.
            id = "f:$path",
            path = path,
            previousPath = prevPath,
            changeType = changeType,
            additions = adds,
            deletions = dels,
            rows = rows,
            noEOFCRAdditions = eofAdd,
            noEOFCRDeletions = eofDel,
            notice = notice,
        )
    }
    return files
}

private fun stripPatchPrefix(path: String): String {
    var p = path
    // Unquote first — a quoted path starts with `"` so a `b/` check on the
    // raw string never fires for `"b/x y"`. Git C-quotes odd filenames.
    if (p.startsWith("\"") && p.endsWith("\"") && p.length >= 2) {
        p = unquotePatchPath(p)
    }
    if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2)
    return p
}

/**
 * Git C-quoted path → raw string: `\\`/`\"`/`\\n`/`\\t` and `\\NNN` octal
 * bytes (the UTF-8 of non-ASCII names) decoded through a byte stream.
 */
private fun unquotePatchPath(p: String): String {
    val s = p.substring(1, p.length - 1)
    if ('\\' !in s) return s
    val out = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        var c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            c = s[++i]
            when (c) {
                'n' -> c = '\n'
                't' -> c = '\t'
                'r' -> c = '\r'
                'a' -> c = ''
                'b' -> c = ''
                'f' -> c = ''
                'v' -> c = ''
                in '0'..'7' -> {
                    var v = c - '0'
                    var n = 1
                    while (n < 3 && i + 1 < s.length && s[i + 1] in '0'..'7') {
                        v = v * 8 + (s[++i] - '0')
                        n++
                    }
                    out.write(v)
                    i++
                    continue
                }
                // '\\', '"' and anything unrecognized → the char itself.
            }
        }
        if (c.code < 0x80) {
            out.write(c.code)
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8))
        }
        i++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** Tool diffs merged by path → renderable files (order preserved). */
internal fun buildReviewFilesFromToolDiffs(diffs: List<ToolDiff>): List<ReviewDiffFile> {
    val merged = LinkedHashMap<String, Pair<String?, String>>()
    diffs.forEach { d ->
        val prev = merged[d.path]
        merged[d.path] = (prev?.first ?: d.oldText) to d.newText
    }
    return merged.entries.map { (path, texts) ->
        buildReviewFileFromTexts("f:$path", path, texts.first, texts.second)
    }
}

// ---- word diff (reviewWordDiffs.ts + jsdiff diffWordsWithSpace port) --------

private const val REVIEW_MAX_WORD_ALT_LINE_LENGTH = 1_000

private const val EXT_WORD =
    "a-zA-Z0-9_\\u00AD\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02C6" +
        "\\u02C8-\\u02D7\\u02DE-\\u02FF\\u1E00-\\u1EFF"

// jsdiff's diffWordsWithSpace tokenizer: newlines are their own token, word
// runs, non-newline whitespace runs, and single non-word chars.
private val WORD_TOKENIZER =
    Regex("(\\r?\\n)|[$EXT_WORD]+|[^\\S\\n\\r]+|[^$EXT_WORD]")

private fun tokenizeWords(value: String): List<String> =
    WORD_TOKENIZER.findAll(value).map { it.value }.toList()

/**
 * Token-level LCS over jsdiff's word tokens → (deletion, addition) highlight
 * ranges, with t3code's neutral/last-op span joining and nearby merging.
 */
internal fun computeWordAltDiffRanges(
    deletionLine: String,
    additionLine: String,
): Pair<List<DiffWordDiffRange>, List<DiffWordDiffRange>> {
    val del = deletionLine.removeSuffix("\n")
    val add = additionLine.removeSuffix("\n")
    if (del.isEmpty() && add.isEmpty()) return emptyList<DiffWordDiffRange>() to emptyList()
    if (del.length > REVIEW_MAX_WORD_ALT_LINE_LENGTH ||
        add.length > REVIEW_MAX_WORD_ALT_LINE_LENGTH
    ) {
        return emptyList<DiffWordDiffRange>() to emptyList()
    }
    val a = tokenizeWords(del)
    val b = tokenizeWords(add)
    val ops = lcsTokenOps(a, b) // per-token: true = changed

    // spans: (kind 0 neutral / 1 changed, text)
    val delSpans = ArrayList<Pair<Int, String>>()
    val addSpans = ArrayList<Pair<Int, String>>()
    val lastIndex = ops.lastIndex
    ops.forEachIndexed { index, op ->
        val isLast = index == lastIndex
        // op: kind ∈ {0 keep, 1 del, 2 add}
        when (op.second) {
            0 -> {
                pushOrJoinSpan(delSpans, op.first, isNeutral = true, isLast = isLast)
                pushOrJoinSpan(addSpans, op.first, isNeutral = true, isLast = isLast)
            }
            1 -> pushOrJoinSpan(delSpans, op.first, isNeutral = false, isLast = isLast)
            else -> pushOrJoinSpan(addSpans, op.first, isNeutral = false, isLast = isLast)
        }
    }
    return mergeNearbyRanges(spansToRanges(delSpans)) to
        mergeNearbyRanges(spansToRanges(addSpans))
}

private fun pushOrJoinSpan(
    spans: MutableList<Pair<Int, String>>,
    value: String,
    isNeutral: Boolean,
    isLast: Boolean,
) {
    val lastSpan = spans.lastOrNull()
    if (lastSpan == null || isLast) {
        spans += (if (isNeutral) 0 else 1) to value
        return
    }
    val lastIsNeutral = lastSpan.first == 0
    if (isNeutral == lastIsNeutral || (isNeutral && value.length == 1 && !lastIsNeutral)) {
        spans[spans.lastIndex] = lastSpan.first to (lastSpan.second + value)
        return
    }
    spans += (if (isNeutral) 0 else 1) to value
}

private fun spansToRanges(spans: List<Pair<Int, String>>): List<DiffWordDiffRange> {
    val ranges = ArrayList<DiffWordDiffRange>()
    var offset = 0
    spans.forEach { (kind, value) ->
        val next = offset + value.length
        if (kind == 1 && value.isNotEmpty()) ranges += DiffWordDiffRange(offset, next)
        offset = next
    }
    return ranges
}

private fun mergeNearbyRanges(ranges: List<DiffWordDiffRange>): List<DiffWordDiffRange> {
    if (ranges.size <= 1) return ranges
    val merged = ArrayList<DiffWordDiffRange>()
    ranges.forEach { range ->
        val prev = merged.lastOrNull()
        if (prev != null && range.start - prev.end <= 1) {
            merged[merged.lastIndex] = DiffWordDiffRange(prev.start, range.end)
        } else {
            merged += range
        }
    }
    return merged
}

/**
 * Per-token ops: (text, 0 keep | 1 removed | 2 added). Common prefix/suffix
 * trim + full DP table on the middle — word lines are ≤1k chars so the
 * table stays small.
 */
private fun lcsTokenOps(a: List<String>, b: List<String>): List<Pair<String, Int>> {
    var p = 0
    while (p < a.size && p < b.size && a[p] == b[p]) p++
    var s = 0
    while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
    val out = ArrayList<Pair<String, Int>>(a.size + b.size)
    for (i in 0 until p) out += a[i] to 0
    val am = a.subList(p, a.size - s)
    val bm = b.subList(p, b.size - s)
    if (am.isNotEmpty() && bm.isNotEmpty()) {
        val n = am.size
        val m = bm.size
        // Full DP table — tokens are short runs (words), bounded by line cap.
        val dp = IntArray((n + 1) * (m + 1))
        fun cell(i: Int, j: Int) = dp[i * (m + 1) + j]
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i * (m + 1) + j] = if (am[i] == bm[j]) {
                    cell(i + 1, j + 1) + 1
                } else {
                    maxOf(cell(i + 1, j), cell(i, j + 1))
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                am[i] == bm[j] -> {
                    out += am[i] to 0
                    i++
                    j++
                }
                cell(i + 1, j) >= cell(i, j + 1) -> {
                    out += am[i] to 1
                    i++
                }
                else -> {
                    out += bm[j] to 2
                    j++
                }
            }
        }
        while (i < n) {
            out += am[i] to 1
            i++
        }
        while (j < m) {
            out += bm[j] to 2
            j++
        }
    } else {
        am.forEach { out += it to 1 }
        bm.forEach { out += it to 2 }
    }
    for (i in 0 until s) out += a[a.size - s + i] to 0
    return out
}
