package com.devingo.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.Touch
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.view.ViewCompat
import kotlin.math.ceil
import kotlin.math.min
import androidx.compose.ui.graphics.Path as ComposePath

/**
 * N6.2: port of t3code's t3-composer-editor (SelectionAwareEditText +
 * ComposerChipSpan + T3ContextChip + T3ChipIcons, MIT-licensed) — inline
 * `@{kind}:{ref}` context chips drawn as ReplacementSpans, chip tap
 * hit-testing, atomic backspace chip deletion, an ellipsized placeholder,
 * and image paste from clipboard/context-menu/commitContent/drag&drop.
 * Wire text stays plain — chips are presentation only.
 *
 * Dropped from the source port: the RN/Expo controlled-document bridge
 * (Compose drives text+tokens directly), t3's context clipboard protocol,
 * the large-paste text folding, readOnly and font-family plumbing, and the
 * hardware-Enter submit policy (ImeAction.Send covers it here).
 */

// ---- chip drawing (T3ContextChip) --------------------------------------------

private class ContextChip(
    label: String,
    private val icon: Bitmap?,
    private val accent: Int,
    fontSize: Float,
    maximumWidth: Float,
    density: Float,
) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSize
        // Matches the source port's platform-medium fallback.
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = accent
    }
    private val em = fontSize
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val padding = density * 5
    private val iconSlot = if (icon == null) 0f else em * 1.25f
    val width = ceil(
        min(
            maximumWidth.coerceAtLeast(em * 3),
            paint.measureText(label) + iconSlot + padding * 2,
        ),
    )
    val height = ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent)
    private val text = TextUtils.ellipsize(
        label,
        paint,
        (width - iconSlot - padding * 2).coerceAtLeast(0f),
        TextUtils.TruncateAt.MIDDLE,
    ).toString()

    fun draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        icon?.let {
            val top = (height - em) / 2
            canvas.drawBitmap(it, null, RectF(padding, top, padding + em, top + em), iconPaint)
        }
        canvas.drawText(text, padding + iconSlot, -paint.fontMetrics.ascent, paint)
        canvas.restore()
    }
}

private class ComposerChipSpan(
    /** Exact source text the span covers — validity check on edit. */
    val source: String,
    private val chip: ContextChip,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int {
        fontMetrics?.let {
            val base = paint.fontMetricsInt
            it.top = base.top
            it.ascent = base.ascent
            it.descent = base.descent
            it.bottom = base.bottom
        }
        // toInt() truncates; a fractional pixel would clip the chip's right edge.
        return ceil(chip.width).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val metrics = paint.fontMetrics
        chip.draw(canvas, x, y + (metrics.ascent + metrics.descent - chip.height) / 2)
    }
}

// ---- chip icons (T3ChipIcons → HugeIcons rasterizer) --------------------------

/**
 * t3 loads bundled PNGs; DevinGo rasterizes the same HugeIcons the app uses
 * everywhere else — tinted at rasterize time, cached by name+color+size.
 */
private object ChipIcons {
    private val cache = HashMap<String, Bitmap>()

    fun bitmap(vector: ImageVector, color: Int, sizePx: Int): Bitmap =
        cache.getOrPut("${vector.name}:$color:$sizePx") {
            rasterize(vector, color, sizePx)
        }

    private fun rasterize(vector: ImageVector, color: Int, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(
            sizePx / vector.viewportWidth,
            sizePx / vector.viewportHeight,
        )
        vector.root.forEach { drawNode(it, canvas, color) }
        return bmp
    }

