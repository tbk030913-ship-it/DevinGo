package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.CitationAttachment
import com.devingo.app.ui.theme.DevinGoColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Location01

/**
 * U5.5's citation sheet (t3code's CitationSheet): review the quote pinned
 * to the composer, trim its text, add a comment, then copy it, jump to the
 * source message, or drop it from the draft. Edits push straight into the
 * session store via [onChange], so the composer chip mirrors them live.
 *
 * The grey context rows show where [CitationAttachment.quotedText] sits in
 * the original message (80 chars either side) — with the whole message
 * quoted they're absent.
 */
@Composable
internal fun CitationSheet(
    citation: CitationAttachment,
    /** The source message's text — the context derivation + trim anchor. */
    sourceText: String?,
    /** False when the source message is gone from the transcript model. */
    locatable: Boolean,
    onChange: (CitationAttachment) -> Unit,
    onLocate: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var quote by remember(citation.messageId) { mutableStateOf(citation.quotedText) }
    var comment by remember(citation.messageId) { mutableStateOf(citation.comment) }
    // Sync every edit into the store — the chip and the merged prompt read
    // the same attachment.
    val (prefix, suffix) = citationContexts(sourceText, quote)
    LaunchedEffect(quote, comment) {
        onChange(
            CitationAttachment(
                messageId = citation.messageId,
                quotedText = quote,
                comment = comment,
            ),
        )
    }
    DevinSheet(title = "引用", onDismiss = onDismiss, scrollContent = true) {
        // Quote card: accent bar + context/quote/context, quote editable.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(Hairline, cs.outlineVariant, RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerLowest),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 14.dp, bottom = 14.dp, start = 12.dp)
                    .width(3.dp)
                    .heightIn(min = 32.dp)
                    .background(cs.primary, RoundedCornerShape(2.dp)),
            )
            androidx.compose.foundation.layout.Column(
                Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                if (prefix.isNotEmpty()) {
                    Text(
                        prefix,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextField(
                    value = quote,
                    onValueChange = { quote = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 8,
                )
                if (suffix.isNotEmpty()) {
                    Text(
                        suffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextField(
            value = comment,
            onValueChange = { comment = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            placeholder = {
                Text(
                    "给这条引用加个备注（可选）",
                    color = cs.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceContainerLowest,
                unfocusedContainerColor = cs.surfaceContainerLowest,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(10.dp),
            singleLine = true,
        )
        // Actions: locate → copy → remove (destructive on the right).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locatable) {
                SheetAction(
                    icon = HugeIcons.Location01,
                    label = "定位到原消息",
                    onClick = onLocate,
                )
            }
            Spacer(Modifier.weight(1f))
            SheetAction(
                icon = HugeIcons.Copy01,
                label = "复制引用",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onCopy()
                },
            )
            Spacer(Modifier.width(18.dp))
            SheetAction(
                icon = HugeIcons.Cancel01,
                label = "从草稿移除",
                tint = cs.error,
                onClick = onRemove,
            )
        }
    }
}

/** A label+icon action button in the sheet's bottom row. */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = DevinGoColors.link,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * The 80-char grey context around [quote] inside [source] — both sides
 * empty when the whole message is quoted or the quote no longer matches
 * (free edits survive; context just disappears).
 */
private fun citationContexts(source: String?, quote: String): Pair<String, String> {
    if (source.isNullOrEmpty() || quote.isEmpty()) return "" to ""
    val idx = source.indexOf(quote)
    if (idx < 0) return "" to ""
    return source.substring(0, idx).trimEnd().takeLast(80) to
        source.substring(idx + quote.length).trimStart().take(80)
}
