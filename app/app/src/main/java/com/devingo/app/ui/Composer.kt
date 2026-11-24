package com.devingo.app.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import com.devingo.app.CitationAttachment
import com.devingo.app.ImageAttachment
import com.devingo.app.UiState
import com.devingo.app.acp.AcpSession
import com.devingo.app.acp.AgentCaps
import com.devingo.app.acp.FsSearchItem
import com.devingo.app.acp.MentionResource
import com.devingo.app.acp.SessionMode
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.AtSign
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Quotes
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Terminal

// ---- composer ------------------------------------------------------------------

/**
 * Devin mobile composer: one rounded #EDEDED pill. At rest it is the compact
 * layer — "+" | single-line text | send; once the field is focused, the text
 * overflows one line, or a citation/image is attached, the expanded layer
 * takes over: a multi-line editor on top of a scrollable 40dp toolbar row
 * (+, @, / triggers, model·effort and permission-mode pills, send at the
 * right end) — t3code's compact/expanded composer surfaces.
 *
 * N6.2: both layers share one native [ComposerEditText] — `@{kind}:{ref}`
 * inline chips, tap-on-chip sheet, atomic chip deletion, clipboard image
 * paste. The view never leaves the tree between layers, so focus/IME survive
 * the pill↔expanded swap.
 *
 * All v1 behaviors kept — image attachments, the unified `/`+`@` popover
 * (A7.3's ComposerCommandPopover), queue/steer menu (queue keeps images; steer is
 * text-only and disables when attachments exist). U5.4: while a queued
 * prompt is being edited, the top slot shows the 取消/保存 banner and the
 * send button becomes 保存 ([onSend] routes). U5.5: [citation] pins a quote
 * chip above the editor; the sheet opens on tap, the × removes it.
 */
