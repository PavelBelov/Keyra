package app.keyra

import android.graphics.Rect
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.method.TransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.view.Gravity

/** One mask per input: only the last inserted code point is visible for 800 ms. */
private class PasswordInputMask(private val input: EditText) : TransformationMethod, TextWatcher, View.OnAttachStateChangeListener {
    private var start = -1
    private var end = -1
    private var until = 0L
    private val conceal = Runnable { hide() }
    private val layoutChange = object : android.text.style.UpdateLayout {}

    init { input.addTextChangedListener(this); input.addOnAttachStateChangeListener(this) }
    fun hide(redraw: Boolean = true) {
        input.removeCallbacks(conceal)
        start = -1; end = -1; until = 0
        if (redraw) {
            input.text?.let {
                it.setSpan(layoutChange, 0, it.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                it.removeSpan(layoutChange)
            }
            input.requestLayout(); input.invalidate()
        }
    }
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun afterTextChanged(s: Editable?) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        hide(redraw = false)
        if (input.transformationMethod !== this || !input.hasFocus() || s == null || count <= 0) return
        end = (start + count).coerceAtMost(s.length)
        if (end <= start) return
        this.start = end - Character.charCount(Character.codePointBefore(s, end))
        until = SystemClock.uptimeMillis() + 800
        input.postDelayed(conceal, 800)
    }
    override fun getTransformation(source: CharSequence, view: View): CharSequence = object : CharSequence {
        override val length get() = source.length
        override fun get(index: Int): Char {
            require(index in 0 until length)
            return if (index in start until end && SystemClock.uptimeMillis() < until && input.hasFocus()) source[index] else '*'
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex >= 0 && endIndex >= startIndex && endIndex <= length)
            val masked = this
            return buildString { for (i in startIndex until endIndex) append(masked[i]) }
        }
        override fun toString() = subSequence(0, length).toString()
    }
    override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) { if (!focused) hide() }
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) { hide() }
}

fun EditText.maskPasswordInput() {
    val mask = (getTag(R.id.password_input_mask) as? PasswordInputMask) ?: PasswordInputMask(this).also {
        setTag(R.id.password_input_mask, it)
    }
    mask.hide()
    transformationMethod = mask
}

fun EditText.withPasswordVisibilityToggle(): LinearLayout {
    val input = this
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    return LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        // Keep the eye inside the same surface, including focus styling.
        background = input.background
        input.background = null
        setAddStatesFromChildren(true)
        addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_visibility)
            imageTintList = input.textColors
            val selectable = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
            setBackgroundResource(selectable.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = context.getString(R.string.show_field, L10n.text(context, R.string.field_password))
            setOnClickListener {
                val start = input.selectionStart
                val end = input.selectionEnd
                val reveal = input.transformationMethod != null
                if (reveal) input.transformationMethod = null else input.maskPasswordInput()
                if (start >= 0 && end >= 0) input.setSelection(start.coerceAtMost(input.length()), end.coerceAtMost(input.length()))
                setImageResource(if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
                contentDescription = context.getString(if (reveal) R.string.hide_field else R.string.show_field, L10n.text(context, R.string.field_password))
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
    }
}
