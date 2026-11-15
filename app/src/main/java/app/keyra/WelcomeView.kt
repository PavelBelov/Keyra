package app.keyra

import android.net.Uri

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import app.keyra.storage.Connection
import app.keyra.storage.Providers
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom

/** First-run forms. Secrets live only for the lifetime of this view. */
// Constructed in code with required data and callbacks; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
class WelcomeView(
    private val activity: AppCompatActivity,
    private val title: (String, Boolean) -> Unit,
    private val pickKey: ((ByteArray?) -> Unit) -> Unit,
    private val pickFile: ((Uri, String) -> Unit) -> Unit,
    private val create: (String, ByteArray?, () -> Unit) -> Unit,
    private val prepare: (Uri?, Connection?, (PreparedVault?, String?) -> Unit) -> Unit,
    private val providers: Providers,
    private val showDialog: (AlertDialog.Builder) -> AlertDialog,
    private val open: (PreparedVault, String, ByteArray?, (String?) -> Unit) -> Unit
) : LinearLayout(activity) {
    private var key: ByteArray? = null
    private var revision = 0
    private var working = false
    private val secrets = mutableListOf<EditText>()
    private var page = "choices"
    private var prepared: PreparedVault? = null
    private var webdavDraft: Connection? = null
    private var accessFromWebdav = false
    private var webdavUi: WebDavSettingsUi? = null
    val showingWebDav: Boolean get() = page == "webdav"
    var inForm = false
        private set

    init { orientation = VERTICAL; showChoices() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(context).apply {
        text = value; textSize = size; setPadding(0, dp(8), 0, dp(8))
    }
    private fun action(value: String, block: () -> Unit) = Button(context).apply {
        text = value; isAllCaps = false; setOnClickListener { if (!working) block() }
    }
    private fun input(hint: String, secret: Boolean = false) = EditText(context).apply {
        this.hint = hint; isSingleLine = true; isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT
        if (secret) maskPasswordInput()
        if (secret) secrets += this
    }
    fun dispose() {
        clearInputs()
        prepared?.dispose(); prepared = null; webdavDraft = null
    }
    private fun clearInputs() {
        webdavUi?.dispose(); webdavUi = null
        revision++; key?.fill(0); key = null
        secrets.forEach { it.text.clear() }; secrets.clear()
    }
    fun back(): Boolean {
        if (!inForm || working) return false
        when (page) {
            "access" -> {
                prepared?.dispose(); prepared = null
                if (accessFromWebdav) showWebdav() else showOpen()
            }
            "webdav" -> showOpen()
            else -> showChoices()
        }
        return true
    }
    private fun reset(name: String, form: Boolean) {
        clearInputs(); removeAllViews(); inForm = form; title(name, form)
    }
    fun showChoices() {
        prepared?.dispose(); prepared = null; webdavDraft = null; page = "choices"
        reset(L10n.text(context, R.string.app_name), false)
        val choices = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        context.addBrandIntro(choices, R.string.welcome_caption)
        fun choice(caption: String, icon: Int, block: () -> Unit) {
            choices.addView(action(caption, block).apply {
                setBackgroundResource(R.drawable.bg_secondary_button); backgroundTintList = null; elevation = 0f
                setPadding(dp(20), dp(20), dp(20), dp(20)); textSize = 16f
                setTextColor(context.getColor(R.color.accent))
                val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, icon)!!.mutate()
                drawable.setTintList(textColors)
                drawable.setBounds(0, 0, dp(28), dp(28))
                setCompoundDrawablesRelative(drawable, null, null, null); compoundDrawablePadding = dp(16)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        choice(L10n.text(context, R.string.ui_create_a_new_database_214), R.drawable.ic_add_entry) { showCreate() }
        choice(L10n.text(context, R.string.ui_open_an_existing_database_215), R.drawable.ic_open_database) { showOpen() }
        addView(ScrollView(context).apply { isFillViewport = true; addView(choices) }, LayoutParams(-1, -1))
    }
    private fun form(name: String): LinearLayout {
        reset(name, true)
        val body = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(8), dp(16), dp(8), dp(24))
        }
        addView(ScrollView(context).apply { addView(body) }, LayoutParams(-1, -1))
        return body
    }
    private fun setWorking(value: Boolean) {
        working = value
        fun enable(view: View) {
            view.isEnabled = !value
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        enable(this)
    }
    private fun showCreate() {
        page = "create"
        val body = form(L10n.text(context, R.string.ui_new_database_216)).apply { setPadding(dp(14), dp(8), dp(14), dp(32)) }
        fun card(title: String) = context.settingsCard(body, title)
        val keyCard = card(L10n.text(context, R.string.ui_key_file_optional_217))
        val status = text("", 13f).apply {
            visibility = GONE; setTextColor(context.getColor(R.color.settings_secondary))
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        keyCard.addView(row(L10n.text(context, R.string.ui_open_94)) {
            val token = revision
            pickKey { bytes ->
                if (token != revision) bytes?.fill(0)
                else if (bytes != null) {
                    revision++; key?.fill(0); key = bytes
                    status.text = L10n.text(context, R.string.ui_key_file_selected_the_key_will_be_saved_in_protected_storage_on_t_218)
                    status.visibility = VISIBLE
                }
            }
        }, LayoutParams(-1, -2))
        keyCard.addView(row(L10n.text(context, R.string.ui_create_219)) {
            revision++; key?.fill(0); key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            status.text = L10n.text(context, R.string.ui_key_file_created_the_key_will_be_saved_in_protected_storage_on_th_220)
            status.visibility = VISIBLE
        }, LayoutParams(-1, -2))
        keyCard.addView(status)
        val password = input(L10n.text(context, R.string.field_password), true).apply {
            background = null; textSize = 16f; minimumHeight = dp(64)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(context.getColor(R.color.settings_text))
            setHintTextColor(context.getColor(R.color.settings_secondary))
        }
        card(L10n.text(context, R.string.field_password)).addView(password.withPasswordVisibilityToggle(), LayoutParams(-1, -2))
        body.addView(action(L10n.text(context, R.string.ui_create_database_221)) {
            if (password.length() < 6) { password.error = L10n.text(context, R.string.ui_at_least_6_characters_84); return@action }
            setWorking(true)
            create(password.text.toString(), key?.copyOf()) { setWorking(false) }
        }.apply {
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_create_database)
            setTextColor(context.getColor(R.color.on_accent)); textSize = 16f; gravity = Gravity.CENTER
        }, LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
    }
    private fun settingsForm(name: String) = form(name).apply { setPadding(dp(14), dp(8), dp(14), dp(32)) }
    private fun card(body: LinearLayout, title: String) = context.settingsCard(body, title)
    private fun rowText(caption: String, summary: String?) = context.settingsText(caption, summary)
    private fun row(caption: String, summary: String? = null, block: () -> Unit) =
        context.settingsAction(caption, summary) { if (!working) block() }
    private fun styledInput(hint: String, value: String = "", secret: Boolean = false) = input(hint, secret).apply {
        setText(value); background = null; textSize = 16f; minimumHeight = dp(64)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setTextColor(context.getColor(R.color.settings_text)); setHintTextColor(context.getColor(R.color.settings_secondary))
    }
    private fun note(value: String) = text(value, 13f).apply {
        setTextColor(context.getColor(R.color.settings_secondary)); setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    private fun primary(body: LinearLayout, caption: String, block: () -> Unit) {
        body.addView(action(caption, block).apply {
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_create_database)
            setTextColor(context.getColor(R.color.on_accent)); textSize = 16f
        }, LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
    }
    private fun showOpen() {
        page = "sources"; webdavDraft = null
        val body = settingsForm(L10n.text(context, R.string.ui_open_database_222))
        card(body, L10n.text(context, R.string.ui_database_source_223)).apply {
            addView(row(L10n.text(context, R.string.ui_file_107), L10n.text(context, R.string.ui_on_this_device_or_in_cloud_storage_224)) {
                val token = revision
                pickFile { uri, _ ->
                    if (token == revision) {
                        setWorking(true)
                        prepare(uri, null) { source, error ->
                            if (token != revision) source?.dispose()
                            else {
                                setWorking(false)
                                if (source != null) showAccess(source, false)
                                else android.widget.Toast.makeText(context, error ?: L10n.text(context, R.string.ui_could_not_read_the_file_225), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            })
            addView(row(L10n.text(context, R.string.webdav_name), L10n.text(context, R.string.ui_http_or_https_226)) { showWebdav() })
        }
    }
    fun refreshCertificateInfo() { webdavUi?.refresh() }
    private fun showWebdav() {
        page = "webdav"
        val previous = webdavDraft
        val body = settingsForm(L10n.text(context, R.string.webdav_name))
        val fields = card(body, L10n.text(context, R.string.ui_connection_227))
        val url = styledInput(L10n.text(context, R.string.url_hint), previous?.location.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val user = styledInput(L10n.text(context, R.string.field_login), previous?.user.orEmpty())
        val password = styledInput(L10n.text(context, R.string.field_password), previous?.password.orEmpty(), true)
        val urlRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val browse = ImageButton(context).apply {
            setImageResource(R.drawable.ic_open_database)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.settings_control))
            background = null
            contentDescription = L10n.text(context, R.string.dav_choose_file)
        }
        urlRow.addView(url, LayoutParams(0, -2, 1f))
        urlRow.addView(browse, LayoutParams(dp(48), dp(64)))
        fields.addView(urlRow); fields.addView(user); fields.addView(password)
        fun connection() = Connection("webdav", url.text.toString().trim(), user.text.toString(), password.text.toString())
        val formRevision = revision
        val ui = WebDavSettingsUi(activity, providers, source = { connection() }, showDialog = showDialog,
            selected = { url.setText(it); url.setSelection(url.length()) },
            browsingChanged = { busy ->
                if (revision == formRevision) setWorking(busy)
                browse.alpha = if (busy) 0.4f else 1f
            })
        webdavUi = ui
        fields.addView(ui.certificateView)
        browse.setOnClickListener { if (!working) ui.browse() }
        fun updateUrl() {
            val parsed = url.text.toString().trim().toHttpUrlOrNull()
            browse.visibility = if (parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty()) VISIBLE else GONE
            ui.changed()
        }
        urlRow.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { updateUrl() }
            override fun onViewDetachedFromWindow(view: View) { ui.dispose() }
        })
        url.doAfterTextChanged { updateUrl() }
        updateUrl()
        fun proceed() {
            val source = connection()
            webdavDraft = source
            val parsed = source.location.toHttpUrlOrNull()
            if (parsed == null || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                url.error = L10n.text(context, R.string.ui_enter_an_http_or_https_address_for_the_database_file_232)
                return
            }
            val token = revision
            setWorking(true)
            prepare(null, source) { vault, message ->
                if (token != revision) vault?.dispose()
                else {
                    setWorking(false)
                    if (vault != null) showAccess(vault, true)
                    else {
                        ui.showError(message ?: L10n.text(context, R.string.ui_could_not_connect_233))
                        refreshCertificateInfo()
                    }
                }
            }
        }
        primary(body, L10n.text(context, R.string.ui_continue_234)) { proceed() }
    }
    private fun showAccess(source: PreparedVault, fromWebdav: Boolean) {
        page = "access"; accessFromWebdav = fromWebdav
        val body = settingsForm(L10n.text(context, R.string.ui_open_database_222))
        prepared?.takeIf { it !== source }?.dispose(); prepared = source
        card(body, L10n.text(context, R.string.ui_database_235)).addView(note(source.label))
        val access = card(body, L10n.text(context, R.string.ui_database_access_143))
        val keyRow = row(L10n.text(context, R.string.ui_key_file_144), L10n.text(context, R.string.ui_not_selected_optional_145)) {
            val token = revision
            pickKey { bytes ->
                if (token != revision) bytes?.fill(0)
                else if (bytes != null) {
                    key?.fill(0); key = bytes
                    // Update only the subtitle, preserving the entered database password.
                    (access.getChildAt(0) as Button).text = rowText(L10n.text(context, R.string.ui_key_file_144), L10n.text(context, R.string.ui_key_file_selected_79))
                }
            }
        }
        access.addView(keyRow)
        val password = styledInput(L10n.text(context, R.string.field_password), secret = true)
        access.addView(password.withPasswordVisibilityToggle(), LayoutParams(-1, -2))
        if (!source.writable) body.addView(note(L10n.text(context, R.string.ui_the_source_is_read_only_a_local_copy_will_be_opened_236)))
        val error = note("").apply { visibility = GONE; setTextColor(context.getColor(R.color.settings_error)) }
        body.addView(error)
        primary(body, L10n.text(context, R.string.ui_open_237)) {
            if (password.length() == 0) { password.error = L10n.text(context, R.string.ui_enter_a_password_148); return@primary }
            error.visibility = GONE
            setWorking(true)
            open(source, password.text.toString(), key?.copyOf()) { message ->
                setWorking(false)
                if (message != null) { error.text = message; error.visibility = VISIBLE }
            }
        }
    }
}
