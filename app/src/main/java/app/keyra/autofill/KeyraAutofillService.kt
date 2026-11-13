package app.keyra.autofill

import android.app.assist.AssistStructure
import android.content.pm.PackageManager
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import app.keyra.KeyraApp
import app.keyra.L10n
import app.keyra.R
import app.keyra.VaultIcons
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class FillTarget(val user: AutofillId?, val password: AutofillId?, val packageName: String, val domain: String?, val certificate: String, val expires: Long, val chooseInApp: Boolean = false, val webScheme: String = "https")
object FillRequests {
    private val requests = ConcurrentHashMap<String, FillTarget>()
    fun put(target: FillTarget): String {
        requests.entries.removeIf { it.value.expires < System.currentTimeMillis() }
        return UUID.randomUUID().toString().also { requests[it] = target }
    }
    fun get(id: String) = requests[id]?.takeIf { it.expires >= System.currentTimeMillis() }
    fun remove(id: String) { requests.remove(id) }
}
fun packageCertificate(manager: PackageManager, name: String): String {
    @Suppress("DEPRECATION")
    val info = manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    val signatures = info.signingInfo?.apkContentsSigners.orEmpty()
    return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
}

class KeyraAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val app = structure.activityComponent.packageName
        if (app == packageName) return callback.onSuccess(null)
        val fields = mutableListOf<CredentialField<AutofillId>>()
        var nextScope = 0
        val domains = hashSetOf<String>()
        val schemes = hashSetOf<String>()
        fun visit(node: AssistStructure.ViewNode, parentScope: Int, visible: Boolean) {
            node.webDomain?.let { domains += it.lowercase(Locale.ROOT) }
            node.webScheme?.takeIf { it.isNotBlank() }?.let { schemes += it.lowercase(Locale.ROOT) }
            val scope = if (node.htmlInfo?.tag.equals("form", true)) ++nextScope else parentScope
            val shown = visible && node.visibility == View.VISIBLE
            val html = node.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase(Locale.ROOT) to it.second }
            val tag = node.htmlInfo?.tag
            val isInput = tag.equals("input", true) || (tag == null && node.inputType != 0)
            node.autofillId?.let { id ->
                fields += CredentialField(id, scope, node.autofillHints.orEmpty().toList(), html,
                    listOfNotNull(node.idEntry, node.hint), node.inputType, node.isFocused,
                    shown && node.isEnabled && isInput && "disabled" !in html && "readonly" !in html)
            }
            repeat(node.childCount) { visit(node.getChildAt(it), scope, shown) }
        }
        repeat(structure.windowNodeCount) { visit(structure.getWindowNodeAt(it).rootViewNode, ++nextScope, true) }
        val (user, password) = CredentialFields.select(fields)
        if ((user == null && password == null) || domains.size > 1 || schemes.size > 1 ||
            schemes.any { it !in setOf("http", "https") } || cancellationSignal.isCanceled) return callback.onSuccess(null)
        val domain = domains.singleOrNull()
        val certificate = runCatching { packageCertificate(packageManager, app) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return callback.onSuccess(null)
        val target = FillTarget(user, password, app, domain, certificate, System.currentTimeMillis() + 120_000,
            webScheme = schemes.singleOrNull() ?: "https")
        val repo = (application as KeyraApp).repository
        val database = repo.database.value
        if (database != null && (domain == null || AutofillResponses.trustedBrowser(repo.store.preferences, target))) {
            val candidates = AutofillResponses.candidates(database, target)
            if (candidates.isNotEmpty()) {
                val direct = AutofillResponses.entries(this, target, candidates, VaultIcons(database.content.meta.customIcons))
                if (!cancellationSignal.isCanceled && repo.database.value === database) callback.onSuccess(direct)
                else callback.onSuccess(null)
                return
            }
        }
        val sender = AutofillResponses.authentication(this, target)
        val presentation = RemoteViews(packageName, R.layout.autofill_unlock).apply {
            setTextViewText(R.id.autofillSelectLabel, L10n.text(this@KeyraAutofillService, R.string.autofill_select))
        }
        val ids = listOfNotNull(user, password).toTypedArray()
        val response = FillResponse.Builder()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            response.setAuthentication(ids, sender, Presentations.Builder().setMenuPresentation(presentation).build())
        } else {
            @Suppress("DEPRECATION")
            response.setAuthentication(ids, sender, presentation)
        }
        callback.onSuccess(response.build())
    }
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) { callback.onSuccess() }
}
