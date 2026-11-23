package com.devingo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.ui.theme.DevinGoColors
import kotlin.math.ceil
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.Queue01
import me.rerere.hugeicons.stroke.UserGroup

// t3code FloatingWorkingControl, ported 1:1 (MIT):
//   .research/t3code-20261123/apps/mobile/src/features/threads/
//     floating-working-control.tsx  (L29-43 constants, L62-388 layout)
//     floating-working-status.ts    (connection label/onReconnect priority)
private val CONTROL_HEIGHT = 40.dp // spec: 40dp pill (t3 uses 38.5)
private val CONTROL_ROW_GAP = 14.dp // t3 CONTROL_ROW_GAP (separation math only)
private val CONTROL_SEPARATION = (CONTROL_ROW_GAP + CONTROL_HEIGHT) / 2
private val SCROLL_GAP = 8.dp // spec: pill ↔ ↓ gap 8dp
private val CONTROL_EASING =
    CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f) // Easing.out(Easing.cubic)
private val CONTROL_TIMING = tween<Float>(240, easing = CONTROL_EASING)
private val SIZE_TIMING = tween<androidx.compose.ui.unit.IntSize>(
    240,
    easing = CONTROL_EASING,
)
private val CONTROL_ENTER = fadeIn(tween(180)) // CONTROL_ENTERING
private val CONTROL_EXIT = fadeOut(tween(120)) // CONTROL_EXITING
private val LABEL_ENTER = fadeIn(tween(160, delayMillis = 80)) // LABEL_ENTERING
private val LABEL_EXIT = fadeOut(tween(100)) // LABEL_EXITING

/** Which status story the capsule's first segment tells (priority order). */
private enum class CapsuleStatus { Connection, Running }

/**
 * The floating work capsule above the composer: a frosted pill whose
 * segments compose by priority — connection (reconnect countdown, tap =
 * retry now) beats running (pulsing hexagon + live action + 停止), then
 * subagents (stub for A7.3) and the queue. The circular ↓ button slides
 * out from beneath the pill's right edge while the transcript isn't at
 * bottom; the whole overlay fades when there's nothing to say.
 */
