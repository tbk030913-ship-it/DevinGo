package com.devingo.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.Subagent
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronRight

/**
 * A7.4 — the foldable subagent group card inside a turn's work-log column
 * (port of t3code's `thread-subagent-group.tsx`):
 *
 * - ≥2 agents: a collapsed-by-default header — ≤3 stacked status circles
 *   (-7dp overlap) + "+N" overflow chip, "N 个子代理", a live status summary
 *   (sky while any run, rose once any failed), the group's live elapsed and
 *   an expand chevron.
 * - Expanded (always for a single agent): a bordered container listing one
 *   [SubagentRow] per agent — breathing status dot, title + status label,
 *   ≤3-line progress, the agent's own timer, and live 切后台/切前台 + 停止
 *   actions. A row tap opens [SubagentDetailSheet].
 */
@Composable
internal fun ThreadSubagentGroupCard(
    subagents: List<Subagent>,
    /** Negotiated `_cognition.ai/subagent/…` surface — hides action chips. */
    actionsEnabled: Boolean,
    onOpen: (Subagent) -> Unit,
    onBackground: (Subagent) -> Unit,
    onForeground: (Subagent) -> Unit,
    onCancel: (Subagent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (subagents.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val grouped = subagents.size > 1
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The header summary runs sky while anything is live, rose once a member
    // failed, else the muted default (t3's adaptive-sky/rose swap).
    val anyLive = subagents.any { it.live }
    val anyFailed = subagents.any { it.status == "failed" }
    val summaryColor by animateColorAsState(
        targetValue = when {
            anyLive -> DevinGoColors.statusSky
            anyFailed -> DevinGoColors.statusRose
            else -> cs.onSurfaceVariant
        },
        label = "subagentSummaryColor",
    )
    Column(modifier.fillMaxWidth()) {
        if (grouped) {
            val chevronRot by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(200),
                label = "subagentChevron",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stacked status circles: -7dp overlap (negative spacing
                // shrinks the row too), +N chip last — the circles double
                // as the group's at-a-glance status mix.
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-7).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    subagents.take(3).forEach { agent ->
                        SubagentStatusCircle(agent = agent)
                    }
                    if (subagents.size > 3) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .border(1.dp, cs.outlineVariant, CircleShape)
                                .background(cs.surfaceContainerLowest, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "+${subagents.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${subagents.size} 个子代理",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.W500,
                        ),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subagentSummary(subagents),
                        style = MaterialTheme.typography.labelSmall,
                        color = summaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SubagentElapsed(subagents)
                Icon(
                    HugeIcons.ChevronRight, null,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(chevronRot),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
        if (!grouped || expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                    .padding(4.dp),
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

/** One agent row: status dot, title + status, ≤3-line detail, timer, actions. */
@Composable
internal fun SubagentRow(
    agent: Subagent,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onBackground: () -> Unit,
    onForeground: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val tone = subagentTone(agent.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .height(20.dp)
                .width(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            SubagentStatusDot(tone = tone, breathing = agent.status == "working")
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    agent.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.W500,
                    ),
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    subagentStatusLabel(agent.status),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.W500,
                    ),
                    color = subagentToneColor(tone),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                SubagentElapsed(listOf(agent))
                if (agent.childSessionId != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        HugeIcons.ChevronRight, "打开子会话",
                        modifier = Modifier.size(12.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            subagentCardDetail(agent)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tone == "failed") {
                        DevinGoColors.statusRose
                    } else {
                        cs.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (agent.live && actionsEnabled) {
                // Only reversible actions ride the row — a stray scroll tap
                // on a one-tap 停止 would kill in-flight work; that lives on
                // the detail sheet behind a deliberate open.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (agent.status == "background") {
                        SubagentActionChip("切前台", onClick = onForeground)
                    } else {
                        SubagentActionChip("切后台", onClick = onBackground)
                    }
                }
            }
        }
    }
}

/** Small bordered action chip — the row's 切后台/切前台/停止 controls. */
@Composable
private fun SubagentActionChip(
    text: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .height(26.dp)
            .pressScale(interaction),
        shape = CircleShape,
        color = cs.surfaceContainerLowest,
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Box(
            Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** The 28dp bordered circle hosting a status dot — the stack's member. */
@Composable
private fun SubagentStatusCircle(agent: Subagent, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(28.dp)
            .border(1.dp, cs.outlineVariant, CircleShape)
            .background(cs.surfaceContainerLowest, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        SubagentStatusDot(
            tone = subagentTone(agent.status),
            breathing = agent.status == "working",
        )
    }
}

/** The breathing 8dp status dot — sky working, emerald done, rose failed. */
@Composable
internal fun SubagentStatusDot(tone: String, breathing: Boolean) {
    val alpha = if (breathing && !isReducedMotion()) {
        val t = rememberInfiniteTransition(label = "subagentDot")
        val a by t.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "subagentDotAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .background(
                subagentToneColor(tone).copy(alpha = alpha),
                CircleShape,
            ),
    )
}

/** Display tone for a stored status (pending + background read as working). */
internal fun subagentTone(status: String): String = when (status) {
    "completed" -> "completed"
    "failed" -> "failed"
    "stopped" -> "stopped"
    "background" -> "background"
    else -> "working" // pending + working
}

@Composable
internal fun subagentToneColor(tone: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        "working" -> DevinGoColors.statusSky
        "background" -> DevinGoColors.statusSky.copy(alpha = 0.55f)
        "completed" -> DevinGoColors.statusEmerald
        "failed" -> DevinGoColors.statusRose
        else -> cs.onSurfaceVariant
    }
}

internal fun subagentStatusLabel(status: String): String = when (status) {
    "pending" -> "等待中"
    "working" -> "运行中"
    "background" -> "后台运行"
    "completed" -> "已完成"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status
}

/** "2 个运行中 · 1 个已完成" — t3's summarizeSubagentStatuses, Chinese. */
internal fun subagentSummary(subagents: List<Subagent>): String {
    var working = 0
    var done = 0
    var failed = 0
    var stopped = 0
    subagents.forEach {
        when (it.status) {
            "completed" -> done++
            "failed" -> failed++
            "stopped" -> stopped++
            else -> working++ // pending/working/background
        }
    }
    return buildList {
        if (working > 0) add("$working 个运行中")
        if (done > 0) add("$done 个已完成")
        if (failed > 0) add("$failed 个失败")
        if (stopped > 0) add("$stopped 个已停止")
    }.joinToString(" · ")
}

/**
 * The group's live elapsed — min start → now while anything runs, else →
 * max end (t3's `subagentCardElapsed`). Ticks once a second while live.
 */
@Composable
internal fun SubagentElapsed(subagents: List<Subagent>) {
    val live = subagents.any { it.live }
    var nowMs by remember(live) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val starts = subagents.map { it.startedAt }.filter { it > 0 }
    if (starts.isEmpty()) return
    val ends = subagents.map { it.endedAt }.filter { it > 0 }
    if (!live && ends.isEmpty()) return
    val durationMs = (if (live) nowMs else ends.max()) - starts.min()
    if (durationMs <= 0) return
    Text(
        formatElapsed(durationMs),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** "0:45" / "12:03" / "1:02:03" — matches the work row's timer format. */
internal fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, sec)
    } else {
        "%d:%02d".format(m, sec)
    }
}

/**
 * ≤3-line row detail — the live progress text while running, the result
 * once terminal (t3's `subagentDetailPreview` + `subagentCardDetail`:
 * markdown links flattened to text, whitespace collapsed, 280-char cap).
 */
internal fun subagentCardDetail(agent: Subagent): String? {
    val raw = if (agent.live) agent.detail ?: agent.result
    else agent.result ?: agent.detail
    var detail = raw?.trimStart()
        ?.replace(Regex("!?\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        ?.replace("`", "")
        ?.replace(Regex("^[ \\t]*[-*][ \\t]+", RegexOption.MULTILINE), "")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?: return null
    if (detail.startsWith("Child task ended with status", ignoreCase = true)) {
        return null
    }
    if (detail.length > 280) {
        // Back off one char rather than split a surrogate pair mid-glyph.
        val cut = if (detail[279].isHighSurrogate()) 279 else 280
        detail = detail.substring(0, cut).trimEnd() + "…"
    }
    return detail.ifBlank { null }
}
