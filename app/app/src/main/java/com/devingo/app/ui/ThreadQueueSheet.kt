package com.devingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.devingo.app.ImageAttachment
import com.devingo.app.QueuedPrompt
import com.devingo.app.ui.theme.DevinGoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnForward
import me.rerere.hugeicons.stroke.Delete02

// t3code ThreadQueueSheet, ported (MIT):
//   .research/t3code-20261123/apps/mobile/src/features/threads/
//     ThreadQueueControl.tsx (L130-566 — row, Send-now pill, swipe remove,
//     tap-edit, 3-thumb strip, auto-pop on empty)
private const val THUMBNAIL_LIMIT = 3

/**
 * The parked-prompt manager opened from the floating capsule's queue
 * segment. Each row: tap = pull back into the composer, the black 立即转向
 * pill = de-queue + get it in front of the agent now (text steers mid-turn;
 * an image prompt cancels the live turn and sends fresh), swipe end-to-start
 * or the trash button = remove. Once the queue drains to empty the sheet
 * closes itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadQueueSheet(
    queued: List<QueuedPrompt>,
    running: Boolean,
    editingId: Long?,
    onDismiss: () -> Unit,
    onSteer: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Serializes row actions — t3's busyRunId: a fired row action removes
    // the row, so a fast second tap must not land on the next item.
    var busy by remember { mutableStateOf(false) }
    fun act(action: () -> Unit) {
        if (busy) return
        busy = true
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        action()
        scope.launch {
            delay(350)
            busy = false
        }
    }
    // t3 auto-pop: the sheet only makes sense while the queue has items.
    val hadQueued = remember { queued.isNotEmpty() }
    LaunchedEffect(queued.isEmpty()) {
        if (queued.isEmpty() && hadQueued) onDismiss()
    }
    DevinSheet(title = "排队消息", onDismiss = onDismiss) {
        if (queued.isEmpty()) {
            Text(
                "没有排队消息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
        ) {
            itemsIndexed(queued, key = { _, q -> q.id }) { index, q ->
                Column {
                    QueueRow(
                        queued = q,
                        index = index,
                        editing = q.id == editingId,
                        canSteer = running &&
                            (q.text.isNotBlank() || q.attachments.isNotEmpty()),
                        enabled = !busy,
                        onSteer = { act { onSteer(q.id) } },
                        onEdit = { act { onEdit(q.id) } },
                        onRemove = { act { onRemove(q.id) } },
                    )
                    // t3's row `border-b` — the last row's edge is the
                    // sheet's own, so it gets no divider.
                    if (index < queued.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = Hairline,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    queued: QueuedPrompt,
    index: Int,
    editing: Boolean,
    canSteer: Boolean,
    enabled: Boolean,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // t3's right-side swipe action — full swipe commits the removal. A
    // commit landing inside the busy window gets dropped by act{} — snap
    // the row back instead of leaving it visually dismissed.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { enabled && it == SwipeToDismissBoxValue.EndToStart },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(cs.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        HugeIcons.Delete02, "删除",
                        modifier = Modifier.size(16.dp),
                        tint = cs.onError,
                    )
                    Text(
                        "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onError,
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = {
            if (enabled) onRemove() else scope.launch { dismissState.reset() }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, onClick = onEdit)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QueueThumbs(queued.attachments)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        queued.text.ifBlank { "[${queued.attachments.size} 张图片]" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (editing) cs.onSurfaceVariant else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (editing) {
                        Text(
                            "编辑中",
                            style = MaterialTheme.typography.labelSmall,
                            color = DevinGoColors.link,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    queueRowSubtitle(queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // t3's black Send-now pill — kept as the row's primary action.
            Surface(
                onClick = onSteer,
                enabled = enabled && canSteer,
                shape = CircleShape,
                color = if (canSteer) cs.primary else cs.surfaceContainerHighest,
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        HugeIcons.ArrowTurnForward, null,
                        modifier = Modifier.size(12.dp),
                        tint = if (canSteer) cs.onPrimary else cs.onSurfaceVariant,
                    )
                    Text(
                        "立即转向",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canSteer) cs.onPrimary else cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                HugeIcons.Delete02, "删除排队消息 ${index + 1}",
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onRemove)
                    .padding(9.dp),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

private fun queueRowSubtitle(q: QueuedPrompt): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(Date(q.createdAtMs))
    return if (q.attachments.isNotEmpty()) {
        "$time · ${q.attachments.size} 张图片"
    } else {
        time
    }
}

/** Up to [THUMBNAIL_LIMIT] image thumbs + a "+N" overflow chip (t3 L473-506). */
@Composable
private fun QueueThumbs(attachments: List<ImageAttachment>) {
    if (attachments.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.take(THUMBNAIL_LIMIT).forEach { a ->
            AsyncImage(
                model = a.bytes,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        val overflow = attachments.size - THUMBNAIL_LIMIT
        if (overflow > 0) {
            Box(
                Modifier
                    .height(24.dp)
                    .width(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}
