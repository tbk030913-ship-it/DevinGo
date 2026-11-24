package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.PendingSecret
import com.devingo.app.SecretReply
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.EyeOff
import me.rerere.hugeicons.stroke.LockKey

/**
 * A7.6's secret card — `_cognition.ai/secret/create` asks for a value in the
 * permission card's slot above the composer (t3code SecretRequestCard ported
 * to the PendingUserInputCard idiom). [pending] null hides it; the last
 * non-null value is kept so the exit animation still has content. Save
 * resolves with [SecretReply]; 拒绝/close declines (null).
 */
@Composable
fun SecretRequestCard(
    pending: PendingSecret?,
    onResolve: (PendingSecret, SecretReply?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastPending by remember { mutableStateOf<PendingSecret?>(null) }
    if (pending != null) lastPending = pending
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pending != null) {
        if (pending != null) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    AnimatedVisibility(
        visible = pending != null,
        modifier = modifier.fillMaxWidth(),
        enter = slideInVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        ) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        // Resolve with the rendered card's identity — the exit-animation
        // ghost must not answer a newly pushed head.
        lastPending?.let { p ->
            SecretRequestCardContent(p) { onResolve(p, it) }
        }
    }
}

@Composable
private fun SecretRequestCardContent(
    p: PendingSecret,
    onResolve: (SecretReply?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var value by remember(p.requestId) { mutableStateOf("") }
    var revealed by remember(p.requestId) { mutableStateOf(false) }
    val save = {
        if (value.isNotBlank()) onResolve(SecretReply(value))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerLowest,
        border = BorderStroke(1.dp, cs.outlineVariant),
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    HugeIcons.LockKey, null,
                    modifier = Modifier.size(16.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    p.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            // The wire's env-var name + scope tell the user exactly what
            // Devin stores — shown mono like every other identifier.
            p.envVarName?.takeIf { it.isNotBlank() && it != p.label }?.let { env ->
                Text(
                    listOfNotNull(env, p.saveScope).joinToString(" · "),
                    style = monoText(MaterialTheme.typography.labelMedium),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            p.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "输入密钥…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.outline,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                visualTransformation = if (p.sensitive && !revealed) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = if (p.sensitive) {
                    {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) HugeIcons.EyeOff else HugeIcons.Eye,
                                if (revealed) "隐藏" else "显示",
                                modifier = Modifier.size(18.dp),
                                tint = cs.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    null
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.onSurface,
                    unfocusedBorderColor = cs.outlineVariant,
                    focusedContainerColor = cs.surfaceContainerLowest,
                    unfocusedContainerColor = cs.surfaceContainerLowest,
                ),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "拒绝",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onResolve(null) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                BlackPillButton(
                    text = "保存密钥",
                    onClick = save,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}
