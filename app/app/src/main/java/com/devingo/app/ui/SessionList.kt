package com.devingo.app.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.SessionBadge
import com.devingo.app.acp.AcpSession
import com.devingo.app.ui.theme.DevinGoColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.SlidersHorizontal

/** Sort/filter state owned by HomeScreen, rendered by the bottom-bar menu. */
internal enum class HomeSort { Recent, Title }
internal enum class HomeFilterPage { Root, Sort, Filter }

/**
 * Project group header for the session list (Devin Desktop sidebar style):
 * folder icon + label + animated chevron + a "+" that starts a session in
 * the group's cwd.
 */
@Composable
internal fun ProjectGroupHeader(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label = "groupChevron",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .semantics {
                heading()
                stateDescription = if (expanded) "已展开" else "已收起"
            }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                HugeIcons.Folder01, null,
                modifier = Modifier.size(18.dp),
                tint = cs.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                group.label,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                HugeIcons.ChevronDown, null,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = rotation },
                tint = cs.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNewSession, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Add, "在 ${group.label} 中新建会话",
                modifier = Modifier.size(18.dp),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

/** "显示更多（N）" affordance under a capped project group. */
@Composable
internal fun MoreSessionsRow(
    remaining: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable(onClick = onClick)
            .padding(start = 44.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "显示更多（$remaining）",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * One session row (Devin Desktop sidebar style): content aligns with the
 * group-label text at 44dp, a 4dp status dot sits in the folder-icon column,
 * title + relative-time/badge meta line, and a trailing "…" menu with copy
 * ID / delete. Swipe EndToStart queues the same deferred delete behind the
 * undo snackbar; the open session can't be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRow(
    s: AcpSession,
    compact: Boolean,
    isCurrent: Boolean,
    switching: Boolean,
    lastSession: Boolean,
    badge: SessionBadge?,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canDelete) {
                onDelete()
            }
            false
        },
    )
    val swipingLeft =
        dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart ||
            dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    val progress = if (swipingLeft) dismissState.progress.coerceIn(0f, 1f) else 0f
    val iconScale = 0.85f + 0.15f * progress
    val overThreshold = progress > 0.6f
    LaunchedEffect(overThreshold) {
        if (overThreshold) {
            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier,
        backgroundContent = {
            if (progress > 0f) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .alpha(progress)
                        .background(lerp(cs.surface, cs.error, progress))
                        .padding(end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text("删除", style = MaterialTheme.typography.labelLarge, color = cs.onPrimary)
                    Icon(
                        HugeIcons.Delete02, "删除",
                        modifier = Modifier.size(20.dp).scale(iconScale),
                        tint = cs.onPrimary,
                    )
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (isCurrent) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(cs.surfaceContainer),
                )
            }
            // 4dp dot in the folder-icon column (header: 16+9 = 25dp center).
            Box(
                Modifier
                    .padding(start = 23.dp)
                    .align(Alignment.CenterStart)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(cs.outline),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    // end pad clears the 48dp "…" button overlaid at CenterEnd.
                    .padding(start = 44.dp, end = 52.dp)
                    .heightIn(min = if (compact) 44.dp else 56.dp)
                    .clickable(onClick = onClick)
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("删除会话") {
                                if (canDelete) onDelete()
                                true
                            },
                        )
                    }
                    .padding(vertical = if (compact) 0.dp else 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.title?.takeIf { it.isNotBlank() } ?: "（无标题）",
                        style = MaterialTheme.typography.bodyLarge,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (switching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                if (!compact) {
                    SessionMetaLine(s, lastSession, badge)
                }
            }
            // Trailing "…" menu, vertically centered on the row.
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert, "会话选项",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
                DevinMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DevinMenuItem(
                        "复制会话 ID",
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(s.sessionId))
                            Toast.makeText(
                                context, "已复制会话 ID", Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                    if (canDelete) {
                        DevinMenuDivider()
                        DevinMenuItem(
                            "删除",
                            danger = true,
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Second line: relative time followed by inline status badges. */
@Composable
private fun SessionMetaLine(s: AcpSession, lastSession: Boolean, badge: SessionBadge?) {
    val cs = MaterialTheme.colorScheme
    // (label, showOrangeDot) pairs in display order.
    val items = mutableListOf<Pair<String, Boolean>>()
    relativeSessionTime(s.lastActivity).takeIf { it.isNotBlank() }
        ?.let { items += it to false }
    if (s.isLocked) items += "占用中" to true
    if (s.pendingPermission || badge?.pendingPermission == true) items += "待授权" to true
    if (badge?.pendingInput == true) items += "待回答" to true
    if (s.running || badge?.running == true) items += "运行中" to false
    if (badge?.unread == true) items += "新消息" to false
    if (s.planFilePath != null) items += "计划" to false
    if (!s.hasUserMessages && s.title.isNullOrBlank()) items += "空会话" to false
    if (lastSession) items += "上次" to false
    if (items.isEmpty()) return
    Row(
        Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, locked) ->
            if (i > 0) {
                Text(" · ", style = MaterialTheme.typography.bodySmall, color = cs.outline)
            }
            if (locked) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(DevinGoColors.statusOrange),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- bottom bar -----------------------------------------------------------------------

/** Filter/sort button + "新建会话" pill floating over the session list. */
@Composable
internal fun HomeBottomBar(
    busy: Boolean,
    configured: Boolean,
    statusText: String,
    sortBy: HomeSort,
    hideEmpty: Boolean,
    compact: Boolean,
    filterPage: HomeFilterPage?,
    onFilterPage: (HomeFilterPage?) -> Unit,
    onSort: (HomeSort) -> Unit,
    onHideEmpty: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit,
    onNewSession: () -> Unit,
    newSessionLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Surface(
                onClick = {
                    onFilterPage(if (filterPage == null) HomeFilterPage.Root else null)
                },
                interactionSource = interaction,
                modifier = Modifier.size(48.dp).pressScale(interaction),
                shape = CircleShape,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(Hairline, cs.outlineVariant),
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        HugeIcons.SlidersHorizontal, "筛选与排序",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface,
                    )
                }
            }
            DevinMenu(
                expanded = filterPage != null,
                onDismissRequest = { onFilterPage(null) },
            ) {
                when (filterPage) {
                    HomeFilterPage.Sort -> {
                        DevinMenuCheckItem(
                            "最近活动", checked = sortBy == HomeSort.Recent,
                            onClick = { onSort(HomeSort.Recent); onFilterPage(null) },
                        )
                        DevinMenuCheckItem(
                            "标题", checked = sortBy == HomeSort.Title,
                            onClick = { onSort(HomeSort.Title); onFilterPage(null) },
                        )
                    }
                    HomeFilterPage.Filter -> {
                        DevinMenuItem(
                            "隐藏空会话",
                            onClick = { onHideEmpty(!hideEmpty) },
                            trailing = {
                                DevinSwitch(checked = hideEmpty, onCheckedChange = onHideEmpty)
                            },
                        )
                    }
                    else -> {
                        DevinMenuItem(
                            "排序",
                            subtitle = if (sortBy == HomeSort.Recent) "最近活动" else "标题",
                            onClick = { onFilterPage(HomeFilterPage.Sort) },
                            trailing = {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = cs.onSurfaceVariant,
                                )
                            },
                        )
                        DevinMenuItem(
                            "筛选",
                            subtitle = if (hideEmpty) "隐藏空会话" else null,
                            onClick = { onFilterPage(HomeFilterPage.Filter) },
                            trailing = {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown, null,
                                    modifier = Modifier.size(18.dp),
                                    tint = cs.onSurfaceVariant,
                                )
                            },
                        )
                        DevinMenuDivider()
                        DevinMenuItem(
                            "紧凑视图",
                            onClick = { onCompact(!compact) },
                            trailing = {
                                DevinSwitch(checked = compact, onCheckedChange = onCompact)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onNewSession,
            enabled = configured && !newSessionLoading,
            modifier = Modifier.height(48.dp),
            shape = CircleShape,
            color = if (configured && !newSessionLoading) {
                cs.primary
            } else {
                DevinGoColors.disabledButton
            },
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (newSessionLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = cs.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.Filled.Add, null,
                        modifier = Modifier.size(18.dp),
                        tint = cs.onPrimary,
                    )
                }
                Text(
                    if (newSessionLoading && statusText.isNotBlank()) {
                        statusText.take(12)
                    } else {
                        "新建会话"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (configured) cs.onPrimary else cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- misc -----------------------------------------------------------------------------

@Composable
internal fun LoginWaitCard(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = BorderStroke(Hairline, cs.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusHexagon(DevinGoColors.statusOrange, pulsing = true)
            Column {
                Text("等待浏览器登录", style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                Text(
                    "已在电脑上打开浏览器，请完成 Devin 登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** ISO timestamp → 刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy-MM-dd. */
internal fun relativeSessionTime(lastActivity: String?): String {
    if (lastActivity.isNullOrBlank()) return ""
    val instant = runCatching {
        kotlin.time.Instant.parse(lastActivity)
    }.getOrNull() ?: return lastActivity
    val minutes = (kotlin.time.Clock.System.now() - instant).inWholeMinutes
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
        else -> instant.toString().take(10)
    }
}
