package app.keyra

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

// Constructed in code with required data and callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class EntryTagsEditorView(context: Context, private val draft: EntryDraft) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val tags = TagFlowLayout(context)
    private val input = EditText(context).apply {
        hint = L10n.text(context, R.string.ui_new_tag_1); isSingleLine = true; isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setText(draft.pendingTagText)
    }
    init {
        orientation = VERTICAL; isSaveEnabled = false
        setPadding(dp(16), dp(4), dp(16), dp(16))
        addView(TextView(context).apply {
            text = L10n.text(context, R.string.ui_tags_2); textSize = 12f; setPadding(0, dp(12), 0, dp(4))
            val colors = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
            try { setTextColor(colors.getColor(0, currentTextColor)) } finally { colors.recycle() }
        })
        addView(tags)
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(input, LayoutParams(0, -2, 1f))
        row.addView(icon(R.drawable.ic_add, L10n.text(context, R.string.ui_add_tag_3)) { add() }, LayoutParams(dp(48), dp(48)))
        addView(row)
        input.doAfterTextChanged { draft.updateTagInput(it.toString()) }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { add(); true } else false
        }
        showTags()
    }
    private fun icon(resource: Int, label: String, clicked: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource); imageTintList = input.textColors
        val selectable = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
        setBackgroundResource(selectable.resourceId)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = label; tooltipText = label
        setOnClickListener { clicked() }
    }
    private fun add() {
        draft.addPendingTags(); input.text.clear(); showTags()
    }
    private fun showTags() {
        tags.show(draft.tags) { tag -> draft.removeTag(tag); showTags() }
    }
    fun setEditingEnabled(enabled: Boolean) {
        fun apply(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
        }
        apply(this)
    }
}
