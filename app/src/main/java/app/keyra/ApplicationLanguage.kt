package app.keyra

import androidx.core.content.edit

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** The first choice follows the device; unsupported device languages use English. */
object ApplicationLanguage {
    private const val PREFERENCE = "appLanguage"
    private val tags = listOf("system", "en", "ru", "de", "fr", "es", "it")

    fun selectedIndex(context: Context): Int = tags.indexOf(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREFERENCE, "system")
    ).coerceAtLeast(0)

    fun languageTag(context: Context): String {
        val selected = tags[selectedIndex(context)]
        if (selected != "system") return selected
        val system = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).systemLocales
        } else Resources.getSystem().configuration.locales
        return system.get(0)?.language?.takeIf { it in tags && it != "system" } ?: "en"
    }

    fun applySaved(context: Context) {
        val tag = languageTag(context)
        Locale.setDefault(Locale.forLanguageTag(tag))
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val locales = LocaleList.forLanguageTags(tag)
            if (manager.applicationLocales != locales) manager.applicationLocales = locales
            return
        }
        val locales = LocaleListCompat.forLanguageTags(tag)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun select(context: Context, index: Int) {
        require(index in tags.indices)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { putString(PREFERENCE, tags[index]) }
        applySaved(context)
    }

    // Application, worker and service contexts also need the app's selected locale.
    fun localizedContext(context: Context): Context {
        val tag = languageTag(context)
        if (context.resources.configuration.locales.get(0)?.language == tag) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }
}

object L10n {
    fun text(context: Context, @StringRes id: Int, vararg arguments: Any?): String =
        ApplicationLanguage.localizedContext(context).getString(id, *arguments)
}
