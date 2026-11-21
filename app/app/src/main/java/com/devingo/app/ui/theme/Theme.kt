package com.devingo.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// DevinGo v2 mirrors Devin's own Android app: a light-only neutral palette
// sampled from the reference screenshots. Primary actions are black; the
// surface container ladder carries all elevation/hierarchy.

private val Light = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF1A7F37),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAFBE1),
    onSecondaryContainer = Color(0xFF0A3D1C),
    tertiary = Color(0xFF6E6E6E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDEDED),
    onTertiaryContainer = Color(0xFF111111),
    surface = Color(0xFFFCFCFC),
    surfaceDim = Color(0xFFD9D9D9),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFEDEDED),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFFEDEDED),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF6E6E6E),
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF111111),
    error = Color(0xFFD1242F),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFF82071E),
    outline = Color(0xFF929292),
    outlineVariant = Color(0xFFEBEBEB),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF111111),
    inverseSurface = Color(0xFF111111),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFF6E6E6E),
)

/**
 * Semantic accents that live outside the colorScheme because they encode
 * app-specific meaning (links, warnings, diffs, sheet scrim). Light-only —
 * no theme switching remains.
 */
object DevinGoColors {
    /** Status hexagon / activity dot. */
    val statusOrange = Color(0xFFF2913D)

    /** @path mentions and markdown links. */
    val link = Color(0xFF0969DA)

    val warning = Color(0xFF9A6700)
    val warningContainer = Color(0xFFFFF8C5)
    val onWarningContainer = Color(0xFF4D2D00)

    val diffAdd = Color(0xFF1A7F37)
    val diffRemove = Color(0xFFD1242F)

    /** Sheet/menu backdrop: black at 12% (reference dims #FCFCFC → ~#DDDDDD). */
    val sheetScrim = Color(0x1F000000)

    /** Disabled pill button container. */
    val disabledButton = Color(0xFFD4D4D4)
}

/** Devin's corner language: small radii everywhere, 24dp only for sheets. */
private val DevinGoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val DevinGoTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.W400),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.W400),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.W400),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.W600),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W600),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W400),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.W400),
        labelSmall = base.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.W500),
        headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.W500),
    )
}

@Composable
fun DevinGoTheme(content: @Composable () -> Unit) {
    // MaterialTheme's default motion scheme is the standard one; the
    // MotionScheme type itself is Kotlin-internal in material3 1.4.0.
    MaterialTheme(
        colorScheme = Light,
        typography = DevinGoTypography,
        shapes = DevinGoShapes,
        content = content,
    )
}
