package com.devingo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devingo.app.AppVisibility
import kotlinx.coroutines.delay

// t3code's "breathing" work-row shimmer, ported 1:1 (MIT):
//   .research/t3code-20261123/apps/mobile/src/features/threads/work-shimmer-steps.ts
//   .research/t3code-20261123/apps/mobile/src/features/threads/thread-work-log.tsx
//     (ShimmeringWorkContent — SHIMMER_WIDTH, gradient stops, gating)
private const val SHIMMER_SWEEP_MS = 1_350L
private const val SHIMMER_PAUSE_MS = 1_450L
private const val SHIMMER_STEPS = 12
private const val SHIMMER_STEP_MS = SHIMMER_SWEEP_MS / SHIMMER_STEPS
private val SHIMMER_BAND = 72.dp

/**
 * Foreground flag for animation gating — the Compose view of
 * [AppVisibility.isForeground] (MainActivity flips it on onStart/onStop,
 * the same lifecycle events observed here).
 */
@Composable
private fun rememberAppForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(AppVisibility.isForeground) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/**
 * Stepped highlight sweep over the receiver: a 72dp band crosses the
 * content's own width in [SHIMMER_STEPS] discrete jumps over
 * [SHIMMER_SWEEP_MS], then rests [SHIMMER_PAUSE_MS] (≈2800ms cycle). The
 * 7-stop symmetric gradient is t3code's; the band paints only the drawn
 * pixels (glyphs/icons) because the content renders into an offscreen
 * layer — no light box washing the row's transparent area.
 *
 * Progress is a plain float state read inside the draw pass, so each step
 * invalidates drawing only — nothing recomposes, and the pause emits zero
 * frames (vs the old 60fps `infiniteRepeatable`). The timer is gated by
 * reduced motion and app foreground, and dies with the composable when the
 * row leaves the LazyColumn viewport. Apply to a content-width container
 * so the sweep spans exactly icon+text, not the row's empty tail.
 */
fun Modifier.steppedShimmer(
    highlight: Color = Color.Unspecified,
): Modifier = composed {
    val tint = if (highlight == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        highlight
    }
    val enabled = !isReducedMotion() && rememberAppForeground()
    // A plain float mutated by the timer coroutine — read inside
    // onDrawWithContent, so a step is a draw-only invalidation.
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            progress.floatValue = 0f
            return@LaunchedEffect
        }
        // 1:1 with startShimmerSteps: step k lands at k·STEP_MS; after the
        // last step the next waits PAUSE+STEP. Progress 0 and 1 both park
        // the band off the content edges — nothing draws during the pause.
        while (true) {
            for (step in 1..SHIMMER_STEPS) {
                delay(SHIMMER_STEP_MS)
                progress.floatValue = step.toFloat() / SHIMMER_STEPS
                if (step == SHIMMER_STEPS) delay(SHIMMER_PAUSE_MS)
            }
        }
    }
    if (!enabled) return@composed this
    // Offscreen layer so SrcAtop blends against the row's own pixels —
    // the sweep tints only glyphs/icons, never the background underneath.
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val band = SHIMMER_BAND.toPx()
            val stops = arrayOf(
                0f to tint.copy(alpha = 0f),
                0.15f to tint.copy(alpha = 0.12f),
                0.35f to tint.copy(alpha = 0.55f),
                0.5f to tint.copy(alpha = 1f),
                0.65f to tint.copy(alpha = 0.55f),
                0.85f to tint.copy(alpha = 0.12f),
                1f to tint.copy(alpha = 0f),
            )
            onDrawWithContent {
                drawContent()
                val w = size.width
                if (w <= 0f) return@onDrawWithContent
                // translateX -band → contentWidth, the t3code range.
                val startX = -band + (w + band) * progress.floatValue
                drawRect(
                    brush = Brush.horizontalGradient(
                        *stops,
                        startX = startX,
                        endX = startX + band,
                    ),
                    topLeft = Offset(startX, 0f),
                    size = Size(band, size.height),
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
}
