package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.acp.RevertStep
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnForward

/**
 * Revert/fork step picker opened by long-pressing a user message. Two-step
 * flow: tapping a row only selects it; "从此处分叉" forks via
 * `_cognition.ai/revert/forkFromStep` and the VM switches sessions.
 */
@Composable
fun RevertSheet(
    steps: List<RevertStep>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme
    DevinSheet(title = "从步骤分叉", onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "选择一个步骤，基于它创建新会话并切换过去；当前会话保留。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (steps.isEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    "没有可分叉的步骤",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
                Text(
                    "会话里还没有可回退的用户步骤",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { index, s ->
                        StepRow(
                            step = s,
                            index = index + 1,
                            selected = s.stepId == selectedId,
                            onClick = { selectedId = s.stepId },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = selectedId != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "将基于所选步骤创建一个新会话，当前会话不会被修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinePillButton(
                            text = "取消",
                            onClick = { selectedId = null },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                        )
                        BlackPillButton(
                            text = "从此处分叉",
                            leadingIcon = {
                                Icon(
                                    HugeIcons.ArrowTurnForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = { selectedId?.let(onPick) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 52.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One selectable step: ordinal dot + summary/kind on a rounded surface. */
@Composable
private fun StepRow(
    step: RevertStep,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) cs.secondaryContainer.copy(alpha = 0.4f)
        else cs.surfaceContainerLowest,
        border = BorderStroke(
            Hairline,
            if (selected) cs.primary else cs.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) cs.primary else cs.surfaceContainerHighest,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$index",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    step.summary?.takeIf { it.isNotBlank() } ?: step.stepId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                step.kind?.let {
                    Text(
                        it,
                        style = monoText(MaterialTheme.typography.labelSmall),
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
