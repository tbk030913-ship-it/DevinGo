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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import com.devingo.app.ImageAttachment
import com.devingo.app.UiState
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.AtSign
import me.rerere.hugeicons.stroke.PencilEdit01

// ---- composer ------------------------------------------------------------------

/**
 * Devin mobile composer: one rounded #EDEDED pill holding "+" | text | the
 * black round send/stop button. All v1 behaviors kept — image attachments,
 * @mention styling + hint, slash-command strip, queue/steer menu (queue
 * keeps images; steer is text-only and disables when attachments exist).
 * U5.4: while a queued prompt is being edited, the top slot shows the
 * 取消/保存 banner and the send button becomes 保存 ([onSend] routes).
 */
@Composable
internal fun Composer(
    ui: UiState,
    input: String,
    attachments: List<ImageAttachment>,
    queueEditing: Boolean,
    permissionPending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSteer: () -> Unit,
    onAttach: (ImageAttachment) -> Unit,
    onRemoveAttachment: (ImageAttachment) -> Unit,
    onCancelQueuedEdit: () -> Unit,
    onStop: () -> Unit,
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
        // Editing a queued prompt: the button is 保存, never the turn's
        // Stop or the queue/steer menu.
        queueEditing -> if (canSend) SendState.Send else SendState.Disabled
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
        // Popup slot above the pill: queue-edit banner, then the mention
        // hint. (Slash-command suggestions render above the whole composer —
        // ChatScreen owns that slot; the queue itself moved to U5.4's
        // floating capsule + ThreadQueueSheet.)
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
                                    queueEditing -> "编辑排队消息…"
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
                                    queueEditing -> onSend()
                                    ui.running -> sendMenu = true
                                    else -> onSend()
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
                                    SendState.Send -> when {
                                        queueEditing -> onSend()
                                        ui.running -> sendMenu = true
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
                        // Queue/steer menu anchored to the send circle.
                        DevinMenu(
                            expanded = sendMenu,
                            onDismissRequest = { sendMenu = false },
                        ) {
                            DevinMenuItem(
                                "排队",
                                subtitle = "本轮结束后自动发送",
                                onClick = { sendMenu = false; onSend() },
                            )
                            DevinMenuItem(
                                "立即转向",
                                subtitle = if (attachments.isEmpty()) {
                                    "打断当前步骤，马上把这条指令交给 Devin"
                                } else {
                                    "转向不支持图片"
                                },
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
