package app.keyra.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.findEntries
import app.keemobile.kotpass.models.Entry
import app.keyra.L10n
import app.keyra.MainActivity
import app.keyra.R
import app.keyra.VaultIcon
import app.keyra.VaultIcons

internal object AutofillResponses {
    fun websiteUrl(target: FillTarget): String? = runCatching {
        val domain = target.domain ?: return null
        if (target.webScheme !in setOf("http", "https")) return null
        java.net.URI(target.webScheme, null, domain, -1, null, null, null)
            .takeIf { it.host != null }?.toASCIIString()
    }.getOrNull()

    fun trustedBrowser(preferences: SharedPreferences, target: FillTarget): Boolean =
        target.domain != null && preferences.getString("browser.${target.packageName}", null) == target.certificate

    fun candidates(database: KeePassDatabase, target: FillTarget, requireTrusted: Boolean = true): List<Entry> = database.findEntries { entry ->
        if (target.domain != null) runCatching {
            val uri = entry.fields.url?.content.orEmpty().toUri()
            uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") &&
                uri.host?.equals(target.domain, true) == true
        }.getOrDefault(false)
        else if (requireTrusted) ApplicationBinding.trusted(entry, target.packageName, target.certificate)
        else ApplicationBinding.matches(entry, target.packageName)
    }.flatMap { it.second }

    private fun presentation(context: Context, title: String, icon: VaultIcon? = null) =
        RemoteViews(context.packageName, R.layout.autofill_entry).apply {
            setTextViewText(R.id.autofillEntryTitle, title)
            if (icon == null) setViewVisibility(R.id.autofillEntryIcon, View.GONE)
            else {
                val bitmap = icon.bitmap
                if (bitmap == null) setImageViewResource(R.id.autofillEntryIcon, icon.resourceId)
                else {
                    // Bound the total bitmap payload of a response sent through Binder.
                    val longest = maxOf(bitmap.width, bitmap.height)
                    val thumbnail = if (longest <= 64) bitmap else Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * 64 / longest).coerceAtLeast(1),
                        (bitmap.height * 64 / longest).coerceAtLeast(1), true)
                    setImageViewBitmap(R.id.autofillEntryIcon, thumbnail)
                }
            }
        }

    @Suppress("DEPRECATION")
    fun dataset(context: Context, target: FillTarget, entry: Entry, icons: VaultIcons = VaultIcons(emptyMap())): Dataset {
        val title = entry.fields.title?.content?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val builder = Dataset.Builder(presentation(context, title, icons.resolve(entry)))
        target.user?.let { builder.setValue(it, AutofillValue.forText(entry.fields.userName?.content.orEmpty())) }
        target.password?.let { builder.setValue(it, AutofillValue.forText(entry.fields.password?.content.orEmpty())) }
        return builder.build()
    }

    fun authentication(context: Context, target: FillTarget): android.content.IntentSender {
        val token = FillRequests.put(target)
        val intent = Intent(context, MainActivity::class.java).putExtra("fillToken", token)
        return PendingIntent.getActivity(context, token.hashCode(), intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    @Suppress("DEPRECATION")
    fun entries(context: Context, target: FillTarget, entries: List<Entry>, icons: VaultIcons = VaultIcons(emptyMap())): FillResponse? {
        if (entries.isEmpty()) return null
        val response = FillResponse.Builder()
        // Keep the response small enough for Binder; preserve access to every match.
        val visible = if (entries.size > 20) entries.take(19) else entries
        visible.forEach { response.addDataset(dataset(context, target, it, icons)) }
        if (visible.size < entries.size) {
            val more = Dataset.Builder(presentation(context, L10n.text(context, R.string.autofill_more_entries)))
            target.user?.let { more.setValue(it, null) }
            target.password?.let { more.setValue(it, null) }
            more.setAuthentication(authentication(context, target.copy(chooseInApp = true)))
            response.addDataset(more.build())
        }
        return response.build()
    }
}
