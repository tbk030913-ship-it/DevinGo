package me.rerere.rikkahub.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * Utility subset ported from RikkaHub (utils/ComposeExt.kt,
 * utils/StringUtils.kt, utils/ContextUtil.kt) — only what the richtext
 * components reference.
 */
fun String.escapeHtml(): String = buildString(length) {
    for (c in this@escapeHtml) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

fun Color.toCssHex(): String {
    fun f(v: Float) = (v * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(f(alpha), f(red), f(green), f(blue))
}

@Composable
fun TextUnit.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }

/** Save [bitmap] into the system gallery (MediaStore on Q+). */
fun Context.exportImage(
    @Suppress("UNUSED_PARAMETER") activity: android.app.Activity,
    bitmap: Bitmap,
    fileName: String,
) {
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES,
            )
        }
    }
    runCatching {
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
