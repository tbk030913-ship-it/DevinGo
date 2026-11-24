// Local web preview sheet (N6.4): a fullscreen WebView pointed at the
// bridge's /preview tunnel. With a token it loads /preview-auth first —
// the 302 mints the devingo_prev cookie (Authorization can't ride on
// subresource loads), then the proxied page + its HMR socket stay inside
// the sheet. Without a token it loads /preview/{port}/ directly.
// Port detection scans the transcript newest-first for localhost:port.

package com.devingo.app.ui.terminal

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.devingo.app.ChatItem
import com.devingo.app.Kind
import com.devingo.app.RowKind
import com.devingo.app.TranscriptModel
import com.devingo.app.ui.DevinSheet
import com.devingo.app.ui.monoText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnForward

private val PORT_RE = Regex("""(?:localhost|\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")

/** Newest dev-server port mentioned in the transcript, or null. Exec output
 *  ("Local: http://localhost:5173/") lands on Kind.Tool detail, so scan it
 *  too; any IPv4 host works — the proxy only uses the port on localhost.
 *  rows[0] renders at the visual bottom — scanning forward hits the latest
 *  messages first; within a text the last match wins. */
internal fun detectPreviewPort(model: TranscriptModel, tail: ChatItem?): Int? {
    fun scan(text: String): Int? =
        PORT_RE.findAll(text).lastOrNull()
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it in 1..65535 }

    tail?.let { t -> scan(t.text)?.let { return it } }
    for (row in model.rows) {
        val item = when (row) {
            is RowKind.Body -> model.itemById[row.itemId]
            is RowKind.User -> model.itemById[row.itemId]
            else -> null
        } ?: continue
        if (item.kind != Kind.User && item.kind != Kind.Agent && item.kind != Kind.Tool) continue
        scan(item.text)?.let { return it }
        scan(item.detail.orEmpty())?.let { return it }
    }
    return null
}

@Composable
fun PreviewSheet(
    port: Int?,
    httpBase: String,
    token: String?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var chosenPort by remember(port) { mutableStateOf(port) }
    var portInput by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // A dismissed WebView keeps its Chromium renderer alive — destroy it
    // when the sheet (or the port-keyed instance) leaves composition.
    DisposableEffect(webView) {
        val w = webView
        onDispose { w?.destroy() }
    }

    DevinSheet(
        title = "预览",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- url bar ------------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chosenPort?.let { "localhost:$it" } ?: "localhost",
                    style = monoText(MaterialTheme.typography.labelLarge),
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { webView?.reload() },
                    enabled = chosenPort != null,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.ArrowTurnForward, "刷新",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }

            val p = chosenPort
            if (p == null) {
                // ---- port entry (no detected port) ----------------------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text(
                                "端口，如 3000",
                                color = cs.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = monoText(MaterialTheme.typography.bodyMedium),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = cs.onSurface,
                            unfocusedTextColor = cs.onSurface,
                            focusedBorderColor = cs.outline,
                            unfocusedBorderColor = cs.outlineVariant,
                        ),
                    )
                    TextButton(
                        onClick = {
                            portInput.toIntOrNull()
                                ?.takeIf { it in 1..65535 }
                                ?.let { chosenPort = it }
                        },
                    ) {
                        Text("打开")
                    }
                }
            } else {
                key(p) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(Color.Black.toArgb())
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mixedContentMode =
                                    WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                webViewClient = WebViewClient()
                                if (!token.isNullOrEmpty()) {
                                    // Mints the devingo_prev cookie then 302s
                                    // into the tunnel — subresource + socket
                                    // loads carry no Authorization header.
                                    loadUrl(
                                        "$httpBase/preview-auth?port=$p",
                                        mapOf("Authorization" to "Bearer $token"),
                                    )
                                } else {
                                    loadUrl("$httpBase/preview/$p/")
                                }
                            }
                        },
                        update = { webView = it },
                    )
                }
            }
        }
    }
}
