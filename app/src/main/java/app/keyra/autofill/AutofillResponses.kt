package app.keyra.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.core.net.toUri
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.findEntries
import app.keemobile.kotpass.models.Entry
import app.keyra.L10n
import app.keyra.MainActivity
import app.keyra.R

internal object AutofillResponses {
    fun trustedBrowser(preferences: SharedPreferences, target: FillTarget): Boolean =
        target.domain != null && preferences.getString("browser.${target.packageName}", null) == target.certificate

    fun candidates(database: KeePassDatabase, target: FillTarget): List<Entry> = database.findEntries { entry ->
        if (target.domain != null) runCatching {
            entry.fields.url?.content.orEmpty().toUri().host?.equals(target.domain, true) == true
        }.getOrDefault(false)
        else entry.fields["Keyra.Package"]?.content == target.packageName &&
            entry.fields["Keyra.Signature"]?.content == target.certificate
    }.flatMap { it.second }

    private fun presentation(context: Context, title: String) =
        RemoteViews(context.packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, title)
        }

    @Suppress("DEPRECATION")
    fun dataset(context: Context, target: FillTarget, entry: Entry): Dataset {
        val title = entry.fields.title?.content?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name)
        val builder = Dataset.Builder(presentation(context, title))
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
    fun entries(context: Context, target: FillTarget, entries: List<Entry>): FillResponse? {
        if (entries.isEmpty()) return null
        val response = FillResponse.Builder()
        // Keep the response small enough for Binder; preserve access to every match.
        val visible = if (entries.size > 20) entries.take(19) else entries
        visible.forEach { response.addDataset(dataset(context, target, it)) }
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