@Composable
internal fun Composer(
    ui: UiState,
    input: String,
    attachments: List<ImageAttachment>,
    citation: CitationAttachment?,
    queueEditing: Boolean,
    permissionPending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onAttach: (ImageAttachment) -> Unit,
    onRemoveAttachment: (ImageAttachment) -> Unit,
    onCitationClick: () -> Unit,
    onCitationRemove: () -> Unit,
    onOpenModel: () -> Unit,
    onSelectMode: (String) -> Unit,
    onCancelQueuedEdit: () -> Unit,
    onStop: () -> Unit,
    /** Bridge /fs/list — the chip scanner's known-path bounds (N6.2). */
    onFetchFiles: suspend () -> List<String>,
    /** Negotiated caps — the @-picker's kind gating (A7.3). */
    agentCaps: AgentCaps,
    /** `_devingo/sessions` cache — the @-picker's 会话 category. */
    sessions: List<AcpSession>,
    /** Bridge /fs/search — the @-picker's file+dir feed (A7.3). */
    onSearchWorkspace: suspend (String) -> List<FsSearchItem>,
    /** `_cognition.ai/{rules,skills,plans}/list` by kind (A7.3). */
    onMentionResources: suspend (String) -> List<MentionResource>,
    /** A picked `@{kind}:{ref}` — registered verbatim for send-scan (A7.3). */
    onMentionInsert: (String) -> Unit,
    /** `cognition.ai/userShellCommand` negotiated — the `!` affordance (A7.6). */
    userShellCommands: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        // Sequential: keeps the strip in pick order and never holds more
        // than one decoded bitmap at a time.
        scope.launch {
            uris.forEach { uri ->
                val attachment = compressImage(context.contentResolver, uri)
                if (attachment != null) {
                    onAttach(attachment)
                } else {
                    Toast.makeText(
                        context, "无法读取该图片", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    var sendMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    // A blank-quote citation isn't sendable content, but a queue edit's
    // blank 保存 is — it's the delete-item affordance.
    val canSend = queueEditing || input.isNotBlank() || attachments.isNotEmpty() ||
        citation?.quotedText?.isNotBlank() == true
    // `!` at position 0 + the negotiated cap → a shell command, not a prompt
    // (A7.6): a terminal glyph marks the field, and send bypasses the
    // queue/steer menu — the shell runs it right away. Attachments can't
    // ride the shell path, so with images pinned `!` stays literal text.
    val shellCommand = userShellCommands && attachments.isEmpty() &&
        input.startsWith("!") && input.drop(1).isNotBlank()

    // The one native editor both layers share — text/selection/chips live on
    // the view; `input` and `selectionEnd` are its Compose mirrors.
    val editor = remember { ComposerEditText(context) }
    var selectionEnd by remember { mutableIntStateOf(0) }
    var chipSheet by remember { mutableStateOf<ChipToken?>(null) }

    // The `@`/`/` trigger under the caret — drives the unified popover (A7.3).
    val trigger = remember(input, selectionEnd) {
        detectComposerTrigger(input, selectionEnd)
    }
    val insertAtCaret: (String) -> Unit = { token ->
        val e = editor.text
        if (e != null) {
            val pos = editor.selectionEnd.coerceIn(0, e.length)
            e.insert(pos, token)
        }
    }
    var workspaceFiles by remember(ui.sessionId) {
        mutableStateOf<List<String>?>(null)
    }
    var filesFailed by remember(ui.sessionId) { mutableStateOf(false) }
    // knownFiles feeds the space-in-path match for file/dir chip tokens.
    val needFiles = input.contains("@file:") || input.contains("@folder:") ||
        input.contains("@directory:")
    LaunchedEffect(needFiles, ui.sessionId) {
        if (needFiles && workspaceFiles == null && !filesFailed) {
            try {
                workspaceFiles = onFetchFiles()
            } catch (_: Exception) {
                filesFailed = true
            }
        }
    }
    val chipTokens = remember(input, workspaceFiles) {
        scanContextTokens(input, workspaceFiles.orEmpty())
    }
    val pasteImages: (List<Uri>) -> Unit = { uris ->
        // Same compress→attach path as the "+" image picker.
        scope.launch {
            uris.forEach { uri ->
                val attachment = compressImage(context.contentResolver, uri)
                if (attachment != null) {
                    onAttach(attachment)
                } else {
                    Toast.makeText(
                        context, "无法读取该图片", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val cs = MaterialTheme.colorScheme
    val sendState = when {
        // Editing a queued prompt: the button is 保存, never the turn's
        // Stop or the queue/steer menu.
        queueEditing -> if (canSend) SendState.Send else SendState.Disabled
        ui.running && !canSend -> SendState.Stop
        canSend -> SendState.Send
        else -> SendState.Disabled
    }

    // Expanded layer: focused (the user's own expansion), multi-line input,
    // an over-long single line, or attachments/citation needing the strip.
    var focused by remember(ui.sessionId) { mutableStateOf(false) }
    var compactFieldWidth by remember { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()
    val fieldStyle = MaterialTheme.typography.bodyLarge
    val fieldPadPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    // ~1.9em per chip for its icon slot + padding — chips draw their label,
    // not the raw token, so measure the rendered approximation or a long
    // @file: path would over-expand the pill.
    val chipChromePx = with(LocalDensity.current) { fieldStyle.fontSize.toPx() * 1.9f }
    val singleLineOverflow = remember(input, chipTokens, compactFieldWidth, fieldStyle) {
        input.isNotEmpty() && compactFieldWidth > 0 && run {
            val approx = buildString {
                var cursor = 0
                for (t in chipTokens) {
                    append(input, cursor, t.start)
                    append(t.label)
                    cursor = t.end
                }
                append(input, cursor, input.length)
            }
            textMeasurer.measure(
                AnnotatedString(approx),
                style = fieldStyle,
                maxLines = 1,
                constraints = Constraints(maxWidth = Constraints.Infinity),
            ).size.width + chipTokens.size * chipChromePx > compactFieldWidth - fieldPadPx
        }
    }
    val expanded = focused || attachments.isNotEmpty() || citation != null ||
        input.contains('\n') || singleLineOverflow

    val onImeSend = {
        when {
            !canSend -> Unit
            queueEditing -> onSend()
            ui.running && !shellCommand -> sendMenu = true
            else -> onSend()
        }
    }

    // Toolbar pills (model·effort → ModelSheet; mode → its own menu).
    val effortName = effortChoiceName(ui.extraConfigOptions)
    val modelLabel = listOfNotNull(
        ui.models.firstOrNull { it.modelId == ui.currentModelId }?.name,
        effortName,
    ).joinToString(" · ").ifBlank { "模型" }
    val showModelPill = ui.models.isNotEmpty() || ui.modes.isNotEmpty() ||
        effortName != null
    val currentMode = ui.modes.firstOrNull { it.id == ui.currentModeId }

    Column(
        Modifier
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Popup slot above the pill: queue-edit banner, then the unified
        // `/`+`@` picker (A7.3 — one surface for both triggers; the queue
        // itself lives in U5.4's floating capsule + ThreadQueueSheet).
        AnimatedVisibility(
            visible = queueEditing && !permissionPending,
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            ComposerPopup(Modifier.padding(horizontal = 16.dp)) {
                QueueEditBanner(
                    canSave = canSend,
                    onSave = onSend,
                    onCancel = onCancelQueuedEdit,
                )
            }
        }
        // Keep the last trigger through the exit animation so the card
        // doesn't render empty for its final ~100ms — session-keyed so a
        // switch can't replay another session's trigger mid-exit.
        var lastTrigger by remember(ui.sessionId) {
            mutableStateOf<ComposerTrigger?>(null)
        }
        LaunchedEffect(trigger) { if (trigger != null) lastTrigger = trigger }
        AnimatedVisibility(
            visible = trigger != null && !permissionPending,
            enter = fadeIn(tween(150)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            ComposerPopup(Modifier.padding(horizontal = 16.dp)) {
                (trigger ?: lastTrigger)?.let { t ->
                    ComposerCommandPopover(
                        trigger = t,
                        sessionId = ui.sessionId,
                        commands = ui.commands,
                        caps = agentCaps,
                        sessions = sessions,
                        onSearch = onSearchWorkspace,
                        onMentionResources = onMentionResources,
                    ) { insert, rangeStart ->
                        val e = editor.text
                        if (e != null) {
                            val end = editor.selectionEnd.coerceIn(0, e.length)
                            e.replace(
                                rangeStart.coerceIn(0, end),
                                end,
                                insert,
                            )
                            // A picked ref can hold spaces — register it
                            // verbatim; the send scan can't re-derive it.
                            if (insert.startsWith("@")) {
                                insert.trimEnd().substringAfter(':', "")
                                    .takeIf { it.isNotEmpty() }
                                    ?.let(onMentionInsert)
                            }
                        }
                    }
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
                AnimatedVisibility(
                    visible = citation != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    citation?.let {
                        CitationChip(
                            citation = it,
                            onClick = onCitationClick,
                            onRemove = onCitationRemove,
                        )
                    }
                }
                val pickImages = {
                    picker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                }
                // One Row always hosts the field: compact adds "+" | send
                // around it, expanded lets it take the full width above the
                // toolbar. The AndroidView keeps its slot across the swap, so
                // the EditText — and its IME connection — never moves.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    if (!expanded) {
                        ComposerPlusButton(
                            onPickImages = pickImages,
                            onInsert = insertAtCaret,
                        )
                    }
                    if (shellCommand) {
                        // Top-anchored so the glyph sits on the first text
                        // line in both layers (the row is bottom-aligned).
                        Icon(
                            HugeIcons.Terminal, "终端命令",
                            modifier = Modifier
                                .align(Alignment.Top)
                                .padding(top = 18.dp, start = 10.dp)
                                .size(16.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                    ComposerField(
                        editor = editor,
                        input = input,
                        tokens = chipTokens,
                        placeholder = when {
                            permissionPending -> "等你授权…"
                            queueEditing -> "编辑排队消息…"
                            ui.running -> "补充指令…（排队或转向）"
                            else -> "给 Devin 发消息"
                        },
                        singleLine = !expanded,
                        onTextChanged = onInputChange,
                        onSelectionChanged = { _, end -> selectionEnd = end },
                        onFocusChanged = { focused = it },
                        onChipClick = { chipSheet = it },
                        onPasteImages = pasteImages,
                        onImeSend = onImeSend,
                        modifier = Modifier
                            .weight(1f)
                            // The pill's floor: singleLine's CENTER_VERTICAL
                            // gravity needs the view at 52dp, not wrap-height.
                            .heightIn(min = 52.dp)
                            // Only the compact width feeds singleLineOverflow —
                            // the shared field's expanded width would
                            // self-cancel the overflow flag and oscillate.
                            .onSizeChanged {
                                if (!expanded) compactFieldWidth = it.width
                            },
                    )
                    if (!expanded) {
                        ComposerSendArea(
                            sendState = sendState,
                            running = ui.running,
                            queueEditing = queueEditing,
                            shellCommand = shellCommand,
                            steerable = attachments.isEmpty(),
                            sendMenu = sendMenu,
                            onSendMenuChange = { sendMenu = it },
                            onSend = onSend,
                            onSteer = onSteer,
                            onStop = onStop,
                        )
                    }
                }
                if (expanded) {
                    // Spec: the toolbar row fades in over 160ms as the layer
                    // expands — the transition state starts hidden so the
                    // enter animation actually plays on layer entry.
                    val toolbarVisible = remember {
                        MutableTransitionState(false).apply { targetState = true }
                    }
                    AnimatedVisibility(
                        visibleState = toolbarVisible,
                        enter =
                            if (isReducedMotion()) EnterTransition.None
                            else fadeIn(tween(160)),
                        exit = fadeOut(tween(120)),
                    ) {
                    ComposerToolbarRow(
                        scrollContent = {
                            ComposerPlusButton(
                                onPickImages = pickImages,
                                onInsert = insertAtCaret,
                            )
                            ToolbarIconButton(
                                icon = HugeIcons.AtSign,
                                description = "@ 引用文件",
                                onClick = {
                                    val e = editor.text
                                    if (e != null) {
                                        val pos = editor.selectionEnd
                                            .coerceIn(0, e.length)
                                        // `@` only triggers at a word
                                        // boundary — pad mid-word carets.
                                        val pad = pos > 0 &&
                                            input.getOrNull(pos - 1)
                                                ?.isWhitespace() == false
                                        e.insert(pos, if (pad) " @" else "@")
                                    }
                                },
                            )
                            ToolbarIconButton(
                                icon = HugeIcons.CommandLine,
                                description = "/ 命令",
                                onClick = { insertAtCaret("/") },
                            )
                            if (showModelPill) {
                                ToolbarPill(
                                    label = modelLabel,
                                    onClick = onOpenModel,
                                )
                            }
                            if (currentMode != null) {
                                Box {
                                    ToolbarPill(
                                        label = currentMode.name,
                                        leadingIcon = HugeIcons.Shield01,
                                        accent = isRiskyMode(currentMode),
                                        onClick = { modeMenu = true },
                                    )
                                    DevinMenu(
                                        expanded = modeMenu,
                                        onDismissRequest = { modeMenu = false },
                                    ) {
                                        ui.modes.forEach { m ->
                                            DevinMenuCheckItem(
                                                m.name,
                                                checked = m.id == ui.currentModeId,
                                                onClick = {
                                                    modeMenu = false
                                                    onSelectMode(m.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        trailing = {
                            ComposerSendArea(
                                sendState = sendState,
                                running = ui.running,
                                queueEditing = queueEditing,
                                shellCommand = shellCommand,
                                steerable = attachments.isEmpty(),
                                sendMenu = sendMenu,
                                onSendMenuChange = { sendMenu = it },
                                onSend = onSend,
                                onSteer = onSteer,
                                onStop = onStop,
                            )
                        },
                    )
                    }
                }
            }
        }
        chipSheet?.let { token ->
            ComposerContextSheet(
                token = token,
                onRemove = {
                    val e = editor.text
                    if (e != null && token.end <= e.length &&
                        e.substring(token.start, token.end) == token.source
                    ) {
                        e.delete(token.start, token.end)
                    }
                    chipSheet = null
                },
                onDismiss = { chipSheet = null },
            )
        }
    }
}

private enum class SendState { Disabled, Send, Stop }

// ---- shared pieces (compact and expanded layers) --------------------------------

/**
 * The composer's text field — the one [ComposerEditText] (N6.2's t3code
 * port) reconfigured per layer: 52dp single-line pill or 3–6-line editor.
 * Text/selection/chips live on the view; Compose mirrors them through the
 * listeners so the draft store stays the source of truth.
 */
@Composable
private fun ComposerField(
    editor: ComposerEditText,
    input: String,
    tokens: List<ChipToken>,
    placeholder: String,
    singleLine: Boolean,
    onTextChanged: (String) -> Unit,
    onSelectionChanged: (Int, Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onChipClick: (ChipToken) -> Unit,
    onPasteImages: (List<Uri>) -> Unit,
    onImeSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    AndroidView(
        factory = { editor },
        modifier = modifier,
        update = { view ->
            view.singleLineMode = singleLine
            view.placeholderText = placeholder
            if (!textStyle.fontSize.isUnspecified) {
                view.setTextSizeSp(textStyle.fontSize.value)
            }
            view.applyThemeColors(
                textColor = cs.onSurface.toArgb(),
                hintColor = cs.outline.toArgb(),
                accent = cs.primary.toArgb(),
            )
            with(density) {
                view.applyContentPadding(
                    horizontalPx = (if (singleLine) 4.dp else 14.dp).roundToPx(),
                    verticalPx = (if (singleLine) 0.dp else 10.dp).roundToPx(),
                )
            }
            view.textListener = onTextChanged
            view.selectionListener = onSelectionChanged
            view.focusListener = onFocusChanged
            view.submitListener = onImeSend
            view.chipClickListener = onChipClick
            view.pasteImagesListener = onPasteImages
            view.setControlledText(input)
            view.setChipTokens(tokens)
        },
    )
}

/** "+" button + its 图片/引用文件 menu — used by both layers. */
@Composable
private fun ComposerPlusButton(
    onPickImages: () -> Unit,
    onInsert: (String) -> Unit,
) {
    var plusMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { plusMenu = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Filled.Add, "更多操作",
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DevinMenu(
            expanded = plusMenu,
            onDismissRequest = { plusMenu = false },
        ) {
            DevinMenuItem(
                "图片",
                onClick = { plusMenu = false; onPickImages() },
            )
            DevinMenuItem(
                "引用文件",
                onClick = { plusMenu = false; onInsert("@") },
            )
        }
    }
}

/**
 * The send/stop circle + queue/steer menu — identical in both layers.
 * [steerable] is false while images are attached (mid-turn steer is
 * text-only).
 */
@Composable
private fun ComposerSendArea(
    sendState: SendState,
    running: Boolean,
    queueEditing: Boolean,
    /** `!` draft — a shell command never opens the queue/steer menu (A7.6). */
    shellCommand: Boolean,
    steerable: Boolean,
    sendMenu: Boolean,
    onSendMenuChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
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
                    SendState.Send -> when {
                        queueEditing -> onSend()
                        running && !shellCommand -> onSendMenuChange(true)
                        else -> onSend()
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
            }
        }
        // Queue/steer menu anchored to the send circle.
        DevinMenu(
            expanded = sendMenu,
            onDismissRequest = { onSendMenuChange(false) },
        ) {
            DevinMenuItem(
                "排队",
                subtitle = "本轮结束后自动发送",
                onClick = { onSendMenuChange(false); onSend() },
            )
            DevinMenuItem(
                "立即转向",
                subtitle = if (steerable) {
                    "打断当前步骤，马上把这条指令交给 Devin"
                } else {
                    "转向不支持图片"
                },
                enabled = steerable,
                danger = true,
                onClick = { onSendMenuChange(false); onSteer() },
            )
        }
    }
}

/**
 * The expanded layer's toolbar: a horizontally scrolling control strip
 * (edge-faded 16dp once it overflows) with [trailing] — the send circle —
 * pinned at the right end so send never scrolls away.
 */
@Composable
private fun ComposerToolbarRow(
    scrollContent: @Composable RowScope.() -> Unit,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .horizontalScroll(scroll)
                    .padding(start = 2.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = scrollContent,
            )
            val canBack by remember { derivedStateOf { scroll.value > 0 } }
            val canForward by remember {
                derivedStateOf { scroll.value < scroll.maxValue }
            }
            // matchParentSize, not fillMaxHeight: a fill takes the full
            // offered maxHeight (the screen's leftover space, passed down
            // through the wrap-content rows) and balloons the whole
            // composer whenever the strip scrolls — matchParentSize only
            // copies the Box's settled height, never feeds back into it.
            if (canBack) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(16.dp)
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(cs.surfaceContainerHigh, Color.Transparent),
                            ),
                        ),
                )
            }
            if (canForward) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(16.dp)
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, cs.surfaceContainerHigh),
                            ),
                        ),
                )
            }
        }
        trailing()
    }
}

/** A 40dp icon touch target inside [ComposerToolbarRow]'s scroll strip. */
@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A labeled inline control in the toolbar strip (t3code's
 * ComposerInlineControl: label + trailing chevron). [accent] flags a
 * high-risk value — the shield/label go warning-amber.
 */
@Composable
private fun ToolbarPill(
    label: String,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accent: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val tint = if (accent) DevinGoColors.warning else cs.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            Icon(it, null, modifier = Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) DevinGoColors.warning else cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            HugeIcons.ChevronDown, null,
            modifier = Modifier
                .padding(start = 3.dp)
                .size(12.dp),
            tint = tint,
        )
    }
}

/** Mode ids/names that deserve the warning-amber treatment (high risk). */
private val riskyModeRegex =
    Regex("danger|yolo|bypass|unsafe|auto|自动|危险", RegexOption.IGNORE_CASE)

private fun isRiskyMode(mode: SessionMode?): Boolean =
    mode != null && (riskyModeRegex.containsMatchIn(mode.id) ||
        riskyModeRegex.containsMatchIn(mode.name))

/**
 * The quote pinned above the editor — accent-bar + one-line preview, tap
 * opens the citation sheet, × removes it from the draft.
 */
@Composable
private fun CitationChip(
    citation: CitationAttachment,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 10.dp, end = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                Hairline, cs.outlineVariant, RoundedCornerShape(10.dp),
            )
            .background(cs.surfaceContainerLowest)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = 10.dp)
                .size(width = 3.dp, height = 22.dp)
                .background(cs.primary, RoundedCornerShape(2.dp)),
        )
        Icon(
            HugeIcons.Quotes, "引用",
            modifier = Modifier
                .padding(start = 8.dp)
                .size(14.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            citation.quotedText.trim().ifBlank { "引用（待填写）" },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HugeIcons.Cancel01, "移除引用",
                modifier = Modifier.size(14.dp),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

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

/**
 * U5.4's composer-top banner while a queued prompt is being edited —
 * t3code's ComposerQueuedEditBanner plus the spec's 保存 action (saving
 * rewrites the queue item in place via [onSave] = the normal send path).
 */
@Composable
private fun QueueEditBanner(
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            HugeIcons.PencilEdit01, null,
            modifier = Modifier.size(14.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            "正在编辑排队消息",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "取消",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            "保存",
            style = MaterialTheme.typography.labelMedium,
            color = if (canSave) DevinGoColors.link else cs.outline,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = canSave, onClick = onSave)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Attachment thumbnails with a remove badge per image. */
@Composable
private fun AttachmentStrip(
    attachments: List<ImageAttachment>,
    onRemoveAttachment: (ImageAttachment) -> Unit,
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

// ---- image attachments ------------------------------------------------------

private const val MAX_IMAGE_EDGE = 2048
private const val IMAGE_QUALITY = 85

/**
 * Read [uri] into a wire-ready [ImageAttachment]: a bounds-only
 * pass picks `inSampleSize` so the long edge lands ≤ [MAX_IMAGE_EDGE], EXIF
 * orientation is applied, then the bitmap is encoded JPEG q85 (WebP when it
 * has alpha). Runs on [Dispatchers.IO]; null when the stream can't be read
 * or decoded.
 */
private suspend fun compressImage(
    resolver: ContentResolver,
    uri: Uri,
): ImageAttachment? = withContext(Dispatchers.IO) {
    val raw = runCatching {
        resolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
    if (longEdge <= 0) return@withContext null
    var sample = 1
    while (longEdge / sample > MAX_IMAGE_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        raw, 0, raw.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return@withContext null
    val bitmap = applyExifOrientation(decoded, exifOrientation(raw))
    if (bitmap !== decoded) decoded.recycle()
    val format = when {
        !bitmap.hasAlpha() -> Bitmap.CompressFormat.JPEG
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Bitmap.CompressFormat.WEBP_LOSSY
        else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }
    val out = ByteArrayOutputStream()
    val encoded = bitmap.compress(format, IMAGE_QUALITY, out)
    bitmap.recycle()
    if (!encoded) return@withContext null
    ImageAttachment(
        out.toByteArray(),
        if (format == Bitmap.CompressFormat.JPEG) "image/jpeg" else "image/webp",
    )
}

private fun exifOrientation(raw: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
    )
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

/** Re-orients [bitmap] per its EXIF tag; returns it untouched when normal. */
private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            m.setRotate(180f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.setRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.setRotate(270f)
            m.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
}