    private fun drawNode(node: VectorNode, canvas: Canvas, color: Int) {
        when (node) {
            is VectorPath -> {
                val path = PathParser().addPathNodes(node.pathData)
                    .toPath(ComposePath()).asAndroidPath()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                if (node.fill != null) {
                    paint.style = Paint.Style.FILL
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        android.graphics.Path.FillType.EVEN_ODD
                    } else {
                        android.graphics.Path.FillType.WINDING
                    }
                    canvas.drawPath(path, paint)
                }
                if (node.stroke != null) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = node.strokeLineWidth
                    paint.strokeCap = when (node.strokeLineCap) {
                        StrokeCap.Round -> Paint.Cap.ROUND
                        StrokeCap.Square -> Paint.Cap.SQUARE
                        else -> Paint.Cap.BUTT
                    }
                    paint.strokeJoin = when (node.strokeLineJoin) {
                        StrokeJoin.Round -> Paint.Join.ROUND
                        StrokeJoin.Bevel -> Paint.Join.BEVEL
                        else -> Paint.Join.MITER
                    }
                    paint.strokeMiter = node.strokeLineMiter
                    canvas.drawPath(path, paint)
                }
            }
            is VectorGroup -> {
                canvas.save()
                val m = Matrix()
                m.setTranslate(node.translationX, node.translationY)
                m.postRotate(node.rotation, node.pivotX, node.pivotY)
                m.postScale(node.scaleX, node.scaleY, node.pivotX, node.pivotY)
                canvas.concat(m)
                if (node.clipPathData.isNotEmpty()) {
                    canvas.clipPath(
                        PathParser().addPathNodes(node.clipPathData)
                            .toPath(ComposePath()).asAndroidPath(),
                    )
                }
                node.forEach { drawNode(it, canvas, color) }
                canvas.restore()
            }
        }
    }
}

// ---- the editor (SelectionAwareEditText) --------------------------------------

/** Mirror of SessionStore.MAX_DRAFT_CHARS — hard ceiling at the widget too. */
private const val MAX_INPUT_CHARS = 64_000

internal class ComposerEditText(context: Context) : EditText(context) {
    var textListener: ((String) -> Unit)? = null
    var selectionListener: ((Int, Int) -> Unit)? = null
    var focusListener: ((Boolean) -> Unit)? = null
    var submitListener: (() -> Unit)? = null
    var chipClickListener: ((ChipToken) -> Unit)? = null
    var pasteImagesListener: ((List<Uri>) -> Unit)? = null

    /** Set while a controlled setText runs — its events must not echo back. */
    private var applyingNativeValue = false
    private var chipTokens: List<ChipToken> = emptyList()
    private var touchDownScrollX = 0
    private var touchDownScrollY = 0
    private var touchDownCaret = -1

