package com.devingo.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devingo.app.Subagent
import com.devingo.app.ui.theme.DevinGoColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.UserGroup

/**
 * A7.4 sheets fed by `SessionStore.subagents`.
 *
 * [SubagentListSheet] — the floating capsule's "N 子代理" target: the same
 * [SubagentRow] list the work-log card expands into, here for every
 * subagent in the session (including orphans without a visible turn).
 *
 * [SubagentDetailSheet] — row tap: the agent's status + timer, its live
 * 切后台/切前台/停止/打开会话 actions, a pending-approval notice routed from
 * the child session (children surface as separate sessions on the hub),
 * then the full task prompt, live output and the frozen result.
 */

/** Capsule target — every subagent in the session, in spawn order. */
@Composable
internal fun SubagentListSheet(
    subagents: List<Subagent>,
    /** Negotiated `_cognition.ai/subagent/…` surface — hides action chips. */
    actionsEnabled: Boolean,
    onOpen: (Subagent) -> Unit,
    onBackground: (Subagent) -> Unit,
    onForeground: (Subagent) -> Unit,
    onCancel: (Subagent) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DevinSheet(title = "子代理", onDismiss = onDismiss, scrollContent = true) {
        if (subagents.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    HugeIcons.UserGroup, null,
                    modifier = Modifier.size(28.dp),
                    tint = cs.onSurfaceVariant,
                )
                Text(
                    "暂无子代理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                subagents.forEach { agent ->
                    SubagentRow(
                        agent = agent,
                        actionsEnabled = actionsEnabled,
                        onClick = { onOpen(agent) },
                        onBackground = { onBackground(agent) },
                        onForeground = { onForeground(agent) },
                    )
                }
            }
        }
    }
}

/** Row tap — one subagent's prompt, live output and result. */
@Composable
internal fun SubagentDetailSheet(
    agent: Subagent,
    /** The child session carries a pending permission (hub routes by id). */
    childPendingApproval: Boolean,
    /** Negotiated `_cognition.ai/subagent/…` surface — hides the actions. */
    actionsEnabled: Boolean,
    onBackground: () -> Unit,
    onForeground: () -> Unit,
    onCancel: () -> Unit,
    /** Null when the wire never named a child session. */
    onOpenChild: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tone = subagentTone(agent.status)
    DevinSheet(title = agent.title, onDismiss = onDismiss, scrollContent = true) {
        // Status line: dot + label + timer.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubagentStatusDot(tone = tone, breathing = agent.status == "working")
            Text(
                subagentStatusLabel(agent.status),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.W500,
                ),
                color = subagentToneColor(tone),
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            SubagentElapsed(listOf(agent))
        }

        // t3's ChildThreadApprovals — the child session surfaces on the hub
        // as its own session; its permission flag rides the session list.
        if (childPendingApproval && onOpenChild != null) {
            Surface(
                onClick = onOpenChild,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(10.dp),
                color = DevinGoColors.warningContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        HugeIcons.Alert02, null,
                        modifier = Modifier.size(16.dp),
                        tint = DevinGoColors.onWarningContainer,
                    )
                    Text(
                        "子代理正在等待你的授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevinGoColors.onWarningContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "打开处理",
                        style = MaterialTheme.typography.labelLarge,
                        color = DevinGoColors.onWarningContainer,
                        maxLines = 1,
                    )
                }
            }
        }

        // Live controls + the child-session deep link.
        if (agent.live || onOpenChild != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (agent.live && actionsEnabled) {
                    if (agent.status == "background") {
                        OutlinePillButton(
                            "切前台", onForeground,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        OutlinePillButton(
                            "切后台", onBackground,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    DangerPillButton(
                        "停止", onCancel,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onOpenChild != null) {
                    BlackPillButton(
                        "打开会话", onOpenChild,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        agent.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            SectionHeader("任务")
            SheetTextBlock(prompt)
        }
        agent.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            SectionHeader(if (agent.live) "最新输出" else "输出")
            SheetTextBlock(detail, mono = true)
        }
        agent.result?.takeIf { it.isNotBlank() && it != agent.detail }?.let { result ->
            SectionHeader("结果")
            SheetTextBlock(result, mono = true)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Bordered text block for the sheet's prompt/output/result sections. */
@Composable
private fun SheetTextBlock(text: String, mono: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text.takeLast(8000),
            style = if (mono) {
                monoText(MaterialTheme.typography.bodySmall)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = cs.onSurface,
        )
    }
}
