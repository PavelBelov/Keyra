package app.keyra

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.ToggleButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged

// Constructed in code with required data and callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class PasswordGeneratorView(
    context: Context,
    private val draft: PasswordGeneratorDraft,
    private val copy: (String) -> Unit,
    private val stateChanged: () -> Unit
) : LinearLayout(context) {
    private val preview = FrameLayout(context)
    private val error = TextView(context)
    private var previewField: EntryFieldView? = null

    init {
        orientation = VERTICAL; isSaveEnabled = false
        addView(preview, LayoutParams(-1, -2))
        error.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.secret_other))
        error.setPadding(0, dp(8), 0, dp(8))
        addView(error)
        addView(TextView(context).apply {
            setText(R.string.password_length); textSize = 13f
            setTextColor(context.getColor(R.color.settings_secondary))
            setPadding(dp(8), dp(24), dp(8), dp(8))
        })
        val length = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(InputFilter.LengthFilter(3)); setText(draft.lengthText)
        }
        addView(length, LayoutParams(-1, -2))
        length.doAfterTextChanged {
            draft.lengthText = it.toString()
            regenerate()
        }
        addView(TextView(context).apply {
            setText(R.string.character_groups); textSize = 13f
            setTextColor(context.getColor(R.color.settings_secondary))
            setPadding(dp(8), dp(24), dp(8), dp(4))
        })
        fun group(row: LinearLayout, label: Int, description: String, enabled: Boolean, change: (Boolean) -> Unit) {
            row.addView(ToggleButton(context).apply {
                textOn = context.getString(label); textOff = textOn; isAllCaps = false
                contentDescription = description
                backgroundTintList = null; stateListAnimator = null
                setBackgroundResource(R.drawable.bg_generator_group)
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(context, R.color.generator_group_text))
                textSize = 17f; minimumWidth = 0; minWidth = 0
                minimumHeight = dp(56)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isChecked = enabled
                setOnCheckedChangeListener { _, selected -> change(selected); regenerate() }
            }, LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        fun row() = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }.also { addView(it, LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        val letters = row()
        group(letters, R.string.characters_lowercase, L10n.text(context, R.string.ui_lowercase_letters_a_z_190), draft.lowercase) { draft.lowercase = it }
        group(letters, R.string.characters_uppercase, L10n.text(context, R.string.ui_uppercase_letters_a_z_191), draft.uppercase) { draft.uppercase = it }
        val other = row()
        group(other, R.string.characters_digits, L10n.text(context, R.string.ui_digits_0_9_192), draft.digits) { draft.digits = it }
        group(other, R.string.characters_symbols, L10n.text(context, R.string.ui_special_characters_193), draft.symbols) { draft.symbols = it }
        if (draft.generated == null) draft.regenerate()
        updatePreview()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun regenerate() { draft.regenerate(); updatePreview() }
    fun hideValue() { previewField?.hideValue() }

    private fun updatePreview() {
        previewField?.hideValue(); preview.removeAllViews()
        previewField = draft.generated?.let { EntryFieldView(context, context.getString(R.string.generated_password), it, true, copy) }
        previewField?.let { preview.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        error.text = when (draft.error) {
            GeneratorError.LENGTH -> context.getString(R.string.generator_length_error)
            GeneratorError.NO_GROUPS -> context.getString(R.string.generator_groups_error)
            GeneratorError.TOO_SHORT -> context.getString(R.string.generator_short_error)
            null -> ""
        }
        error.visibility = if (draft.error == null) View.GONE else View.VISIBLE
        stateChanged()
    }
}
