package com.devingo.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.devingo.app.acp.AcpSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Session-level action surfaces shared by Home and Chat (M9.1): the rename
 * sheet (chat `···` menu + session-row menu), the lock-takeover confirm
 * dialog's holder label, and the Markdown share sheet's file handoff.
 */

/** Rename text-input sheet — same chrome as Settings' EditFieldSheet. */
@Composable
internal fun RenameSessionSheet(
    initialTitle: String,
    submitting: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var value by remember(initialTitle) { mutableStateOf(initialTitle) }
    DevinSheet(title = "重命名会话", onDismiss = onDismiss) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            supportingText = {
                error?.let { Text(it, color = cs.error) }
            },
            placeholder = { Text("会话标题", color = cs.outline) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = cs.outlineVariant,
                cursorColor = cs.onSurface,
            ),
        )
        Spacer(Modifier.height(16.dp))
        BlackPillButton(
            text = "保存",
            loading = submitting,
            enabled = value.isNotBlank() && !submitting,
            onClick = { onSubmit(value.trim()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "（其他终端 · PID 1234）"-style holder detail for the takeover dialog. */
internal fun lockHolderLabel(s: AcpSession): String {
    val parts = listOfNotNull(
        s.lockHolderClient?.takeIf { it.isNotBlank() },
        s.lockHolderPid?.let { "PID $it" },
    )
    return parts.joinToString(" · ")
}

/**
 * Write [markdown] under the FileProvider's share dir and fire the system
 * share sheet with it attached as a real `.md` file. [shareUrl] (the
 * `_cognition.ai/session/share` guest link, when the agent produced one)
 * rides EXTRA_TEXT so targets that prefer text still get a link.
 */
internal suspend fun shareSessionMarkdown(
    context: Context,
    title: String,
    markdown: String,
    shareUrl: String?,
    idTag: String,
) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // The session tag keeps same-titled exports from overwriting each
        // other while an earlier content:// grant is still live.
        val name = (
            title.ifBlank { "session" }.take(40) +
                idTag.replace(Regex("""[^\p{L}\p{N}._\-]+"""), "")
                    .takeLast(12).let { if (it.isBlank()) "" else "-$it" }
            )
            .replace(Regex("""[^\p{L}\p{N}._\- ]+"""), "")
            .trim().ifBlank { "session" } + ".md"
        File(dir, name).apply { writeText(markdown) }
    }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_SUBJECT, title)
        if (!shareUrl.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, shareUrl)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享会话 Markdown"))
}

/** [text] with every (case-insensitive) [query] match bolded + raised. */
internal fun queryHighlighted(
    text: String,
    query: String,
    base: Color,
    highlight: Color,
): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var i = 0
    while (i < text.length) {
        val hit = text.indexOf(query, i, ignoreCase = true)
        if (hit < 0) {
            pushStyle(SpanStyle(color = base))
            append(text.substring(i))
            pop()
            break
        }
        if (hit > i) {
            pushStyle(SpanStyle(color = base))
            append(text.substring(i, hit))
            pop()
        }
        pushStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold))
        append(text.substring(hit, hit + query.length))
        pop()
        i = hit + query.length
    }
}
