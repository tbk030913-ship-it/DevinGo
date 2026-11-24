package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.BackgroundShell
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Terminal

/**
 * A7.6's 后台命令 sheet — every shell-manager terminal the session tracks
 * (user `!` commands + agent background shells), live off
 * `SessionStore.backgroundShells`. Rows borrow the subagent sheet's idiom:
 * status dot + command + status label + elapsed; a running row carries its
 * 终止 chip (`_cognition.ai/terminal/killBackgroundShell`).
 */
@Composable
internal fun BackgroundWorkSheet(
    shells: List<BackgroundShell>,
    onKill: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DevinSheet(title = "后台命令", onDismiss = onDismiss, scrollContent = true) {
        if (shells.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    HugeIcons.Terminal, null,
                    modifier = Modifier.size(28.dp),
                    tint = cs.onSurfaceVariant,
                )
                Text(
                    "暂无后台命令",
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
                shells.forEach { shell ->
                    BackgroundShellRow(
                        shell = shell,
                        onKill = { onKill(shell.terminalId) },
                    )
                }
            }
        }
    }
}

/** running | completed | failed | killed → the subagent tones. */
private fun shellTone(status: String): String = when (status) {
    "completed" -> "completed"
    "failed" -> "failed"
    "killed" -> "stopped"
    else -> "working"
}

private fun shellStatusLabel(status: String, exitCode: Int?): String = when (status) {
    "completed" -> "已完成"
    "failed" -> if (exitCode != null) "失败 · exit $exitCode" else "失败"
    "killed" -> "已终止"
    else -> "运行中"
}

/** One shell row: dot, `$ command`, status · elapsed, 终止 chip while live. */
@Composable
private fun BackgroundShellRow(
    shell: BackgroundShell,
    onKill: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tone = shellTone(shell.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .height(20.dp)
                .width(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            SubagentStatusDot(tone = tone, breathing = shell.running)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$ ${shell.command}",
                    style = monoText(MaterialTheme.typography.bodySmall),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (shell.running) {
                    ShellElapsed(shell.startedAt)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shellStatusLabel(shell.status, shell.exitCode),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.W500,
                    ),
                    color = subagentToneColor(tone),
                    maxLines = 1,
                )
                if (shell.userShell) {
                    Text(
                        " · 手动",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (shell.running) {
                    Text(
                        "终止",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.error,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onKill)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            shell.output?.takeIf { it.isNotBlank() }?.let { output ->
                Text(
                    output.lineSequence().take(3).joinToString("\n"),
                    style = monoText(MaterialTheme.typography.labelSmall),
                    color = if (shell.status == "failed") {
                        DevinGoColors.statusRose
                    } else {
                        cs.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Live "0:12" ticker for a running shell — the work row's timer format. */
@Composable
private fun ShellElapsed(startedAt: Long) {
    if (startedAt <= 0) return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    Text(
        formatElapsed(nowMs - startedAt),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}
