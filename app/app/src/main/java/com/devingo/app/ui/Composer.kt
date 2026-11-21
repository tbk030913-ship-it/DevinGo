package com.devingo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.devingo.app.ChatViewModel
import com.devingo.app.ChatViewModel.UiState
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnForward
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.AtSign
import me.rerere.hugeicons.stroke.Queue01

// ---- composer ------------------------------------------------------------------

/**
 * Devin mobile composer: one rounded #EDEDED pill holding "+" | text | the
 * black round send/stop button. All v1 behaviors kept — image attachments,
 * @mention styling + hint, slash-command strip, queue/steer menu with the
 * attachment-loss guard.
 */
@Composable
internal fun Composer(
    ui: UiState,
    input: String,
    attachments: List<ChatViewModel.ImageAttachment>,
    queued: List<ChatViewModel.QueuedPrompt>,
    permissionPending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onAttach: (ChatViewModel.ImageAttachment) -> Unit,
    onRemoveAttachment: (ChatViewModel.ImageAttachment) -> Unit,
    onCancelQueued: (Int) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/*"
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: return@forEach
                onAttach(ChatViewModel.ImageAttachment(bytes, mime))
            }
        }
    }
    var plusMenu by remember { mutableStateOf(false) }
    var sendMenu by remember { mutableStateOf(false) }
    val canSend = input.isNotBlank() || attachments.isNotEmpty()

    var fieldValue by remember { mutableStateOf(TextFieldValue(input)) }
    LaunchedEffect(input) {
        if (fieldValue.text != input) {
            fieldValue = TextFieldValue(input, selection = TextRange(input.length))
        }
    }
    val caretToken = remember(fieldValue.text, fieldValue.selection) {
        val before = fieldValue.text.substring(
            0,
            fieldValue.selection.end.coerceIn(0, fieldValue.text.length),
        )
        caretMentionRegex.find(before)?.groups?.get(2)?.value
    }

    val cs = MaterialTheme.colorScheme
    val sendState = when {
        ui.running && !canSend -> SendState.Stop
        canSend -> SendState.Send
        else -> SendState.Disabled
    }

    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Popup slot above the pill: queue chip, then the mention hint.
        // (Slash-command suggestions render above the whole composer —
        // ChatScreen owns that slot.)
        AnimatedVisibility(
            visible = queued.isNotEmpty() && !permissionPending,
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            ComposerPopup(Modifier.padding(horizontal = 16.dp)) {
                QueueChip(queued, onCancelQueued)
            }
        }
        AnimatedVisibility(
            visible = caretToken != null && !permissionPending,
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            caretToken?.let { token ->
                ComposerPopup(Modifier.padding(horizontal = 16.dp)) {
                    MentionHint(token)
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .animateContentSize(),
            shape = RoundedCornerShape(26.dp),
            color = cs.surfaceContainerHigh,
        ) {
            Column {
                AnimatedVisibility(
                    visible = attachments.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    AttachmentStrip(attachments, onRemoveAttachment)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box {
                        IconButton(
                            onClick = { plusMenu = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add, "更多操作",
                                modifier = Modifier.size(22.dp),
                                tint = cs.onSurface,
                            )
                        }
                        DevinMenu(
                            expanded = plusMenu,
                            onDismissRequest = { plusMenu = false },
                        ) {
                            DevinMenuItem(
                                "图片",
                                onClick = {
                                    plusMenu = false
                                    picker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                            )
                            DevinMenuItem(
                                "引用文件",
                                onClick = {
                                    plusMenu = false
                                    val tv = fieldValue
                                    val pos = tv.selection.end.coerceIn(
                                        0, tv.text.length,
                                    )
                                    val newText = tv.text.substring(0, pos) + "@" +
                                        tv.text.substring(pos)
                                    fieldValue = TextFieldValue(
                                        newText,
                                        selection = TextRange(pos + 1),
                                    )
                                    onInputChange(newText)
                                },
                            )
                        }
                    }
                    TextField(
                        value = fieldValue,
                        onValueChange = {
                            fieldValue = it
                            onInputChange(it.text)
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    permissionPending -> "等你授权…"
                                    ui.running -> "补充指令…（排队或转向）"
                                    else -> "给 Devin 发消息"
                                },
                                color = cs.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        minLines = 1,
                        maxLines = 6,
                        visualTransformation = mentionVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                when {
                                    !canSend -> Unit
                                    ui.running -> sendMenu = true
                                    else -> {
                                        haptic.performHapticFeedback(
                                            HapticFeedbackType.Confirm,
                                        )
                                        onSend()
                                    }
                                }
                            },
                        ),
                    )
                    // Trailing black circle: 48dp touch box around a 36dp visual.
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            onClick = {
                                when (sendState) {
                                    SendState.Disabled -> Unit
                                    SendState.Stop -> onStop()
                                    SendState.Send -> if (ui.running) {
                                        sendMenu = true
                                    } else {
                                        haptic.performHapticFeedback(
                                            HapticFeedbackType.Confirm,
                                        )
                                        onSend()
                                    }
                                }
                            },
                            enabled = sendState != SendState.Disabled,
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (sendState == SendState.Disabled) {
                                        DevinGoColors.disabledButton
                                    } else {
                                        cs.primary
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = sendState,
                                    transitionSpec = {
                                        (fadeIn(tween(150)) +
                                            androidx.compose.animation.scaleIn(
                                                initialScale = 0.8f,
                                                animationSpec = tween(150),
                                            )) togetherWith
                                            (fadeOut(tween(100)) +
                                                androidx.compose.animation.scaleOut(
                                                    targetScale = 0.8f,
                                                    animationSpec = tween(100),
                                                ))
                                    },
                                    label = "sendState",
                                ) { state ->
                                    when (state) {
                                        SendState.Stop -> Box(
                                            Modifier
                                                .size(11.dp)
                                                .background(
                                                    Color.White,
                                                    RoundedCornerShape(3.dp),
                                                ),
                                        )
                                        else -> Icon(
                                            HugeIcons.ArrowUp01, null,
                                            modifier = Modifier.size(18.dp),
                                            tint = Color.White,
                                        )
                                    }
                                }
                            }
                        }
                        // Queue/steer menu anchored to the send circle.
                        DevinMenu(
                            expanded = sendMenu,
                            onDismissRequest = { sendMenu = false },
                        ) {
                            if (attachments.isNotEmpty()) {
                                Row(
                                    Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 8.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Warning, null,
                                        modifier = Modifier.size(14.dp),
                                        tint = DevinGoColors.warning,
                                    )
                                    Text(
                                        "运行中发送会丢失图片，请等本轮结束或移除图片",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DevinGoColors.warning,
                                    )
                                }
                            }
                            DevinMenuItem(
                                "排队",
                                subtitle = "本轮结束后自动发送",
                                enabled = attachments.isEmpty(),
                                onClick = { sendMenu = false; onSend() },
                            )
                            DevinMenuItem(
                                "立即转向",
                                subtitle = "打断当前步骤，马上把这条指令交给 Devin",
                                enabled = attachments.isEmpty(),
                                danger = true,
                                onClick = { sendMenu = false; onSteer() },
                            )
                        }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SendState { Disabled, Send, Stop }

/** White popup card floating 6dp above the composer pill. */
@Composable
internal fun ComposerPopup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = BorderStroke(Hairline, cs.outlineVariant),
    ) {
        content()
    }
}

