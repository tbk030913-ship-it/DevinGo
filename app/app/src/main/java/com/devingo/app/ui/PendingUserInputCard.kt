package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.ElicitationReply
import com.devingo.app.PendingElicitation
import com.devingo.app.acp.ElicitationFieldType
import com.devingo.app.acp.ElicitationParser
import com.devingo.app.acp.QuestionDraft
import com.devingo.app.acp.QuestionOption
import com.devingo.app.acp.UserInputQuestion
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.Comment01
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/**
 * A7.2's structured elicitation card — t3code's PendingUserInputCard ported
 * to the permission card's slot above the composer. [pending] null hides it;
 * the last non-null value is kept so the exit animation still has content.
 * Submit answers `{action:"accept", content:{…}}`; 跳过/关闭 declines.
 */
@Composable
fun PendingUserInputCard(
    pending: PendingElicitation?,
    running: Boolean,
    onResolve: (PendingElicitation, ElicitationReply?) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastPending by remember { mutableStateOf<PendingElicitation?>(null) }
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
            PendingUserInputCardContent(p, running, { onResolve(p, it) }, onStop)
        }
    }
}

@Composable
private fun PendingUserInputCardContent(
    p: PendingElicitation,
    running: Boolean,
    onResolve: (ElicitationReply?) -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    // Card caps at 55% of the window (65% landscape); the body scrolls inside.
    val cardMax = (config.screenHeightDp * if (landscape) 0.65 else 0.55).dp
    // A fresh elicitation opens expanded; the header collapses to the pill.
    var expanded by remember(p.requestId) { mutableStateOf(true) }
    // Question id -> draft; resets when a new elicitation takes the slot.
    val drafts = remember(p.requestId) { mutableStateMapOf<String, QuestionDraft>() }

    val resolved = p.questions.associate { q ->
        q.id to ElicitationParser.resolveAnswer(q, drafts[q.id] ?: QuestionDraft())
    }
    val canSubmit = p.questions.all { q -> !q.required || resolved[q.id] != null }

    fun submit() {
        val content = buildJsonObject {
            resolved.values.filterNotNull().forEach {
                put(it.contentKey, it.contentValue)
            }
        }
        val lines = p.questions.mapNotNull { q ->
            resolved[q.id]?.let { "${displayQuestion(q)} → ${it.display}" }
        }
        onResolve(ElicitationReply(content, lines))
    }

    if (!expanded) {
        // Collapsed pill: what Devin asked + tap-to-expand + stop (while the
        // turn still runs — t3's onStopThread affordance).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = cs.surfaceContainerLowest,
            border = BorderStroke(1.dp, cs.outlineVariant),
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { expanded = true }
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    HugeIcons.Comment01, null,
                    modifier = Modifier.size(16.dp),
                    tint = cs.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    cardTitle(p),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                if (running) {
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onStop)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = cardMax),
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
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    cardTitle(p),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    Text(
                        "停止",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onStop)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                IconButton(
                    onClick = { expanded = false },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        HugeIcons.ChevronDown, "收起",
                        modifier = Modifier.size(16.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onResolve(null) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Close, "跳过并关闭",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                p.message.takeIf { it.isNotBlank() }?.let { msg ->
                    MarkdownBlock(
                        content = msg,
                        style = MaterialTheme.typography.bodyMedium
                            .copy(color = cs.onSurfaceVariant),
                    )
                }
                p.questions.forEachIndexed { i, q ->
                    QuestionBlock(
                        q = q,
                        showHeader = p.questions.size > 1 ||
                            q.header != "问题 ${i + 1}",
                        draft = drafts[q.id] ?: QuestionDraft(),
                        onDraftChange = { drafts[q.id] = it },
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "跳过",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onResolve(null) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                BlackPillButton(
                    text = "提交回答",
                    onClick = ::submit,
                    enabled = canSubmit,
                    modifier = Modifier.width(140.dp),
                )
            }
        }
    }
}

private fun cardTitle(p: PendingElicitation): String =
    if (p.questions.size > 1) {
        "Devin 提出了 ${p.questions.size} 个问题"
    } else {
        "Devin 提出了一个问题"
    }

/** Question text for the WorkLog line — collapsed, one line, ~60 chars. */
private fun displayQuestion(q: UserInputQuestion): String {
    val s = q.question.replace(Regex("\\s+"), " ").trim()
    return if (s.length > 60) s.take(60) + "…" else s
}

/** One question: header label + markdown + option rows + custom answer. */
@Composable
private fun QuestionBlock(
    q: UserInputQuestion,
    showHeader: Boolean,
    draft: QuestionDraft,
    onDraftChange: (QuestionDraft) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showHeader) {
            Text(
                q.header,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MarkdownBlock(
            content = q.question,
            style = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
        )
        if (q.isMultiSelect && (q.minItems > 0 || q.maxItems < Int.MAX_VALUE)) {
            Text(
                multiSelectHint(q),
                style = MaterialTheme.typography.labelSmall,
                color = cs.outline,
            )
        }
        if (q.options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                q.options.forEach { option ->
                    val selected = option.wireValue in draft.selected
                    OptionRow(
                        option = option,
                        multi = q.isMultiSelect,
                        selected = selected && draft.custom.isBlank(),
                        onClick = {
                            onDraftChange(toggleOption(q, draft, option.wireValue))
                        },
                    )
                }
            }
        }
        if (q.allowCustomText) {
            CustomAnswerField(
                q = q,
                value = draft.custom,
                placeholder = if (q.options.isEmpty()) "输入回答…" else "其他 / 补充说明",
                onChange = { onDraftChange(setCustom(draft, it)) },
            )
        }
    }
}

