package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatViewModel
import com.devingo.app.ChatViewModel.UiState
import com.devingo.app.acp.ConfigOption
import com.devingo.app.acp.SessionMode
import com.devingo.app.acp.SessionModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

/**
 * The reasoning-effort config option, per spec §C priority:
 * thought_level → thinking(select) → model_config with ≥2 choices.
 */
internal fun effortConfigOption(options: List<ConfigOption>): ConfigOption? =
    options.firstOrNull { it.category == "thought_level" }
        ?: options.firstOrNull { it.category == "thinking" && it.type == "select" }
        ?: options.firstOrNull {
            it.category == "model_config" && it.options.size >= 2
        }

/** Display name of the effort option's current choice, for the top bar. */
internal fun effortChoiceName(options: List<ConfigOption>): String? {
    val opt = effortConfigOption(options) ?: return null
    return opt.options.firstOrNull { it.value == opt.currentValue }
        ?.let { effortLabel(it.value, it.name) }
}

private val EFFORT_VALUE_LABELS = mapOf(
    "minimal" to "最低",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "extra_high" to "超高",
    "extra-high" to "超高",
    "max" to "最高",
)

/** 推理强度选项的中文显示名：先按 value 匹配，匹配不到再按 name，兜底原样显示 name。 */
internal fun effortLabel(value: String, name: String): String =
    EFFORT_VALUE_LABELS[value.lowercase()]
        ?: EFFORT_VALUE_LABELS[name.lowercase()]
        ?: name

/**
 * Model + mode picker opened from the chat top bar. Selecting a mode/model
 * closes the sheet after 150ms; the effort section stays open on select.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    ui: UiState,
    onSelectMode: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectConfig: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val close = {
        if (!closing) {
            closing = true
            scope.launch {
                delay(150)
                onDismiss()
            }
        }
    }
    DevinSheet(
        title = "模型",
        onDismiss = onDismiss,
        // Opens at ~half height in portrait; landscape has no half state.
        halfExpanded = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE,
        scrollContent = true,
    ) {
        if (ui.modes.isNotEmpty()) {
            SectionHeader("模式")
            ui.modes.forEach { m ->
                SheetChoiceRow(
                    title = m.name,
                    subtitle = m.description,
                    checked = m.id == ui.currentModeId,
                    onClick = { onSelectMode(m.id); close() },
                )
            }
        }
        val effort = effortConfigOption(ui.extraConfigOptions)
        if (effort != null) {
            SectionHeader(
                effort.name?.takeIf { it.isNotBlank() && !it.equals("thinking", true) }
                    ?: "推理强度",
            )
            if (effort.options.size <= 5) {
                val cs = MaterialTheme.colorScheme
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    effort.options.forEachIndexed { index, choice ->
                        val selected = choice.value == effort.currentValue
                        SegmentedButton(
                            selected = selected,
                            onClick = {
                                onSelectConfig(effort.id, choice.value)
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = effort.options.size,
                            ),
                            label = {
                                Text(
                                    effortLabel(choice.value, choice.name),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = cs.primary,
                                activeContentColor = cs.onPrimary,
                                inactiveContainerColor = cs.surfaceContainerLowest,
                                inactiveContentColor = cs.onSurface,
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) cs.primary else cs.outlineVariant,
                            ),
                        )
                    }
                }
            } else {
                effort.options.forEach { choice ->
                    SheetChoiceRow(
                        title = effortLabel(choice.value, choice.name),
                        subtitle = choice.description,
                        checked = choice.value == effort.currentValue,
                        onClick = {
                            // Effort selection keeps the sheet open (§C).
                            onSelectConfig(effort.id, choice.value)
                        },
                    )
                }
            }
        }
        if (ui.models.isNotEmpty()) {
            SectionHeader("模型")
            ui.models.forEach { m ->
                SheetChoiceRow(
                    title = m.name,
                    subtitle = m.description,
                    checked = m.modelId == ui.currentModelId,
                    onClick = { onSelectModel(m.modelId); close() },
                )
            }
        }
        if (ui.modes.isEmpty() && ui.models.isEmpty() && effort == null) {
            Text(
                "当前会话没有可选的模式或模型",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

/** One selectable row: title + optional subtitle + animated trailing check. */
@Composable
private fun SheetChoiceRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(
            visible = checked,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Icon(
                Icons.Filled.Check, "已选择",
                modifier = Modifier.size(18.dp),
                tint = cs.onSurface,
            )
        }
    }
}

/** Session metadata sheet: id/copy, cwd, context meter, token counts. */
@Composable
fun SessionInfoSheet(ui: UiState, sessionCwd: String, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    DevinSheet(title = "会话信息", onDismiss = onDismiss) {
        InfoRow("会话 ID", ui.sessionId ?: "—") {
            ui.sessionId?.let { id ->
                IconButton(onClick = {
                    val cm = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE,
                    ) as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("sessionId", id),
                    )
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm,
                    )
                }) {
                    Icon(
                        HugeIcons.Copy01, "复制会话 ID",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        }
        InfoRow("工作目录", sessionCwd.ifBlank { "—" })
        val frac = if (ui.contextSize > 0) {
            (ui.contextUsed.toFloat() / ui.contextSize).coerceIn(0f, 1f)
        } else 0f
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "上下文",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (ui.contextSize > 0) {
                        "${formatTokens(ui.contextUsed)} / ${formatTokens(ui.contextSize)}"
                    } else "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            if (ui.contextSize > 0) {
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (frac >= 0.9f) cs.error else cs.primary,
                    trackColor = cs.surfaceContainerHighest,
                )
            }
        }
        InfoRow(
            "Token 用量",
            "输入 ${formatTokens(ui.usageInput)} · 输出 ${formatTokens(ui.usageOutput)} · 缓存 ${formatTokens(ui.usageCacheRead)}",
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = monoText(MaterialTheme.typography.bodyMedium),
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