    /** Compact pill (single-line, centered) vs expanded (3–6 lines). */
    var singleLineMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minLines = 1
                maxLines = 1
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                minLines = 3
                maxLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            // A live inputType swap needs an IME restart to take effect.
            if (hasFocus()) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.restartInput(this)
            }
        }

    /** TextView hints don't ellipsize — truncate the hint to the measured width. */
    var placeholderText = ""
        set(value) {
            field = value
            applyPlaceholder()
        }

    fun applyPlaceholder(
        availableWidth: Int = width - compoundPaddingLeft - compoundPaddingRight,
    ) {
        val next = if (availableWidth > 0) {
            TextUtils.ellipsize(
                placeholderText, paint, availableWidth.toFloat(), TextUtils.TruncateAt.END,
            )
        } else {
            placeholderText
        }
        if (hint?.toString() != next.toString()) hint = next
    }

    /** Per-layer insets: compact hugs the pill, expanded breathes like the old TextField. */
    fun applyContentPadding(horizontalPx: Int, verticalPx: Int) {
        if (paddingLeft != horizontalPx || paddingRight != horizontalPx ||
            paddingTop != verticalPx || paddingBottom != verticalPx
        ) {
            setPadding(horizontalPx, verticalPx, horizontalPx, verticalPx)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        minLines = 1
        setPadding(0, 0, 0, 0)
        filters = arrayOf(InputFilter.LengthFilter(MAX_INPUT_CHARS))
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, actionId, event ->
            val isEnter = (
                event?.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                ) && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || (singleLineMode && isEnter)) {
                submitListener?.invoke()
                true
            } else {
                false
            }
        }
        setOnFocusChangeListener { _, hasFocus -> focusListener?.invoke(hasFocus) }
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?, start: Int, count: Int, after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?, start: Int, before: Int, count: Int,
                ) = Unit

                override fun afterTextChanged(editable: Editable) {
                    // An edit inside a chip range invalidates it now — the
                    // controlled token list catches up a frame later.
                    editable.getSpans(0, editable.length, ComposerChipSpan::class.java)
                        .forEach { span ->
                            val s = editable.getSpanStart(span)
                            val e = editable.getSpanEnd(span)
                            if (s < 0 || e > editable.length ||
                                editable.substring(s, e) != span.source
                            ) {
                                editable.removeSpan(span)
                            }
                        }
                    if (!applyingNativeValue) {
                        textListener?.invoke(editable.toString())
                    }
                }
            },
        )
        val contextGestures = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent) = true
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    val offset = getOffsetForPosition(event.x, event.y)
                    val token = chipTokens.firstOrNull {
                        offset in it.start..it.end &&
                            it.end <= length() &&
                            chipContains(it, event.x, event.y)
                    } ?: return false
                    if (token.end <= length() &&
                        text.substring(token.start, token.end) == token.source
                    ) {
                        chipClickListener?.invoke(token)
                    }
                    return false
                }
            },
        )
        setOnTouchListener { _, event ->
            contextGestures.onTouchEvent(event)
            false
        }
        // Chip widths cap at the view's width — re-ellipsize on a width change.
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) applyTokenSpans()
        }
        // IME-committed image content (GBoard paste, sticker → image).
        ViewCompat.setOnReceiveContentListener(this, arrayOf("image/*")) { _, payload ->
            val uris = buildList {
                for (i in 0 until payload.clip.itemCount) {
                    payload.clip.getItemAt(i).uri?.let(::add)
                }
            }
            if (uris.isEmpty() || pasteImagesListener == null) {
                payload
            } else {
                pasteImagesListener?.invoke(uris)
                null
            }
        }
    }

    // Keeps autofill services such as SMS verification codes out of the draft.
    override fun getAutofillType(): Int = View.AUTOFILL_TYPE_NONE

    // ---- controlled text + tokens --------------------------------------------

    /** Compose → view: external draft changes land here; typing does not. */
    fun setControlledText(value: String) {
        if (text?.toString() == value) return
        applyingNativeValue = true
        try {
            setText(value)
            applyTokenSpans()
            // Parity with the old TextFieldValue path: external writes park the
            // caret at the end.
            setSelection(value.length)
        } finally {
            applyingNativeValue = false
        }
    }

    fun setChipTokens(tokens: List<ChipToken>) {
        if (tokens == chipTokens) return
        chipTokens = tokens
        applyTokenSpans()
    }

    fun setTextSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        applyTokenSpans()
    }

    fun applyThemeColors(textColor: Int, hintColor: Int, accent: Int) {
        setTextColor(textColor)
        setHintTextColor(hintColor)
        highlightColor = (accent and 0x00FFFFFF) or (0x52 shl 24)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(accent) }
            textSelectHandle?.mutate()?.setTint(accent)
            textSelectHandleLeft?.mutate()?.setTint(accent)
            textSelectHandleRight?.mutate()?.setTint(accent)
        }
    }

    private fun applyTokenSpans() {
        val editable = text ?: return
        editable.getSpans(0, editable.length, ComposerChipSpan::class.java)
            .forEach(editable::removeSpan)
        val maxWidth = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        for (t in chipTokens) {
            if (t.start < 0 || t.end <= t.start || t.end > editable.length) continue
            if (editable.substring(t.start, t.end) != t.source) continue
            val (accent, icon) = chipPresentation(t.kind)
            editable.setSpan(
                ComposerChipSpan(
                    source = t.source,
                    chip = ContextChip(
                        label = t.label,
                        icon = ChipIcons.bitmap(
                            icon, accent, ceil(textSize).toInt(),
                        ),
                        accent = accent,
                        fontSize = textSize,
                        maximumWidth = maxWidth,
                        density = resources.displayMetrics.density,
                    ),
                ),
                t.start,
                t.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        invalidate()
    }

    // A chip is one replacement span, so a tap on its right half reports the
    // offset at its end, and a tap below the last line clamps to it; only the
    // chip's drawn bounds count.
    private fun chipContains(token: ChipToken, viewX: Float, viewY: Float): Boolean {
        val layout = layout ?: return false
        val x = viewX - totalPaddingLeft + scrollX
        val y = viewY - totalPaddingTop + scrollY
        val line = layout.getLineForOffset(token.start)
        val left = layout.getPrimaryHorizontal(token.start)
        val right = if (layout.getLineForOffset(token.end) == line) {
            layout.getPrimaryHorizontal(token.end)
        } else {
            layout.getLineRight(line)
        }
        return y >= layout.getLineTop(line) && y <= layout.getLineBottom(line) &&
            x >= minOf(left, right) && x <= maxOf(left, right)
    }

    // ---- atomic chip deletion -------------------------------------------------

    private fun deleteChip(backwards: Boolean): Boolean {
        val content = text
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (content == null || start < 0 || end < 0) return false
        val from = if (start == end && backwards) (start - 1).coerceAtLeast(0) else start
        val to = if (start == end && !backwards) {
            (end + 1).coerceAtMost(content.length)
        } else {
            end
        }
        val spans = content.getSpans(from, to, ComposerChipSpan::class.java).filter {
            content.getSpanStart(it) < to && content.getSpanEnd(it) > from
        }
        if (spans.isNotEmpty()) {
            val first = minOf(from, spans.minOf { content.getSpanStart(it) })
            val last = maxOf(to, spans.maxOf { content.getSpanEnd(it) })
            content.delete(first, last)
            setSelection(first)
        }
        return spans.isNotEmpty()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> deleteChip(true)
            KeyEvent.KEYCODE_FORWARD_DEL -> deleteChip(false)
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun deleteAdjacentChip(beforeLength: Int, afterLength: Int): Boolean = when {
        beforeLength == 1 && afterLength == 0 -> deleteChip(true)
        beforeLength == 0 && afterLength == 1 -> deleteChip(false)
        else -> false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean = deleteAdjacentChip(beforeLength, afterLength) ||
                super.deleteSurroundingText(beforeLength, afterLength)

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean = deleteAdjacentChip(beforeLength, afterLength) ||
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }
    }

    // ---- touch / measure -------------------------------------------------------

    // Releasing a drag that scrolled the text makes ArrowKeyMovementMethod pull
    // the caret into view. Finish Touch's drag and cancel the release instead,
    // so the caret stays where it was.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDownScrollX = scrollX
            touchDownScrollY = scrollY
            touchDownCaret = if (selectionStart == selectionEnd) selectionStart else -1
        }
        val releasesScroll =
            event.actionMasked == MotionEvent.ACTION_UP &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                touchDownCaret >= 0 &&
                selectionStart == touchDownCaret &&
                selectionEnd == touchDownCaret &&
                (scrollX != touchDownScrollX || scrollY != touchDownScrollY)
        if (!releasesScroll) return super.onTouchEvent(event)
        Touch.onTouchEvent(this, text, event)
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        return try {
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            applyPlaceholder(
                MeasureSpec.getSize(widthMeasureSpec) -
                    compoundPaddingLeft - compoundPaddingRight,
            )
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!applyingNativeValue) selectionListener?.invoke(selStart, selEnd)
    }

    // ---- image paste ------------------------------------------------------------

    override fun onTextContextMenuItem(id: Int): Boolean {
        // Only plain "paste" intercepts image clips — "paste as plain text"
        // must keep producing text (t3 routes it to pasteInterceptedText).
        if (id == android.R.id.paste) {
            val uris = clipboardImageUris()
            if (uris.isNotEmpty()) {
                pasteImagesListener?.invoke(uris)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    private fun clipboardImageUris(): List<Uri> {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip ?: return emptyList()
        return buildList {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uri ->
                    if (context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                        add(uri)
                    }
                }
            }
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        if (!isEnabled) return false
        // TextView only claims text/plain drags at DRAG_STARTED — claim
        // image drags ourselves or ACTION_DROP is never delivered.
        if (event.clipDescription?.hasMimeType("image/*") == true) {
            return if (event.action == DragEvent.ACTION_DROP) {
                dropImages(event)
            } else {
                true
            }
        }
        return super.onDragEvent(event)
    }

    private fun dropImages(event: DragEvent): Boolean {
        val clip = event.clipData
        val activity = findActivity(context)
        val listener = pasteImagesListener
        if (clip == null || activity == null || listener == null) return false
        val imageUris = try {
            // Keep the activity-scoped grant while the app reads the drop.
            activity.requestDragAndDropPermissions(event)
            (0 until clip.itemCount).mapNotNull { index ->
                clip.getItemAt(index).uri?.takeIf { uri ->
                    context.contentResolver.getType(uri)?.startsWith("image/") == true
                }
            }
        } catch (_: SecurityException) {
            emptyList()
        }
        return if (imageUris.size == clip.itemCount && imageUris.isNotEmpty()) {
            listener(imageUris)
            true
        } else {
            false
        }
    }

    private fun findActivity(ctx: Context): Activity? = when (ctx) {
        is Activity -> ctx
        is ContextWrapper -> findActivity(ctx.baseContext)
        else -> null
    }
}
