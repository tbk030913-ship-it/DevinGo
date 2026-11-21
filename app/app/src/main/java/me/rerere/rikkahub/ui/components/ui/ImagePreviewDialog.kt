package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

/**
 * Adaptation shim: RikkaHub's pager/zoom viewer pulled an extra library and
 * a FilesManager dependency. DevinGo's version is a plain full-screen image
 * preview — tap outside to dismiss.
 */
@Composable
fun ImagePreviewDialog(
    images: List<String>,
    initialPage: Int = 0,
    actions: @Composable RowScope.(page: Int) -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    val url = images.getOrNull(initialPage) ?: return
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
