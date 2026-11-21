package com.devingo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Devin-sheet chrome and the small set of controls every v2 surface shares:
 * sheets, pill buttons, menus, settings rows and the logo mark. Colors come
 * from the theme roles — nothing here hard-codes a hex value.
 */

// ---- sheets -------------------------------------------------------------------

/**
 * Devin's bottom-sheet chrome: 24dp top corners on surfaceContainerLow, a
 * 36x4dp drag handle, and a 64dp header row with a white close circle at the
 * start and the title centered across the full row. Dismissal always runs
 * through [sheetState.hide] first so the sheet slides out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevinSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    halfExpanded: Boolean = false,
    scrollContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !halfExpanded)
    val scope = rememberCoroutineScope()
    val dismiss = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        },
        sheetState = sheetState,
        modifier = modifier,
        containerColor = cs.surfaceContainerLow,
        scrimColor = DevinGoColors.sheetScrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        cs.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                )
                val closeInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    interactionSource = closeInteraction,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .pressScale(closeInteraction),
                    shape = CircleShape,
                    color = cs.surfaceContainerLowest,
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close, "关闭",
                            modifier = Modifier.size(20.dp),
                            tint = cs.onSurface,
                        )
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollContent) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
                content = content,
            )
        }
    }
}

// ---- pill buttons --------------------------------------------------------------

private enum class PillKind { Black, Outline, Danger }

/**
 * The signature Devin action: a 48dp full-round pill. [BlackPillButton] is the
 * primary (black) variant, [OutlinePillButton] the bordered secondary and
 * [DangerPillButton] the error-container variant used for destructive allows.
 * [loading] cross-fades the label to a spinner + text.
 */
@Composable
fun BlackPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
) = PillButton(PillKind.Black, text, onClick, modifier, leadingIcon, loading, enabled)

@Composable
fun OutlinePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) = PillButton(PillKind.Outline, text, onClick, modifier, leadingIcon, false, enabled)

@Composable
fun DangerPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) = PillButton(PillKind.Danger, text, onClick, modifier, leadingIcon, false, enabled)

@Composable
private fun PillButton(
    kind: PillKind,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingIcon: (@Composable () -> Unit)?,
    loading: Boolean,
    enabled: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val active = enabled && !loading
    val (container, content, border) = when (kind) {
        PillKind.Black -> Triple(
            if (enabled) cs.primary else DevinGoColors.disabledButton,
            if (enabled) cs.onPrimary else cs.onSurfaceVariant,
            null,
        )
        PillKind.Outline -> Triple(
            cs.surfaceContainerLowest,
            cs.onSurface,
            BorderStroke(1.dp, cs.outlineVariant),
        )
        PillKind.Danger -> Triple(
            cs.errorContainer,
            cs.onErrorContainer,
            null,
        )
    }
    Surface(
        onClick = onClick,
        enabled = active,
        interactionSource = interaction,
        modifier = modifier
            .height(48.dp)
            .then(if (active) Modifier.pressScale(interaction) else Modifier),
        shape = CircleShape,
        color = container,
        border = border,
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = loading,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "pillLoading",
            ) { isLoading ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = content,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        leadingIcon?.invoke()
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.W500,
                        ),
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- menus ---------------------------------------------------------------------

/**
 * DropdownMenu in Devin's chrome: surfaceContainerLow, 12dp corners, a 1dp
 * outlineVariant border and a soft 6dp shadow. Items come from
 * [DevinMenuItem]/[DevinMenuCheckItem]; groups are split by [DevinMenuDivider].
 */
@Composable
fun DevinMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant),
        shadowElevation = 6.dp,
        content = content,
    )
}

/** 48dp menu row: optional 18dp leading icon, bodyLarge label, trailing slot. */
@Composable
fun DevinMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val contentColor = if (danger) cs.error else cs.onSurface
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        leadingIcon = icon?.let { iv ->
            {
                Icon(
                    iv, null,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
        },
        trailingIcon = trailing,
    )
}

/** Menu row with a trailing check mark shown while [checked]. */
@Composable
fun DevinMenuCheckItem(
    text: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    DevinMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        subtitle = subtitle,
        trailing = {
            if (checked) {
                Icon(
                    Icons.Filled.Check, "已选择",
                    modifier = Modifier.size(18.dp),
                    tint = cs.onSurface,
                )
            }
        },
    )
}

/** Hairline group separator inside a [DevinMenu]. */
@Composable
fun DevinMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = Hairline,
    )
}

// ---- settings rows ---------------------------------------------------------------

/** Section caption above a group of settings rows (labelMedium, onSurfaceVariant). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // No start padding: callers inside DevinSheet already get 20dp content
        // padding; SettingsScreen passes its own start padding via [modifier]
        // so headers always land on the row-title left edge.
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * One settings row: title bodyLarge (+ optional bodySmall subtitle), min 56dp,
 * 20dp horizontal padding. [trailing] hosts a switch, a value+chevron or a
 * check; rows with [onClick] but no trailing get a chevron automatically.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) cs.onSurface
                else cs.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.Filled.KeyboardArrowRight, null,
                modifier = Modifier.size(20.dp),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

/**
 * Devin's switch: black track + white thumb when on; flat #E6E6E6 track with
 * no border when off.
 */
@Composable
fun DevinSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = cs.primary,
            checkedThumbColor = cs.surfaceContainerLowest,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = cs.surfaceContainerHighest,
            uncheckedThumbColor = cs.surfaceContainerLowest,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Trailing "value ›" label used by tappable settings rows. */
@Composable
fun SettingValue(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.KeyboardArrowRight, null,
            modifier = Modifier.size(16.dp),
            tint = cs.onSurfaceVariant,
        )
    }
}

// ---- logo -----------------------------------------------------------------------

/**
 * The three-hexagon cluster mark, drawn in Canvas — the grey logo Devin shows
 * on its Home header avatar and empty states. [color] defaults to the
 * reference's light grey.
 */
@Composable
fun DevinLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    color: Color = Color(0xFFE3E3E3),
) {
    Canvas(modifier.size(size)) {
        drawHexagonCluster(color)
    }
}

private fun DrawScope.drawHexagonCluster(color: Color) {
    val w = size.width
    val h = size.height
    val r = minOf(w, h) / 2f * 0.34f
    // Triangle cluster: apex hex on top, two base hexes below.
    val centers = listOf(
        Offset(w * 0.5f, h * 0.30f),
        Offset(w * 0.30f, h * 0.68f),
        Offset(w * 0.70f, h * 0.68f),
    )
    centers.forEach { c ->
        val path = Path()
        for (i in 0 until 6) {
            val angle = Math.toRadians(-90.0 + i * 60.0)
            val x = c.x + r * cos(angle).toFloat()
            val y = c.y + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

// ---- snackbar -------------------------------------------------------------------

/** Snackbar chrome matching Devin: inverseSurface container, 12dp radius. */
@Composable
fun DevinSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(12.dp),
            containerColor = cs.inverseSurface,
            contentColor = cs.inverseOnSurface,
            actionColor = cs.inverseOnSurface,
            dismissActionContentColor = cs.inverseOnSurface.copy(alpha = 0.6f),
        )
    }
}