@Composable
internal fun FloatingWorkingControl(
    reconnecting: Boolean,
    connectionFailed: Boolean,
    reconnectRetryAtMs: Long?,
    statusText: String,
    running: Boolean,
    runningDetail: String?,
    turnStartedAtMs: Long,
    queuedCount: Int,
    subagentCount: Int,
    showScrollToEnd: Boolean,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSubagents: () -> Unit,
    onScrollToEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val status = when {
        reconnecting || connectionFailed -> CapsuleStatus.Connection
        running -> CapsuleStatus.Running
        else -> null
    }
    val hasCapsule = status != null || queuedCount > 0 || subagentCount > 0
    AnimatedVisibility(
        visible = hasCapsule || showScrollToEnd,
        enter = CONTROL_ENTER,
        exit = CONTROL_EXIT,
        modifier = modifier,
    ) {
        // t3's separationProgress: 1 = ↓ sits beside the pill, 0 = tucked
        // under its right edge. The pill shifts +SEP, the arrow −SEP.
        val p by animateFloatAsState(
            targetValue = if (showScrollToEnd) 1f else 0f,
            animationSpec = CONTROL_TIMING,
            label = "separation",
        )
        val sepPx = with(LocalDensity.current) { CONTROL_SEPARATION.toPx() }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SCROLL_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasCapsule) {
                Surface(
                    shape = CircleShape,
                    color = cs.surfaceContainerLowest.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, cs.outlineVariant),
                    shadowElevation = 3.dp,
                    modifier = Modifier.graphicsLayer {
                        translationX = sepPx * (1f - p)
                    },
                ) {
                    Row(
                        Modifier
                            .height(CONTROL_HEIGHT)
                            .animateContentSize(SIZE_TIMING),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (status != null) {
                            AnimatedContent(
                                targetState = status,
                                transitionSpec = {
                                    LABEL_ENTER togetherWith LABEL_EXIT
                                },
                                label = "capsuleStatus",
                            ) { s ->
                                when (s) {
                                    CapsuleStatus.Connection -> ConnectionLabel(
                                        reconnecting = reconnecting,
                                        retryAtMs = reconnectRetryAtMs,
                                        statusText = statusText,
                                        onClick = onReconnect,
                                    )
                                    CapsuleStatus.Running -> RunningLabel(
                                        detail = runningDetail,
                                        startedAtMs = turnStartedAtMs,
                                        onStop = onStop,
                                    )
                                }
                            }
                        }
                        if (subagentCount > 0) {
                            // t3: a segment's divider exists only when a
                            // prior segment does.
                            if (status != null) SegmentDivider()
                            CapsuleSegment(onClick = onOpenSubagents) {
                                Icon(
                                    HugeIcons.UserGroup, null,
                                    modifier = Modifier.size(13.dp),
                                    tint = cs.onSurfaceVariant,
                                )
                                CapsuleText("$subagentCount 子代理")
                            }
                        }
                        if (queuedCount > 0) {
                            if (status != null || subagentCount > 0) {
                                SegmentDivider()
                            }
                            CapsuleSegment(onClick = onOpenQueue) {
                                Icon(
                                    HugeIcons.Queue01, null,
                                    modifier = Modifier.size(13.dp),
                                    tint = cs.onSurfaceVariant,
                                )
                                CapsuleText("$queuedCount 条排队")
                                Icon(
                                    HugeIcons.ChevronDown, null,
                                    modifier = Modifier.size(10.dp),
                                    tint = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Surface(
                onClick = onScrollToEnd,
                enabled = showScrollToEnd,
                shape = CircleShape,
                color = cs.surfaceContainerLowest.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, cs.outlineVariant),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .size(CONTROL_HEIGHT)
                    .graphicsLayer {
                        translationX = -sepPx * (1f - p)
                        alpha = p
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        HugeIcons.ChevronDown, "滚动到底部",
                        modifier = Modifier.size(20.dp),
                        tint = cs.onSurface,
                    )
                }
            }
        }
    }
}

/** t3's `h-4 w-px bg-border` between pill segments. */
@Composable
private fun SegmentDivider() {
    Box(
        Modifier
            .height(16.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** One padded, tappable segment of the capsule (t3's px-3 h-11 rows). */
@Composable
private fun CapsuleSegment(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier
            .height(CONTROL_HEIGHT)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun CapsuleText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum",
        ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * t3's connection label: red 8dp dot + countdown/attempt text; the whole
 * segment is the tap target — it cuts the backoff wait short
 * (kickReconnect / `connectionFloatingStatus`'s onReconnect).
 */
@Composable
private fun ConnectionLabel(
    reconnecting: Boolean,
    retryAtMs: Long?,
    statusText: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Remaining backoff seconds — ticks half-second so the ceil never
    // shows a stale digit; parked while the dial itself is in flight.
    var remaining by remember(retryAtMs) {
        mutableIntStateOf(
            retryAtMs?.let { ceil((it - System.currentTimeMillis()) / 1000f).toInt() } ?: 0,
        )
    }
    LaunchedEffect(retryAtMs) {
        val at = retryAtMs ?: return@LaunchedEffect
        while (true) {
            remaining = ceil((at - System.currentTimeMillis()) / 1000f).toInt()
            delay(500)
        }
    }
    val text = when {
        retryAtMs != null ->
            "重连中 (${remaining.coerceAtLeast(0)}s) · 点击立即重试"
        reconnecting -> "重连中 · 点击立即重试"
        else -> statusText.ifBlank { "连接失败" } + " · 点击重试"
    }
    Row(
        Modifier
            .height(CONTROL_HEIGHT)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(cs.error),
        )
        CapsuleText(text, Modifier.widthIn(max = 220.dp))
    }
}

/** The running story: pulsing hexagon + live action + the 停止 segment. */
@Composable
private fun RunningLabel(
    detail: String?,
    startedAtMs: Long,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Same per-second ticker as WorkRow's live elapsed.
    var elapsed by remember(startedAtMs) {
        mutableIntStateOf(
            if (startedAtMs > 0L) {
                ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
            } else {
                0
            },
        )
    }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsed = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
        }
    }
    val label = buildString {
        append(detail?.takeIf { it.isNotBlank() } ?: "处理中")
        if (startedAtMs > 0L) {
            append(" · ${elapsed / 60}:")
            append((elapsed % 60).toString().padStart(2, '0'))
        }
    }
    // t3's ShimmeringWorkContent on the working label — the same
    // reduced-motion/lifecycle-gated sweep as the live WorkRow.
    Row(
        Modifier
            .height(CONTROL_HEIGHT)
            .padding(start = 14.dp)
            .steppedShimmer(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusHexagon(DevinGoColors.statusOrange, size = 10.dp, pulsing = true)
        CapsuleText(label, Modifier.widthIn(max = 200.dp))
    }
    SegmentDivider()
    Box(
        Modifier
            .height(CONTROL_HEIGHT)
            .clip(CircleShape)
            .clickable(onClick = onStop)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "停止",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurface,
            maxLines = 1,
        )
    }
}
