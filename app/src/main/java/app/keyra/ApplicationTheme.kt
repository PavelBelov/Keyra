package app.keyra

import androidx.core.content.edit

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ApplicationTheme {
    private const val PREFERENCE = "appTheme"
    private val modes = listOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES
    )
    fun selectedIndex(context: Context): Int {
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(PREFERENCE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        return modes.indexOf(saved).coerceAtLeast(0)
    }
    fun applySaved(context: Context) { AppCompatDelegate.setDefaultNightMode(modes[selectedIndex(context)]) }
    fun select(context: Context, index: Int) {
        val mode = modes[index]
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { putInt(PREFERENCE, mode) }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
