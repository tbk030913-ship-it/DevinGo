package com.devingo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.PendingPermission
import com.devingo.app.acp.PermissionOption
import com.devingo.app.permissionOptionLabel
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.File01

private val prettyJson = Json { prettyPrint = true }

/**
 * Inline permission card pinned above the composer (replaces the v1 bottom
 * sheet). [pending] null hides it; the last non-null value is kept so the
 * exit animation still has content to animate out.
 */
@Composable
fun PermissionCard(
    pending: PendingPermission?,
    onResolve: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastPending by remember {
        mutableStateOf<PendingPermission?>(null)
    }
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
        lastPending?.let { PermissionCardContent(it, onResolve) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionCardContent(
    p: PendingPermission,
    onResolve: (String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val kind = p.toolCall.kind.orEmpty()
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    // Card caps at half the window, 65% in landscape; body scrolls inside.
    val cardMax = (config.screenHeightDp * if (landscape) 0.65 else 0.5).dp
    var showDetails by remember { mutableStateOf(false) }
    val detailsRotation by animateFloatAsState(
        targetValue = if (showDetails) 0f else -90f,
        label = "permissionDetails",
    )

    val commandText = commandPreview(p.toolCall.rawInput)
    val paths = remember(p.toolCall.locations) {
        (p.toolCall.locations as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("path").strOrNull() }
            .orEmpty()
    }
    val options = remember(p.options) { orderedOptions(p) }

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
                    permissionTitle(kind),
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onResolve(null) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Close, "拒绝并关闭",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (cardMax - 76.dp).coerceAtLeast(48.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                p.toolCall.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                commandText?.let { CommandBox(it) }
                if (kind in DANGER_KINDS) DangerLine(kind)
                if (paths.isNotEmpty() || p.toolCall.rawInput != null) {
                    DetailsToggle(
                        expanded = showDetails,
                        rotation = detailsRotation,
                        onToggle = { showDetails = !showDetails },
                    )
                    Column(Modifier.animateContentSize()) {
                        if (showDetails) {
                            paths.forEach { PathRow(it) }
                            p.toolCall.rawInput?.let { RawJsonBlock(it) }
                        }
                    }
                }
                options.forEachIndexed { i, opt ->
                    PermissionOptionRow(
                        letter = ('A' + i).toString(),
                        label = permissionOptionLabel(opt),
                        dangerLabel = kind == "delete" && !isReject(opt),
                        onClick = { onResolve(opt.optionId) },
                    )
                }
                // 取消 only appears when the agent offers no explicit reject.
                if (options.none { isReject(it) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onResolve(null) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "取消",
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                if (shownOptions(p).size != p.options.size) {
                    Text(
                        "“始终允许”选项仅桌面端可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.outline,
                    )
                }
            }
        }
    }
}

private val DANGER_KINDS = setOf("execute", "delete", "move")

private fun permissionTitle(kind: String): String = when (kind) {
    "execute" -> "Devin 想运行命令"
    "edit" -> "Devin 想修改文件"
    "delete" -> "Devin 想删除文件"
    "move" -> "Devin 想移动文件"
    "read" -> "Devin 想读取文件"
    "fetch" -> "Devin 想访问网络"
    else -> "Devin 请求权限"
}

private fun shownOptions(p: PendingPermission): List<PermissionOption> {
    val oneShot = p.options.filterNot { "always" in it.kind.orEmpty() }
    return if (oneShot.isEmpty()) p.options else oneShot
}

/** allow-once first, then other non-always options, then reject last. */
private fun orderedOptions(p: PendingPermission): List<PermissionOption> =
    shownOptions(p).sortedBy {
        val k = it.kind.orEmpty()
        when {
            "reject" in k -> 2
            "allow" in k && "always" !in k -> 0
            else -> 1
        }
    }

private fun isReject(option: PermissionOption) = "reject" in option.kind.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionOptionRow(
    letter: String,
    label: String,
    dangerLabel: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = cs.surfaceContainer, bounded = true),
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            letter,
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dangerLabel) cs.error else cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailsToggle(
    expanded: Boolean,
    rotation: Float,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "详情",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Icon(
            HugeIcons.ChevronDown, if (expanded) "收起详情" else "展开详情",
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun DangerLine(kind: String) {
    val cs = MaterialTheme.colorScheme
    val isDelete = kind == "delete"
    val tint = if (isDelete) cs.error else DevinGoColors.warning
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Warning, null,
            modifier = Modifier.size(14.dp),
            tint = tint,
        )
        Text(
            when (kind) {
                "delete" -> "敏感操作：将删除内容"
                "move" -> "敏感操作：将移动/重命名内容"
                else -> "敏感操作：将执行命令"
            },
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `command`/`cmd` string out of the tool's rawInput JSON, if present. */
private fun commandPreview(raw: JsonElement?): String? {
    val obj = raw as? JsonObject ?: return null
    return (obj["command"] ?: obj["cmd"])?.strOrNull()
}

@Composable
private fun CommandBox(command: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainer,
    ) {
        SelectionContainer {
            Text(
                "$ $command",
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = monoText().copy(fontSize = 13.sp),
                color = cs.onSurface,
                maxLines = 6,
            )
        }
    }
}

@Composable
private fun PathRow(path: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            HugeIcons.File01,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = cs.onSurfaceVariant,
        )
        Text(
            path,
            style = monoText().copy(fontSize = 11.5.sp),
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RawJsonBlock(raw: JsonElement?) {
    val cs = MaterialTheme.colorScheme
    val text = remember(raw) {
        raw?.let {
            runCatching {
                prettyJson.encodeToString(JsonElement.serializer(), it)
            }.getOrDefault(it.toString())
        }?.take(1600)
    }
    if (text == null) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = BorderStroke(Hairline, cs.outlineVariant),
    ) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = monoText().copy(fontSize = 11.5.sp),
                color = cs.onSurfaceVariant,
            )
        }
    }
}

private fun JsonElement?.strOrNull(): String? =
    (this as? JsonPrimitive)?.let { if (it.isString) it.content else null }
