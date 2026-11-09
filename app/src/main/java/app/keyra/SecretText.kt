package app.keyra

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

object SecretText {
    fun colored(context: Context, text: String): CharSequence = SpannableString(text).apply { color(context, this) }

    fun clearColors(text: Spannable) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
    }

    fun color(context: Context, text: Spannable) {
        clearColors(text)
        val letters = ContextCompat.getColor(context, R.color.secret_letters)
        val digits = ContextCompat.getColor(context, R.color.secret_digits)
        val other = ContextCompat.getColor(context, R.color.secret_other)
        val value = text.toString()
        fun colorAt(offset: Int) = when {
            Character.isLetter(value.codePointAt(offset)) -> letters
            Character.isDigit(value.codePointAt(offset)) -> digits
            else -> other
        }
        var start = 0
        while (start < value.length) {
            val color = colorAt(start)
            var end = start + Character.charCount(value.codePointAt(start))
            while (end < value.length && colorAt(end) == color) end += Character.charCount(value.codePointAt(end))
            text.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            start = end
        }
    }
}
