package app.keyra

import androidx.core.net.toUri

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import app.keemobile.kotpass.models.EntryValue
import app.keyra.databinding.RowEntryFieldBinding

/** Compact field row. Revealed values stay in the view only and are never saved as UI state. */
// Constructed in code with required data and callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class EntryFieldView(
    context: Context,
    private val fieldName: String,
    private val value: EntryValue,
    private val isProtected: Boolean,
    copy: (String) -> Unit,
    private val isUrl: Boolean = false
) : FrameLayout(context) {
    private val binding = RowEntryFieldBinding.inflate(LayoutInflater.from(context), this, true)
    private var revealed = false
    private val autoHide = Runnable { hideValue() }

    init {
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        binding.fieldLabel.text = fieldName
        binding.copyField.contentDescription = context.getString(R.string.copy_field, fieldName)
        binding.copyField.tooltipText = binding.copyField.contentDescription
        binding.copyField.setOnClickListener { copy(value.content) }
        if (isProtected) {
            binding.fieldValue.typeface = Typeface.MONOSPACE
            binding.fieldValue.textDirection = View.TEXT_DIRECTION_LTR
            binding.toggleField.visibility = View.VISIBLE
            binding.toggleField.setOnClickListener { if (revealed) hideValue() else showValue() }
            hideValue()
        } else {
            displayValue(value.content.ifEmpty { context.getString(R.string.empty_value) })
        }
    }

    private fun displayValue(text: CharSequence) {
        val raw = value.content.trim()
        val target = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { target.toUri() }.getOrNull()
        val link = isUrl && raw.isNotEmpty() && uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()
        binding.fieldValue.apply {
            this.text = if (link) SpannableString(text).apply {
                setSpan(URLSpan(target), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else text
            movementMethod = if (link) LinkMovementMethod.getInstance() else null
            linksClickable = link
        }
    }

    private fun showValue() {
        revealed = true
        displayValue(SecretText.colored(context, value.content.ifEmpty { context.getString(R.string.empty_value) }))
        binding.fieldValue.contentDescription = null
        binding.toggleField.setImageResource(R.drawable.ic_visibility_off)
        binding.toggleField.contentDescription = context.getString(R.string.hide_field, fieldName)
        binding.toggleField.tooltipText = binding.toggleField.contentDescription
        removeCallbacks(autoHide)
        postDelayed(autoHide, 15_000)
    }

    fun hideValue() {
        if (!isProtected) return
        removeCallbacks(autoHide)
        revealed = false
        binding.fieldValue.movementMethod = null
        binding.fieldValue.linksClickable = false
        binding.fieldValue.setText(R.string.hidden_value)
        binding.fieldValue.contentDescription = context.getString(R.string.hidden_field, fieldName)
        binding.toggleField.setImageResource(R.drawable.ic_visibility)
        binding.toggleField.contentDescription = context.getString(R.string.show_field, fieldName)
        binding.toggleField.tooltipText = binding.toggleField.contentDescription
    }

    override fun onDetachedFromWindow() {
        hideValue()
        super.onDetachedFromWindow()
    }

}
