package app.keyra

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Shared, localised introduction for entry points into the vault. */
internal fun Context.addBrandIntro(body: LinearLayout, caption: Int) {
    body.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_keyra)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
    body.addView(TextView(this).apply {
        setText(R.string.app_name); textSize = 32f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(getColor(R.color.settings_text))
    }, LinearLayout.LayoutParams(-1, -2))
    body.addView(TextView(this).apply {
        text = L10n.text(this@addBrandIntro, caption); textSize = 16f; gravity = Gravity.CENTER
        setTextColor(getColor(R.color.settings_secondary))
        setPadding(0, dp(8), 0, dp(28))
    }, LinearLayout.LayoutParams(-1, -2))
}

