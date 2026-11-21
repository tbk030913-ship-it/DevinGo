package com.devingo.app.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared primitives kept from the v1 workbench: hairlines, monospace text
 * helpers, reduced-motion detection, the status hexagon, the shimmer sweep
 * and the press-scale feedback. The v1 rail/timeline primitives are gone —
 * v2 renders turns and work rows instead.
 */

/** Devin's separators are hairlines; half-density pixels read as 1px lines. */
val Hairline = 0.5.dp

/** JetBrains Mono for code, commands, paths, ids and token counts only. */
val MonoFamily = FontFamily(Font(R.font.jetbrains_mono))

/** True when the user reduced/disabled animations at the system level. */
@Composable
fun isReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/** Monospace body text for code, paths, terminal output and ids. */
@Composable
fun monoText(base: TextStyle = MaterialTheme.typography.bodySmall): TextStyle =
    base.copy(fontFamily = MonoFamily)

/**
 * Devin's activity glyph: a pointy-top hexagon. [pulsing] breathes the alpha
 * between 0.45 and 1 over 900ms — disabled under reduced motion.
 */
@Composable
fun StatusHexagon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
    pulsing: Boolean = false,
) {
    val alpha = if (pulsing && !isReducedMotion()) {
        val t = rememberInfiniteTransition(label = "hexPulse")
        val a by t.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "hexAlpha",
        )
        a
    } else 1f
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            for (i in 0 until 6) {
                val angle = Math.toRadians(-90.0 + i * 60.0)
                val x = cx + radius * cos(angle).toFloat()
                val y = cy + radius * sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(path, color.copy(alpha = alpha))
    }
}

/**
 * Light sweep across the receiver, used by the live "工作中" work row. The
 * animated fraction is only read inside the draw lambda (drawWithCache), so
 * frames invalidate drawing without recomposition. Static under reduced
 * motion — callers should hide it entirely in that case.
 */
@Composable
fun Modifier.shimmer(): Modifier {
    if (isReducedMotion()) return this
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    return drawWithCache {
        onDrawWithContent {
            drawContent()
            val w = size.width
            if (w <= 0f) return@onDrawWithContent
            val band = w * 0.45f
            val startX = -band + (w + band * 2f) * progress
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.38f),
                    1f to Color.Transparent,
                    start = Offset(startX, 0f),
                    end = Offset(startX + band, size.height),
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
    }
}

/**
 * Press feedback shared by pills, icon buttons and close circles: scales to
 * 0.96 while [interactionSource] reports a press. Pass the same source the
 * clickable/Surface uses so the states stay in sync.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