private fun multiSelectHint(q: UserInputQuestion): String = when {
    q.minItems > 0 && q.maxItems < Int.MAX_VALUE ->
        "选 ${q.minItems}–${q.maxItems} 项"
    q.minItems > 0 -> "至少选 ${q.minItems} 项"
    else -> "最多选 ${q.maxItems} 项"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionRow(
    option: QuestionOption,
    multi: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = cs.surfaceContainer, bounded = true),
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) {
            Checkbox(
                checked = selected,
                onCheckedChange = null, // the row handles taps
                modifier = Modifier.size(40.dp),
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.size(40.dp),
            )
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                option.label,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            option.description?.takeIf { it != option.label }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CustomAnswerField(
    q: UserInputQuestion,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.outline,
            )
        },
        singleLine = q.fieldType != ElicitationFieldType.Text,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (q.fieldType) {
                ElicitationFieldType.Number, ElicitationFieldType.Integer ->
                    KeyboardType.Number
                else -> KeyboardType.Text
            },
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.onSurface,
            unfocusedBorderColor = cs.outlineVariant,
            focusedContainerColor = cs.surfaceContainerLowest,
            unfocusedContainerColor = cs.surfaceContainerLowest,
        ),
    )
}

// ---- draft semantics (t3code pendingUserInput.ts) ------------------------------

/** Tapping an option picks it (or toggles it off in multi) and clears custom. */
private fun toggleOption(
    q: UserInputQuestion,
    draft: QuestionDraft,
    wireValue: String,
): QuestionDraft = if (q.isMultiSelect) {
    val sel = if (wireValue in draft.selected) {
        draft.selected - wireValue
    } else {
        draft.selected + wireValue
    }
    draft.copy(selected = sel, custom = "")
} else {
    // Single-select stays sticky on re-tap (t3 pendingUserInput parity).
    draft.copy(selected = listOf(wireValue), custom = "")
}

/** Typing a non-blank custom answer drops the option selection. */
private fun setCustom(draft: QuestionDraft, text: String): QuestionDraft =
    if (text.isBlank()) draft.copy(custom = text)
    else draft.copy(custom = text, selected = emptyList())
