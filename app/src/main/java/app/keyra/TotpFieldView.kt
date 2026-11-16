package app.keyra

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.keyra.core.Totp
import app.keyra.databinding.RowTotpFieldBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@android.annotation.SuppressLint("ViewConstructor")
class TotpFieldView(context: Context, private val settings: Totp?, copy: (String) -> Unit) : FrameLayout(context) {
    private val binding = RowTotpFieldBinding.inflate(LayoutInflater.from(context), this, true)
    private var ticker: Job? = null

    init {
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        binding.fieldValue.typeface = Typeface.MONOSPACE
        binding.fieldValue.textDirection = View.TEXT_DIRECTION_LTR
        binding.copyField.contentDescription = context.getString(R.string.copy_field, context.getString(R.string.totp_label))
        binding.copyField.tooltipText = binding.copyField.contentDescription
        binding.copyField.isEnabled = false
        binding.copyField.setOnClickListener {
            // Compute at the time of the click, including clicks on a period boundary.
            settings?.let { copy(it.codeAt(System.currentTimeMillis()).value) }
        }
        if (settings == null) {
            binding.fieldValue.setText(R.string.totp_invalid)
            binding.remainingTime.visibility = View.GONE
            binding.remainingProgress.visibility = View.GONE
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (settings == null) return
        val owner = findViewTreeLifecycleOwner() ?: return
        ticker = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    binding.copyField.isEnabled = true
                    while (isActive) {
                        val code = settings.codeAt(System.currentTimeMillis())
                        if (binding.fieldValue.text.toString() != code.value) binding.fieldValue.text = code.value
                        binding.remainingTime.text = context.getString(R.string.totp_remaining, code.remainingSeconds)
                        binding.remainingProgress.progress = (code.remainingMillis * 1000 / code.periodMillis).toInt()
                        delay(minOf(100L, code.remainingMillis))
                    }
                } finally {
                    clearCode()
                }
            }
        }
    }

    private fun clearCode() {
        binding.fieldValue.text = ""
        binding.remainingTime.text = ""
        binding.remainingProgress.progress = 0
        binding.copyField.isEnabled = false
    }

    override fun onDetachedFromWindow() {
        ticker?.cancel()
        ticker = null
        if (settings != null) clearCode()
        super.onDetachedFromWindow()
    }
}
