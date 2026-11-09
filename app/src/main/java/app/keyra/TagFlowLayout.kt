package app.keyra

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone

/** Packs tags in their original order, wrapping at the available width. */
class TagFlowLayout(context: Context) : ViewGroup(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val gap get() = dp(6)

    fun show(tags: List<String>, remove: ((String) -> Unit)? = null) {
        removeAllViews()
        tags.forEach { tag ->
            val chip = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(if (remove == null) 36 else 48)
                setBackgroundResource(R.drawable.bg_entry_tag)
                clipToOutline = true
            }
            chip.addView(TextView(context).apply {
                text = tag; textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.entry_tag_text))
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (remove != null) chip.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.entry_tag_text))
                val highlight = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, highlight, true)
                setBackgroundResource(highlight.resourceId)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                contentDescription = L10n.text(context, R.string.ui_remove_tag_194, tag); tooltipText = contentDescription
                setOnClickListener { remove(tag) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(chip)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var rowWidth = 0
        var rowHeight = 0
        var widest = 0
        var height = paddingTop + paddingBottom
        var state = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val space = if (rowWidth == 0) 0 else gap
            if (rowWidth > 0 && rowWidth + space + child.measuredWidth > available) {
                widest = maxOf(widest, rowWidth)
                height += rowHeight + gap
                rowWidth = 0; rowHeight = 0
            }
            if (rowWidth > 0) rowWidth += gap
            rowWidth += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
            state = combineMeasuredStates(state, child.measuredState)
        }
        widest = maxOf(widest, rowWidth)
        height += rowHeight
        setMeasuredDimension(
            resolveSizeAndState(maxOf(suggestedMinimumWidth, widest + paddingLeft + paddingRight), widthMeasureSpec, state),
            resolveSizeAndState(maxOf(suggestedMinimumHeight, height), heightMeasureSpec, state shl MEASURED_HEIGHT_STATE_SHIFT)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var used = 0
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val space = if (used == 0) 0 else gap
            if (used > 0 && used + space + child.measuredWidth > available) {
                y += rowHeight + gap; used = 0; rowHeight = 0
            }
            if (used > 0) used += gap
            val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) right - left - paddingRight - used - child.measuredWidth else paddingLeft + used
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            used += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
