package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01

/**
 * N6.2: the chip-tap sheet — t3code's ComposerContextSheet reduced to what
 * DevinGo's tokens carry: the kind icon, the full ref/path and the 移除引用
 * action (deletes the token range from the draft).
 */
@Composable
internal fun ComposerContextSheet(
    token: ChipToken,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (accent, icon) = chipPresentation(token.kind)
    DevinSheet(title = token.label, onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(Hairline, cs.outlineVariant, RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerLowest),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(accent),
                )
                Spacer(Modifier.width(10.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        token.detail,
                        style = monoText(MaterialTheme.typography.bodySmall),
                        color = cs.onSurface,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Cancel01, null,
                    modifier = Modifier.size(15.dp),
                    tint = cs.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "移除引用",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.error,
                )
            }
        }
    }
}
