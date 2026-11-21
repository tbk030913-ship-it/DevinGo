package me.rerere.rikkahub.ui.context

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.ui.toast.ToastType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adaptation shims: RikkaHub's composition locals, defaulted for DevinGo.
 * Navigation is a no-op — the ported richtext components only use it to
 * open auxiliary viewer screens, which DevinGo renders inline instead.
 */
val LocalSettings = compositionLocalOf { Settings() }

val LocalWorkspaceId = compositionLocalOf<String?> { null }

class Navigator {
    fun navigate(@Suppress("UNUSED_PARAMETER") route: Any) {}
}

val LocalNavController = compositionLocalOf { Navigator() }

/**
 * Minimal toast sink backed by android.widget.Toast. Provide a context-bound
 * instance at the app root; the default no-ops.
 */
class Toaster(context: Context? = null) {
    private val appContext = context?.applicationContext

    fun show(
        message: String,
        type: ToastType = ToastType.Normal,
        duration: Duration = 4.seconds,
    ) {
        val ctx = appContext ?: return
        val length =
            if (duration > 4.seconds) android.widget.Toast.LENGTH_LONG
            else android.widget.Toast.LENGTH_SHORT
        android.widget.Toast.makeText(ctx, message, length).show()
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }
