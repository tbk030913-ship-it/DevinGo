// Line-comment composer for the native review sheet — Kotlin port of
// t3code's ReviewCommentComposerSheet.tsx + the selection formatting of
// reviewCommentSelection.ts (MIT): ≤5 selected-line preview, file/range
// label, comment input, "添加到对话草稿" submit.

package com.devingo.app.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.ui.BlackPillButton
import com.devingo.app.ui.DevinSheet
import com.devingo.app.ui.monoText
import com.devingo.app.ui.theme.DevinGoColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Comment01
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon

/** t3code's composer caps the selected-lines preview at five rows. */
private const val REVIEW_COMMENT_PREVIEW_MAX = 5

/**
 * One review comment target: the selected line range of [lines] plus the
 * file it belongs to. Built by the review sheet's tap/long-press flow.
 */
data class ReviewSelection(
    val filePath: String,
    val lines: List<ReviewLineInfo>,
    val startIndex: Int,
    val endIndex: Int,
) {
    val selected: List<ReviewLineInfo> get() = lines.subList(startIndex, endIndex + 1)
}

private fun unifiedNumber(line: ReviewLineInfo): Int? =
    line.newLineNumber ?: line.oldLineNumber

private fun changeMarker(change: String): String = when (change) {
    "add" -> "+"
    "delete" -> "-"
    else -> " "
}

/** Port of t3code's formatReviewSelectedRangeLabel ("+12", "3 to 9", …). */
fun formatSelectedRangeLabel(sel: ReviewSelection): String {
    val lines = sel.selected
    val first = lines.firstOrNull() ?: return "line"
    val last = lines.last()
    val firstNumber = unifiedNumber(first)
    val lastNumber = unifiedNumber(last)
    if (firstNumber == null || lastNumber == null) {
        return if (lines.size == 1) "line" else "${lines.size} lines"
    }
    val firstMarker = changeMarker(first.change).trim()
    val marker =
        if (lines.all { it.change == first.change } && firstMarker.isNotEmpty()) {
            changeMarker(first.change)
        } else {
            ""
        }
    return if (firstNumber == lastNumber) {
        "$marker$firstNumber"
    } else {
        "$marker$firstNumber to $marker$lastNumber"
    }
}

/** `file:L1-L3` attribution used inside the draft quote block. */
fun formatSelectedAttribution(sel: ReviewSelection): String {
    val lines = sel.selected
    val first = lines.firstOrNull()?.let(::unifiedNumber)
    val last = lines.lastOrNull()?.let(::unifiedNumber)
    return when {
        first == null || last == null -> sel.filePath
        first == last -> "${sel.filePath}:L$first"
        else -> "${sel.filePath}:L$first-L$last"
    }
}

/** Port of t3code's formatReviewSelectedDiff — a minimal fenced hunk. */
fun formatSelectedDiff(sel: ReviewSelection): String {
    val lines = sel.selected
    val oldStart = lines.firstOrNull { it.oldLineNumber != null }?.oldLineNumber ?: 0
    val oldCount = lines.count { it.oldLineNumber != null }
    val newStart = lines.firstOrNull { it.newLineNumber != null }?.newLineNumber ?: 0
    val newCount = lines.count { it.newLineNumber != null }
    val body = lines.joinToString("\n") { changeMarker(it.change) + it.content }
    return "@@ -$oldStart,$oldCount +$newStart,$newCount @@\n" +
        body.ifEmpty { " " }
}

/** The citation's quotedText: attribution + fenced diff, markdown-quoted at send. */
fun formatReviewCitation(sel: ReviewSelection): String =
    "${formatSelectedAttribution(sel)}\n```diff\n${formatSelectedDiff(sel)}\n```"

/**
 * The comment sheet opened by tapping a diff line: header (file + range),
 * ≤5-line preview, comment field and the 添加到对话草稿 submit.
 */
@Composable
fun ReviewCommentSheet(
    selection: ReviewSelection,
    onSubmit: (comment: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var comment by remember { mutableStateOf("") }
    val preview = selection.selected.take(REVIEW_COMMENT_PREVIEW_MAX)
    val hidden = selection.selected.size - preview.size

    DevinSheet(
        title = "评论 ${formatSelectedAttribution(selection)}",
        onDismiss = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.Comment01, null,
                modifier = Modifier.size(15.dp),
                tint = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                selection.filePath,
                style = monoText(MaterialTheme.typography.labelMedium),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cs.surfaceContainerLowest,
            border = BorderStroke(0.5.dp, cs.outlineVariant),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                preview.forEach { line ->
                    val (bg, markerColor) = when (line.change) {
                        "add" ->
                            DevinGoColors.diffAdd.copy(alpha = 0.12f) to DevinGoColors.diffAdd
                        "delete" ->
                            DevinGoColors.diffRemove.copy(alpha = 0.10f) to DevinGoColors.diffRemove
                        else ->
                            androidx.compose.ui.graphics.Color.Transparent to cs.onSurfaceVariant
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            unifiedNumber(line)?.toString() ?: "",
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.width(34.dp),
                            maxLines = 1,
                        )
                        Text(
                            changeMarker(line.change),
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = markerColor,
                            modifier = Modifier.width(12.dp),
                        )
                        Text(
                            line.content,
                            style = monoText(MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)),
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
                if (hidden > 0) {
                    Text(
                        "…另有 $hidden 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cs.surfaceContainerLowest,
            border = BorderStroke(0.5.dp, cs.outlineVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (comment.isEmpty()) {
                    Text(
                        "写下你的评论…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text("取消", color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            BlackPillButton(
                text = "添加到对话草稿",
                onClick = { onSubmit(comment.trim()) },
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
