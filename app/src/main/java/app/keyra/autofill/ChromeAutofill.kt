package app.keyra.autofill

import android.content.Context
import android.content.Intent
import android.net.Uri

internal object ChromeAutofill {
    private const val PACKAGE = "com.android.chrome"
    enum class State { ENABLED, DISABLED, UNAVAILABLE, NOT_INSTALLED }

    fun settingsIntent() = Intent(Intent.ACTION_APPLICATION_PREFERENCES)
        .addCategory(Intent.CATEGORY_DEFAULT)
        .addCategory(Intent.CATEGORY_APP_BROWSER)
        .addCategory(Intent.CATEGORY_PREFERENCE)
        .setPackage(PACKAGE)

    /** Chrome's public provider reports its own setting, independently of Android's service. */
    fun state(context: Context): State {
        try { context.packageManager.getPackageInfo(PACKAGE, 0) }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { return State.NOT_INSTALLED }
        return try {
            val uri = Uri.parse("content://$PACKAGE.AutofillThirdPartyModeContentProvider/autofill_third_party_mode")
            context.contentResolver.query(uri, arrayOf("autofill_third_party_state"), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex("autofill_third_party_state")
                if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) State.UNAVAILABLE
                else when (cursor.getInt(index)) {
                    0 -> State.DISABLED
                    1 -> State.ENABLED
                    else -> State.UNAVAILABLE
                }
            } ?: State.UNAVAILABLE
        } catch (_: Exception) { State.UNAVAILABLE }
    }
}
