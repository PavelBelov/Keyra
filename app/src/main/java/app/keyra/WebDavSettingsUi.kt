package app.keyra

import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.keyra.core.*
import app.keyra.storage.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Shared WebDAV controls for first opening and synchronization settings. */
internal class WebDavSettingsUi(
    private val activity: AppCompatActivity,
    private val providers: Providers,
    private val source: () -> Connection,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog,
    private val selected: (String) -> Unit,
    private val browsingChanged: (Boolean) -> Unit
) {
    private fun text(id: Int, vararg args: Any?) = L10n.text(activity, id, *args)
    private var inspection: Job? = null
    private var failed = false
    private var loading = false
    private var browsing = false
    private var browsingJob: Job? = null
    private val certificate = activity.settingsAction(text(R.string.ui_certificate_228)) { showCertificate() }
    val certificateView: View get() = certificate
    fun refresh() {
        val connection = source()
        certificate.visibility = if (connection.location.toHttpUrlOrNull()?.isHttps == true) View.VISIBLE else View.GONE
        val info = providers.certificates.infoFor(connection.location)
        val status = when {
            loading -> R.string.dav_certificate_checking
            failed -> R.string.dav_certificate_unknown
            info?.trusted == true -> R.string.dav_certificate_valid
            info?.accepted == true -> R.string.dav_certificate_accepted
            info != null -> R.string.dav_certificate_custom
            else -> R.string.dav_certificate_unknown
        }
        certificate.text = activity.settingsText(text(R.string.ui_certificate_228), text(status),
            if (!loading && (failed || info?.trusted != true)) R.color.settings_warning else R.color.settings_control)
    }
    fun changed() {
        inspection?.cancel(); failed = false
        val connection = source()
        if (connection.location.toHttpUrlOrNull()?.isHttps != true) { loading = false; refresh(); return }
        loading = true; refresh()
        inspection = activity.lifecycleScope.launch {
            delay(700)
            try { withContext(Dispatchers.IO) { providers.inspectCertificate(connection) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = providers.certificates.pendingFor(connection.location) == null }
            finally { if (isActive) { loading = false; refresh() } }
        }
    }
    fun dispose() { inspection?.cancel(); browsingJob?.cancel() }
    fun showError(message: String) { error(message) }
    private fun showCertificate() {
        val connection = source()
        val info = providers.certificates.infoFor(connection.location)
        if (info == null) { changed(); error(text(R.string.dav_certificate_unknown)); return }
        val challenge = info.challenge
        val cert = challenge.certificate
        val date = java.text.DateFormat.getDateTimeInstance()
        val message = text(R.string.dav_certificate_details, challenge.origin, cert.subjectX500Principal.name,
            cert.issuerX500Principal.name, date.format(cert.notBefore), date.format(cert.notAfter), challenge.fingerprint)
        val builder = AlertDialog.Builder(activity).setTitle(text(R.string.ui_certificate_228)).setMessage(message)
            .setNegativeButton(text(R.string.ui_cancel_82), null)
        if (!failed && !info.trusted && !info.accepted) builder.setPositiveButton(text(R.string.ui_accept_certificate_131)) { _, _ ->
            activity.lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { providers.certificates.accept(challenge) }; changed() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error(text(R.string.ui_could_not_accept_the_certificate_check_its_validity_dates_133)) }
            }
        }
        showDialog(builder)
    }
    fun browse() {
        if (browsing) return
        val connection = source()
        if (connection.user.isBlank() || connection.password.isEmpty()) { error(text(R.string.dav_credentials_required)); return }
        browsing = true; browsingChanged(true)
        browsingJob = activity.lifecycleScope.launch {
            try {
                val folder = withContext(Dispatchers.IO) { providers.browse(connection, connection.location) }
                if (!certificate.isAttachedToWindow) return@launch
                showBrowser(connection, folder)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (certificate.isAttachedToWindow) { error(describe(failure)); refresh() } }
            finally { browsing = false; browsingChanged(false) }
        }
    }
    private fun showBrowser(connection: Connection, initial: DavFolder) {
        val body = activity.settingsList().apply { setBackgroundColor(activity.getColor(R.color.settings_surface)) }
        val path = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.settings_secondary)); textSize = 13f
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12))
        }
        val rows = activity.settingsList()
        val scroll = ScrollView(activity).apply { addView(rows) }
        body.addView(path); body.addView(scroll, LinearLayout.LayoutParams(-1, activity.dp(360)))
        val dialog = showDialog(AlertDialog.Builder(activity).setTitle(text(R.string.dav_choose_file)).setView(body)
            .setNegativeButton(text(R.string.ui_cancel_82), null))
        var job: Job? = null
        fun load(url: String, render: (DavFolder) -> Unit) {
            if (job?.isActive == true) return
            job = activity.lifecycleScope.launch {
                rows.isEnabled = false
                try {
                    val folder = withContext(Dispatchers.IO) { providers.browse(connection, url) }
                    if (dialog.isShowing) render(folder)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (dialog.isShowing) error(describe(failure), dialog.window!!.decorView) }
                finally { rows.isEnabled = true }
            }
        }
        fun Button.withFileIcon(resource: Int) = apply {
            val icon = activity.getDrawable(resource)!!.mutate()
            if (resource == R.drawable.ic_arrow_up) icon.setTint(activity.getColor(R.color.settings_control))
            icon.setBounds(0, 0, activity.dp(28), activity.dp(28))
            setCompoundDrawablesRelative(icon, null, compoundDrawablesRelative[2], null)
        }
        fun render(folder: DavFolder) {
            path.text = folder.url.encodedPath; rows.removeAllViews()
            if (folder.url.encodedPath != "/") rows.addView(activity.settingsAction("..") {
                load(WebDavBrowser.parent(folder.url).toString(), ::render)
            }.withFileIcon(R.drawable.ic_arrow_up))
            if (folder.entries.isEmpty()) rows.addView(TextView(activity).apply {
                text = text(R.string.dav_empty); setTextColor(activity.getColor(R.color.settings_secondary))
                setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            })
            folder.entries.forEach { entry ->
                rows.addView(activity.settingsAction(entry.name) {
                    if (job?.isActive == true) return@settingsAction
                    if (entry.directory) load(entry.url.toString(), ::render)
                    else { selected(entry.url.toString()); dialog.dismiss() }
                }.apply {
                    if (entry.directory) withFileIcon(R.drawable.vault_icon_48)
                    else if (entry.url.pathSegments.lastOrNull()?.endsWith(".kdbx", ignoreCase = true) == true)
                        withFileIcon(R.drawable.vault_icon_13)
                })
            }
        }
        dialog.setOnCancelListener { job?.cancel() }
        body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { job?.cancel() }
        })
        render(initial)
    }
    private fun describe(failure: Exception): String {
        val causes = generateSequence<Throwable>(failure) { it.cause }.toList()
        val storage = causes.filterIsInstance<StorageException>().firstOrNull()
        return when {
            causes.any { it is WebDavCertificateApprovalRequired } -> text(R.string.ui_server_certificate_requires_approval_19)
            causes.any { it is javax.net.ssl.SSLException } -> text(R.string.ui_tls_certificate_verification_failed_20)
            storage?.kind == StorageError.AUTH -> text(R.string.ui_check_your_webdav_username_and_password_66)
            storage?.kind == StorageError.PERMISSION -> text(R.string.ui_database_file_access_denied_68)
            storage?.kind == StorageError.NOT_FOUND -> text(R.string.ui_database_file_not_found_67)
            else -> text(R.string.dav_error)
        }
    }
    private fun error(message: String, anchor: View = activity.window.decorView) {
        if (!anchor.isAttachedToWindow) return
        val content = TextView(activity).apply {
            text = message; textSize = 14f; setTextColor(activity.getColor(R.color.settings_error))
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(16))
        }
        val popup = PopupWindow(content, anchor.width - activity.dp(32), -2, false).apply {
            elevation = activity.dp(8).toFloat(); isOutsideTouchable = true
        }
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, activity.dp(32))
        content.announceForAccessibility(message)
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { popup.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(listener)
        popup.setOnDismissListener { anchor.removeOnAttachStateChangeListener(listener) }
        activity.lifecycleScope.launch { delay(6000); popup.dismiss() }
        content.setOnClickListener { popup.dismiss() }
    }
}
