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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.acp.RevertPreview
import com.devingo.app.acp.RevertStep
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.ArrowTurnForward
import me.rerere.hugeicons.stroke.File01

/**
 * Revert/fork step picker opened by long-pressing a user message (A7.5).
 * Selecting a row fetches `_cognition.ai/revert/preview` (cached per step
 * while the sheet is open), then offers two modes: 「分叉出新会话」 forks via
 * `revert/forkFromStep`; 「原地回退」 rewinds history + workspace files via
 * `revert/execute` behind a second in-sheet confirm. Both leave the agent
 * to null the step list — success or failure unmounts this sheet.
 */
@Composable
fun RevertSheet(
    steps: List<RevertStep>,
    onDismiss: () -> Unit,
    onFork: (RevertStep) -> Unit,
    onExecute: (RevertStep) -> Unit,
    fetchPreview: suspend (RevertStep) -> RevertPreview?,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val previews = remember { mutableStateMapOf<String, PreviewState>() }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    val selected = steps.firstOrNull { it.stepId == selectedId }

    fun loadPreview(step: RevertStep) {
        previews[step.stepId] = PreviewState.Loading
        scope.launch {
            val p = fetchPreview(step)
            previews[step.stepId] =
                if (p == null) PreviewState.Failed else PreviewState.Ready(p)
        }
    }

    // Fetch once per selection; cached entries survive stepsUpdated refreshes.
    LaunchedEffect(selected?.stepId) {
        val step = selected ?: return@LaunchedEffect
        if (step.stepId !in previews) loadPreview(step)
    }

    DevinSheet(title = "回退 / 分叉", onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "原地回退会还原会话与工作区文件；分叉从新会话继续，当前会话保留。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (steps.isEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    "没有可回退的步骤",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEachIndexed { index, s ->
                        StepRow(
                            step = s,
                            index = s.stepNumber ?: (index + 1),
                            selected = s.stepId == selectedId,
                            onClick = {
                                if (s.stepId == selectedId) {
                                    selectedId = null
                                    confirming = false
                                } else {
                                    selectedId = s.stepId
                                    confirming = false
                                }
                            },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = selected != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                selected?.let { step ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PreviewBlock(
                            state = previews[step.stepId] ?: PreviewState.Loading,
                            onRetry = { loadPreview(step) },
                        )
                        if (confirming) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    HugeIcons.AlertCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = cs.error,
                                )
                                Text(
                                    "会话历史与工作区文件将回退到此步，不可撤销。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.error,
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinePillButton(
                                    text = "取消",
                                    onClick = { confirming = false },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                )
                                DangerPillButton(
                                    text = "确认回退",
                                    loading = busy == "execute",
                                    enabled = busy == null,
                                    onClick = {
                                        busy = "execute"
                                        onExecute(step)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                )
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinePillButton(
                                    text = "分叉出新会话",
                                    leadingIcon = {
                                        Icon(
                                            HugeIcons.ArrowTurnForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    enabled = busy == null,
                                    onClick = {
                                        busy = "fork"
                                        onFork(step)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                )
                                DangerPillButton(
                                    text = "原地回退到此步",
                                    leadingIcon = {
                                        Icon(
                                            HugeIcons.ArrowTurnBackward,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    enabled = busy == null,
                                    onClick = { confirming = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 52.dp),
                                )
                            }
                        }
                    }
                } ?: Spacer(Modifier)
            }
        }
    }
}

/** Preview fetch result for one step — cached in the sheet while it's open. */
private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Failed : PreviewState
    data class Ready(val preview: RevertPreview) : PreviewState
}

/** The file-impact list `revert/preview` returned for the selected step. */
@Composable
private fun PreviewBlock(state: PreviewState, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        PreviewState.Loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                "正在分析回退影响…",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        PreviewState.Failed -> Surface(
            onClick = onRetry,
            shape = RoundedCornerShape(8.dp),
            color = cs.surfaceContainerLowest,
        ) {
            Text(
                "无法获取预览，点按重试",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        is PreviewState.Ready -> {
            val p = state.preview
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (p.files.isEmpty() && p.warnings.isEmpty() && p.conflicts.isEmpty()) {
                    Text(
                        "此步骤之后没有文件改动",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                p.files.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            HugeIcons.File01,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.onSurfaceVariant,
                        )
                        f.action?.let { kind ->
                            val (label, color) = when (kind) {
                                "restore" -> "还原" to cs.onSurfaceVariant
                                "delete" -> "删除" to DevinGoColors.diffRemove
                                "recreate" -> "重建" to DevinGoColors.diffAdd
                                else -> kind to cs.onSurfaceVariant
                            }
                            Text(
                                label,
                                style = monoText(MaterialTheme.typography.labelSmall),
                                color = color,
                                maxLines = 1,
                            )
                        }
                        Text(
                            f.path,
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (f.additions > 0) {
                            Text(
                                "+${f.additions}",
                                style = monoText(MaterialTheme.typography.labelSmall),
                                color = DevinGoColors.diffAdd,
                                maxLines = 1,
                            )
                        }
                        if (f.deletions > 0) {
                            Text(
                                "−${f.deletions}",
                                style = monoText(MaterialTheme.typography.labelSmall),
                                color = DevinGoColors.diffRemove,
                                maxLines = 1,
                            )
                        }
                    }
                }
                p.warnings.forEach { w ->
                    Text(
                        w,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                p.conflicts.forEach { path ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            HugeIcons.AlertCircle,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = cs.error,
                        )
                        Text(
                            "冲突 $path",
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = cs.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