/** Slash-command matches for [input]; shared by the strip and its visibility. */
internal fun commandMatches(
    ui: UiState,
    input: String,
): List<com.devingo.app.acp.AvailableCommand> {
    if (ui.commands.isEmpty() || !input.startsWith("/")) return emptyList()
    val query = input.substring(1).takeWhile { it != ' ' }
    return ui.commands.filter { cmd ->
        cmd.name.removePrefix("/").startsWith(query, ignoreCase = true)
    }.take(6)
}

/** "排队中 N 条 ›" chip → menu listing queued prompts with remove buttons. */
@Composable
private fun QueueChip(
    queued: List<ChatViewModel.QueuedPrompt>,
    onCancelQueued: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = cs.surfaceContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    HugeIcons.Queue01, null,
                    modifier = Modifier.size(14.dp),
                    tint = cs.onSurfaceVariant,
                )
                Text(
                    "排队中 ${queued.size} 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        DevinMenu(expanded = open, onDismissRequest = { open = false }) {
            queued.forEachIndexed { i, q ->
                DevinMenuItem(
                    text = q.text.take(40) +
                        if (q.attachmentCount > 0) " [图×${q.attachmentCount}]" else "",
                    subtitle = "点击移除",
                    onClick = { open = false; onCancelQueued(i) },
                    trailing = {
                        Icon(
                            Icons.Filled.Close, "取消排队",
                            modifier = Modifier.size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

/** Attachment thumbnails with a remove badge per image. */
@Composable
private fun AttachmentStrip(
    attachments: List<ChatViewModel.ImageAttachment>,
    onRemoveAttachment: (ChatViewModel.ImageAttachment) -> Unit,
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(attachments) { index, a ->
            Box(Modifier.size(56.dp)) {
                AsyncImage(
                    model = a.bytes,
                    contentDescription = "已选图片 ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            Hairline,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp),
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .minimumInteractiveComponentSize()
                        .clickable { onRemoveAttachment(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        Modifier.size(20.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Close,
                                "移除图片 ${index + 1}",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- @mention ------------------------------------------------------------------

/** @token candidates: start-of-text or post-whitespace, non-space tail. */
private val mentionTokenRegex = Regex("(^|\\s)(@\\S+)")
private val caretMentionRegex = Regex("(^|\\s)(@\\S*)$")

/** Styles qualifying @path tokens (path-ish tail) link-blue + mono. */
@Composable
private fun mentionVisualTransformation(): VisualTransformation {
    val link = DevinGoColors.link
    return remember(link) {
        VisualTransformation { text ->
            val builder = AnnotatedString.Builder(text.text)
            mentionTokenRegex.findAll(text.text).forEach { m ->
                val token = m.groups[2] ?: return@forEach
                val rest = token.value.removePrefix("@")
                if (rest.any { it == '/' || it == '\\' || it == '.' }) {
                    builder.addStyle(
                        SpanStyle(color = link, fontFamily = MonoFamily),
                        token.range.first,
                        token.range.last + 1,
                    )
                }
            }
            TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }
    }
}

/** One-line hint describing the @token at the caret. */
@Composable
private fun MentionHint(token: String) {
    val cs = MaterialTheme.colorScheme
    val rest = token.removePrefix("@")
    val qualifies = rest.any { it == '/' || it == '\\' || it == '.' }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.AtSign, null,
            modifier = Modifier.size(14.dp),
            tint = cs.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        if (qualifies) {
            Text(
                "作为文件引用发送：",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rest,
                style = monoText(MaterialTheme.typography.labelSmall),
                color = DevinGoColors.link,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                "路径需包含 / 或扩展名，如 @src/main.kt",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Slash-command strip inside the popup card while input starts with "/". */
@Composable
internal fun CommandSuggestions(ui: UiState, input: String, onPick: (String) -> Unit) {
    val matches = commandMatches(ui, input)
    if (matches.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column {
        matches.forEach { cmd ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable {
                        val name = cmd.name.removePrefix("/")
                        onPick("/$name ")
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "/${cmd.name.removePrefix("/")}",
                    style = monoText(MaterialTheme.typography.bodySmall),
                    color = DevinGoColors.link,
                )
                cmd.description?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                cmd.inputHint?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        it,
                        style = monoText(MaterialTheme.typography.labelSmall),
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
