package app.keyra

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.widget.doAfterTextChanged
import app.keemobile.kotpass.models.EntryValue
import app.keyra.databinding.RowEditFieldBinding

// Constructed in code with required data and callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class EditableEntryFieldView(
    context: Context,
    key: String,
    initial: EntryValue,
    changed: (String) -> Unit,
    generate: (() -> Unit)? = null
) : FrameLayout(context) {
    private val binding = RowEditFieldBinding.inflate(LayoutInflater.from(context), this, true)
    private val secret = key == "Password" || initial is EntryValue.Encrypted
    private val name = fieldLabel(context, key)
    private var revealed = false
    private var notifying = true
    private val autoHide = Runnable { hideValue() }

    init {
        isSaveEnabled = false; isSaveFromParentEnabled = false
        binding.fieldLabel.text = name
        val multiline = key == "Notes" || key !in standardEntryFields
        binding.fieldInput.apply {
            hint = name
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else if (key == "URL") InputType.TYPE_TEXT_VARIATION_URI else 0) or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            setSingleLine(!multiline)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or (if (multiline) EditorInfo.IME_ACTION_NONE else EditorInfo.IME_ACTION_NEXT)
            typeface = if (secret) Typeface.MONOSPACE else Typeface.DEFAULT
            setText(initial.content)
            if (secret) textDirection = View.TEXT_DIRECTION_LTR
        }
        binding.fieldInput.doAfterTextChanged { editable ->
            if (notifying && editable != null) {
                changed(editable.toString())
                if (revealed) SecretText.color(context, editable)
            }
        }
        if (secret) {
            binding.toggleField.visibility = View.VISIBLE
            binding.toggleField.setOnClickListener { if (revealed) hideValue() else showValue() }
            hideValue()
        }
        if (generate != null) {
            binding.generateField.visibility = View.VISIBLE
            binding.generateField.tooltipText = context.getString(R.string.password_generator)
            binding.generateField.setOnClickListener { hideValue(); generate() }
        }
    }

    private fun showValue() {
        revealed = true
        val selection = binding.fieldInput.selectionStart
        binding.fieldInput.transformationMethod = null
        binding.fieldInput.text?.let { SecretText.color(context, it) }
        if (selection >= 0) binding.fieldInput.setSelection(selection.coerceAtMost(binding.fieldInput.length()))
        binding.toggleField.setImageResource(R.drawable.ic_visibility_off)
        binding.toggleField.contentDescription = context.getString(R.string.hide_field, name)
        binding.toggleField.tooltipText = binding.toggleField.contentDescription
        removeCallbacks(autoHide); postDelayed(autoHide, 15_000)
    }

    fun hideValue() {
        if (!secret) return
        removeCallbacks(autoHide); revealed = false
        val selection = binding.fieldInput.selectionStart
        binding.fieldInput.text?.let { SecretText.clearColors(it) }
        binding.fieldInput.maskPasswordInput()
        if (selection >= 0) binding.fieldInput.setSelection(selection.coerceAtMost(binding.fieldInput.length()))
        binding.toggleField.setImageResource(R.drawable.ic_visibility)
        binding.toggleField.contentDescription = context.getString(R.string.show_field, name)
        binding.toggleField.tooltipText = binding.toggleField.contentDescription
    }

    fun dispose() {
        notifying = false; hideValue()
        binding.fieldInput.text?.clear()
    }

    fun setEditingEnabled(enabled: Boolean) {
        binding.fieldInput.isEnabled = enabled
        binding.toggleField.isEnabled = enabled
        binding.generateField.isEnabled = enabled
    }

    fun showError(message: String) {
        binding.fieldInput.error = message
        binding.fieldInput.requestFocus()
    }

    override fun onDetachedFromWindow() { hideValue(); super.onDetachedFromWindow() }
}
