// Fullscreen terminal console sheet (N6.4) — DevinSheet chrome over the
// ported TerminalCanvasView, with t3's hidden-EditText IME plumbing adapted
// from T3TerminalView (MIT): 1px focused input drives commitText/deletes,
// hardware keys map through TerminalNavigationKeyListener + Ctrl+letter,
// Enter sends CR. A chip row covers Esc/Tab/Ctrl/arrows; Ctrl is a one-shot
// modifier for the next typed character. No scrollback — 复制全部 asks the
// bridge for its raw-output ring. Selection is implemented client-side over
// the decoded grid (the bridge streams screen-only frames).

package com.devingo.app.ui.terminal

import android.graphics.Color as GfxColor
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import com.devingo.app.ui.DevinSheet
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

private const val DEL = '\u007F'
private const val ESC = '\u001B'

@Composable
fun TerminalSheet(
    sessionId: String,
    project: String,
    httpBase: String,
    token: String?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("连接中…") }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var ctl by remember { mutableStateOf<TerminalCtl?>(null) }

    val session = remember(httpBase, token, sessionId) {
        TerminalSession(httpBase, token, sessionId, object : TerminalSession.Listener {
            override fun onFrame(frame: TerminalFrame) {
                ctl?.setFrame(frame)
            }

            override fun onExit(code: Int) {
                exitCode = code
            }

            override fun onCopyAll(data: ByteArray) {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("terminal", String(data, Charsets.UTF_8))),
                    )
                }
                status = "已复制全部输出"
            }

            override fun onStatus(text: String) {
                status = text
            }
        })
    }
    DisposableEffect(session) { onDispose { session.close() } }

    // Ctrl chip = one-shot modifier: the next typed character becomes its
    // control byte. Quick chips bypass it (they send their bytes raw).
    fun sendTyped(text: String) {
        if (ctrlArmed && text.isNotEmpty()) {
            ctrlArmed = false
            val c = text[0]
            val b = when (c) {
                in 'a'..'z' -> c.code - 'a'.code + 1
                in 'A'..'Z' -> c.code - 'A'.code + 1
                '@' -> 0
                '[' -> 0x1B
                '\\' -> 0x1C
                ']' -> 0x1D
                else -> null
            }
            if (b != null) {
                session.sendInput(b.toChar().toString())
                // Only the first char takes Ctrl — an IME batch's tail
                // still goes through as plain text.
                if (text.length > 1) session.sendInput(text.substring(1))
                return
            }
        }
        session.sendInput(text)
    }

    DevinSheet(
        title = "终端 · $project",
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        // The bottom sheet's dialog window defaults to adjustPan — resize it
        // so the IME shrinks the canvas and the grid re-fits live.
        val view = LocalView.current
        DisposableEffect(view) {
            var p = view.parent
            while (p != null && p !is DialogWindowProvider) p = p.parent
            val window = (p as? DialogWindowProvider)?.window
            val prev = window?.attributes?.softInputMode
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { prev?.let { window.setSoftInputMode(it) } }
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            // ---- control row -------------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    exitCode?.let { "进程已退出 ($it)" } ?: status,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { session.sendCopyAll() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        HugeIcons.Copy01, "复制全部",
                        modifier = Modifier.size(18.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }

            // ---- quick keys ---------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyChip("Esc") { session.sendInput("$ESC") }
                KeyChip("Tab") { session.sendInput("\t") }
                KeyChip("Ctrl", active = ctrlArmed) { ctrlArmed = !ctrlArmed }
                KeyChip("Ctrl+C") { session.sendInput("\u0003") }
                KeyChip("↑") { session.sendInput("$ESC[A") }
                KeyChip("↓") { session.sendInput("$ESC[B") }
                KeyChip("←") { session.sendInput("$ESC[D") }
                KeyChip("→") { session.sendInput("$ESC[C") }
            }

            // ---- canvas + hidden IME input ------------------------------------
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val sentText = TerminalSentText()
                        val input = object : EditText(ctx) {
                            // Terminal input must stay out of credential autofill.
                            override fun getAutofillType(): Int = View.AUTOFILL_TYPE_NONE

                            override fun onCreateInputConnection(
                                outAttrs: EditorInfo,
                            ): InputConnection? =
                                super.onCreateInputConnection(outAttrs)?.let { conn ->
                                    TerminalInputConnection(conn, sentText, { true }) { count ->
                                        session.sendInput(DEL.toString().repeat(count))
                                    }
                                }
                        }
                        configureTerminalInput(input, sentText, ::sendTyped, session::sendInput)

                        val canvas = TerminalCanvasView(ctx)
                        val controller = TerminalCtl(session, canvas)
                        canvas.selectionDelegate = controller.selection
                        canvas.onRequestKeyboard = {
                            input.requestFocus()
                            val imm = ctx.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE,
                            ) as? InputMethodManager
                            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        }
                        canvas.onCellMetricsChanged = { controller.emitResize() }
                        canvas.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            controller.emitResize()
                        }
                        ctl = controller
                        FrameLayout(ctx).apply {
                            addView(
                                canvas,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            addView(input, FrameLayout.LayoutParams(1, 1))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (active) cs.primary else cs.surfaceContainerHigh,
        modifier = Modifier.padding(end = 6.dp, top = 2.dp, bottom = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) cs.onPrimary else cs.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Owns the measured grid + selection overlay for one canvas. */
private class TerminalCtl(
    private val session: TerminalSession,
    private val canvas: TerminalCanvasView,
) {
    var baseFrame: TerminalFrame? = null
    private var cols = 0
    private var rows = 0
    val selection = GridSelection()

    fun setFrame(frame: TerminalFrame) {
        baseFrame = frame
        // Fresh output invalidates an active selection (t3 parity).
        if (canvas.hasActiveSelection()) canvas.resetSelectionState()
        canvas.setFrame(frame)
    }

    fun emitResize() {
        if (canvas.width <= 0 || canvas.height <= 0) return
        val c = (canvas.usableWidth() / canvas.cellWidthPx).toInt().coerceIn(2, 400)
        val r = (canvas.usableHeight() / canvas.cellHeightPx).toInt().coerceIn(2, 200)
        if (c != cols || r != rows) {
            cols = c
            rows = r
            if (!opened) {
                opened = true
                session.open(c, r)
            } else {
                session.sendResize(c, r)
            }
        }
    }

    private var opened = false

    /** Client-side word/range selection over the decoded grid. */
    inner class GridSelection : TerminalSelectionDelegate {
        private var anchor: Pair<Int, Int>? = null
        private var extent: Pair<Int, Int>? = null

        private fun ordered(): Pair<Int, Int>? {
            val f = baseFrame ?: return null
            val a = anchor ?: return null
            val e = extent ?: return null
            val ai = (a.second * f.cols + a.first).coerceIn(0, f.cols * f.rows - 1)
            val ei = (e.second * f.cols + e.first).coerceIn(0, f.cols * f.rows - 1)
            return if (ai <= ei) ai to ei else ei to ai
        }

        private fun render() {
            val f = baseFrame ?: return
            val range = ordered()
            if (range == null) {
                canvas.setFrame(f)
                return
            }
            val flags = f.cellFlags.copyOf()
            for (i in range.first..range.second) {
                flags[i] = flags[i] or TerminalCanvasView.FLAG_SELECTED
            }
            canvas.setFrame(f.copy(cellFlags = flags))
        }

        override fun selectWordAt(col: Int, row: Int): Boolean {
            val f = baseFrame ?: return false
            if (row !in 0 until f.rows || col !in 0 until f.cols) return false
            val line = buildString(f.cols) {
                for (c in 0 until f.cols) {
                    append(f.cellText[row * f.cols + c].ifEmpty { " " })
                }
            }
            if (line[col].isWhitespace()) return false
            var s = col
            while (s > 0 && !line[s - 1].isWhitespace()) s--
            var e = col
            while (e < f.cols - 1 && !line[e + 1].isWhitespace()) e++
            anchor = s to row
            extent = e to row
            render()
            return true
        }

        override fun extendSelection(anchorCol: Int, anchorRow: Int, col: Int, row: Int) {
            anchor = anchorCol to anchorRow
            extent = col to row
            render()
        }

        override fun selectAll(): Boolean {
            val f = baseFrame ?: return false
            anchor = 0 to 0
            extent = (f.cols - 1) to (f.rows - 1)
            render()
            return true
        }

        override fun clearSelection() {
            anchor = null
            extent = null
            baseFrame?.let(canvas::setFrame)
        }

        override fun selectionText(): String? {
            val f = baseFrame ?: return null
            val (first, last) = ordered() ?: return null
            val sRow = first / f.cols
            val eRow = last / f.cols
            val sCol = first % f.cols
            val eCol = last % f.cols
            val sb = StringBuilder()
            for (r in sRow..eRow) {
                val from = if (r == sRow) sCol else 0
                val to = if (r == eRow) eCol else f.cols - 1
                for (c in from..to) {
                    sb.append(f.cellText[r * f.cols + c].ifEmpty { " " })
                }
                if (r < eRow) {
                    while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                    sb.append('\n')
                }
            }
            return sb.toString().trimEnd()
        }
    }
}

/** Port of T3TerminalView.configureInputView (MIT): the invisible EditText
 *  forwards commitText, IME deletes and hardware keys into the socket.
 *  [send] handles typed text (Ctrl chip may apply); [sendRaw] sends bytes
 *  verbatim — deletes, Enter, Ctrl+letter and nav sequences never consume
 *  an armed Ctrl. */
private fun configureTerminalInput(
    input: EditText,
    sentText: TerminalSentText,
    send: (String) -> Unit,
    sendRaw: (String) -> Unit,
) {
    input.setSingleLine(true)
    input.setTextColor(GfxColor.TRANSPARENT)
    input.setHintTextColor(GfxColor.TRANSPARENT)
    input.setBackgroundColor(GfxColor.TRANSPARENT)
    input.typeface = Typeface.MONOSPACE
    input.textSize = 13f
    input.alpha = 0.01f
    input.isFocusableInTouchMode = true
    input.imeOptions = EditorInfo.IME_ACTION_SEND or
        EditorInfo.IME_FLAG_NO_EXTRACT_UI or
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    input.inputType = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    input.setPadding(0, 0, 0, 0)
    input.setOnEditorActionListener(
        TerminalEnterListener({ false }) {
            // Enter must send CR: raw-mode TUIs treat LF as Ctrl+J.
            sendRaw("\r")
        },
    )
    val navigationKeys = TerminalNavigationKeyListener { sendRaw(it) }
    input.setOnKeyListener { _, keyCode, event ->
        if (navigationKeys.onKey(input, keyCode, event)) {
            true
        } else if (event.action != KeyEvent.ACTION_DOWN) {
            false
        } else {
            when {
                keyCode == KeyEvent.KEYCODE_DEL -> {
                    sentText.removeCodePoints(1)
                    sendRaw(DEL.toString())
                    true
                }
                event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                    sendRaw((keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString())
                    true
                }
                else -> false
            }
        }
    }
    input.addTextChangedListener(
        object : TextWatcher {
            private var clearing = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (clearing || s == null || count <= 0) return
                val end = (start + count).coerceAtMost(s.length)
                if (start < end) {
                    val insertedText = s.subSequence(start, end).toString()
                    sentText.append(insertedText)
                    send(insertedText)
                }
            }

            override fun afterTextChanged(editable: Editable?) {
                if (clearing || editable.isNullOrEmpty()) return
                clearing = true
                editable.clear()
                clearing = false
            }
        },
    )
}
