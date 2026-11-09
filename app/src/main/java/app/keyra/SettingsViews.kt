package app.keyra

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

internal fun Context.settingsList() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    dividerDrawable = GradientDrawable().apply {
        setColor(getColor(R.color.settings_divider))
        setSize(1, dp(1))
    }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

internal fun Context.settingsCard(body: LinearLayout, title: String, topPadding: Int = 24): LinearLayout {
    body.addView(TextView(this).apply {
        text = title; textSize = 13f
        setTextColor(getColor(R.color.accent))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(8), dp(topPadding), dp(8), dp(12))
    })
    return settingsList().apply {
        setBackgroundResource(R.drawable.bg_settings_card)
        clipToOutline = true
        body.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}

internal fun Context.settingsText(title: String, summary: String?, summaryColor: Int = R.color.settings_secondary): CharSequence {
    if (summary.isNullOrBlank()) return title
    return SpannableString("$title\n$summary").apply {
        val start = title.length + 1
        setSpan(AbsoluteSizeSpan(13, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColor(summaryColor)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun Context.settingsAction(title: String, summary: String? = null, action: () -> Unit) = Button(this).apply {
    isAllCaps = false
    setOnClickListener { action() }
    backgroundTintList = null; stateListAnimator = null; elevation = 0f
    val highlight = android.util.TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, highlight, true)
    setBackgroundResource(highlight.resourceId)
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    textSize = 16f; setTextColor(getColor(R.color.settings_text))
    text = settingsText(title, summary)
    setTypeface(typeface, Typeface.NORMAL)
    minWidth = 0; minimumWidth = 0; minHeight = dp(if (summary.isNullOrBlank()) 60 else 76)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    val chevron = AppCompatResources.getDrawable(context, R.drawable.ic_settings_chevron)!!
    chevron.setBounds(0, 0, dp(20), dp(20))
    setCompoundDrawablesRelative(null, null, chevron, null)
    compoundDrawablePadding = dp(12)
    layoutParams = LinearLayout.LayoutParams(-1, -2)
}
