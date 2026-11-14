package app.keyra

import android.net.Uri

import androidx.core.net.toUri
import androidx.core.content.edit

import android.Manifest
import android.content.*
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.autofill.AutofillManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import app.keyra.core.*
import app.keyra.databinding.ActivityMainBinding
import app.keyra.storage.*
import app.keyra.autofill.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : AppCompatActivity() {
    private val model: MainViewModel by viewModels()
    private val repo get() = model.repository
    private lateinit var binding: ActivityMainBinding
    private var searchJob: Job? = null
    private var journalJob: Job? = null
    private var keyCallback: ((ByteArray?) -> Unit)? = null
    private var sourceSelection = false
    private var importing = false
    private var importPassword: EditText? = null
    private fun clearImportPassword() {
        val field = importPassword; importPassword = null
        field?.text?.clear()
    }
    private var listAdapter: RowsAdapter? = null
    private var filterRows: ((String) -> Unit)? = null
    private var updatingSearch = false
    private var addExpanded = false
    private var mainMenu: PopupMenu? = null
    private var lastRootBackAt = -1L
    private var exitToast: Toast? = null
    private val entryFieldViews = mutableListOf<EntryFieldView>()
    private val editorFieldViews = linkedMapOf<String, EditableEntryFieldView>()
    private var generatorView: PasswordGeneratorView? = null
    private val dialogs = mutableListOf<AlertDialog>()
    private var biometric: BiometricPrompt? = null
    private var biometricActive = false
    private var autoQuickAttempted = false
    private var startupPermissionPending = false
    private var loginPassword: EditText? = null
    private val autofillPicking get() = model.autofillPickerToken != null
    private var welcomeView: WelcomeView? = null
    private var welcomeFileCallback: ((Uri, String) -> Unit)? = null
    private var keyExportBytes: ByteArray? = null
    private var refreshSynchronization: (() -> Unit)? = null
    private var refreshAutofill: (() -> Unit)? = null
    private var autofillStatusJob: Job? = null
    private val syncSecretFields = mutableListOf<EditText>()
    private var clearingSyncSecrets = false
    private var lastCertificatePrompt: String? = null
    private fun clearSyncSecrets(dispose: Boolean = true) {
        clearingSyncSecrets = true
        try {
            syncSecretFields.forEach { it.text.clear() }
            if (dispose) syncSecretFields.clear()
        } finally { clearingSyncSecrets = false }
    }

    private val welcomeFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = welcomeFileCallback; welcomeFileCallback = null
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            callback?.invoke(uri, uri.lastPathSegment ?: L10n.text(this@MainActivity, R.string.ui_database_file_42))
        }
    }
    private val keyExportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val bytes = keyExportBytes; keyExportBytes = null
        lifecycleScope.launch {
            try {
                val ok = uri != null && bytes != null && ioResult {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    repo.store.log("KEY_EXPORT_COMPLETE")
                }
                if (ok) toast(L10n.text(this@MainActivity, R.string.ui_key_file_saved_43))
            } finally { bytes?.fill(0) }
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startupPermissionPending = false
        maybeAutoUnlock()
    }
    private val keyPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = keyCallback; keyCallback = null
        lifecycleScope.launch {
            try {
                val bytes = if (uri == null) null else withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readLimited(1024 * 1024)
                        try { Kdbx.credentials("", data); data }
                        catch (e: Exception) { data.fill(0); throw e }
                    }
                }
                if (callback != null) callback(bytes) else bytes?.fill(0)
            } catch (_: Exception) { toast(L10n.text(this@MainActivity, R.string.ui_could_not_read_the_key_file_check_its_format_integrity_and_size_m_44)) }
        }
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val forSource = sourceSelection; sourceSelection = false
        val isImport = importing; importing = false
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (forSource) {
                if (canWrite(uri)) lifecycleScope.launch { repo.attach(Connection("saf", uri.toString())); repo.sync() }
                else toast(L10n.text(this@MainActivity, R.string.ui_persistent_write_permission_is_unavailable_for_this_file_use_impo_45))
            } else if (isImport) {
                if (repo.database.value != null) {
                    model.discardImport(); model.importDraft = ImportDraft(uri); model.panel = Panel.IMPORT; render()
                }
            }
        }
    }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) lifecycleScope.launch {
            val succeeded = ioResult { repo.exportTo(contentResolver.openOutputStream(uri, "wt")!!) }
            if (succeeded) toast(L10n.text(this@MainActivity, R.string.ui_database_exported_34))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoQuickAttempted = savedInstanceState?.getBoolean("autoQuickAttempted") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.searchButton.setOnClickListener { openSearch() }
        binding.closeSearch.setOnClickListener { closeSearch() }
        binding.clearSearch.setOnClickListener { binding.headerSearch.text.clear() }
        binding.menuButton.setOnClickListener { menu() }
        binding.navigateBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.saveButton.setOnClickListener {
            if (model.generator != null) applyGeneratedPassword() else saveEntryDraft()
        }
        binding.editButton.setOnClickListener {
            if (model.generator != null) { generatorView?.regenerate(); return@setOnClickListener }
            val entry = model.entry?.let { id -> repo.database.value?.getEntryBy { uuid == id } }
            if (entry != null) {
                entryFieldViews.forEach { it.hideValue() }
                editEntry(entry)
            }
        }
        binding.headerSearch.doAfterTextChanged {
            if (!updatingSearch && model.searching) { model.query = it.toString(); filterRows?.invoke(model.query) }
        }
        binding.headerSearch.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        binding.addButton.setOnClickListener { setAddExpanded(!addExpanded) }
        binding.addDismiss.setOnClickListener { setAddExpanded(false) }
        binding.addEntry.setOnClickListener { setAddExpanded(false); hideKeyboard(); editEntry(null) }
        binding.addGroup.setOnClickListener { setAddExpanded(false); hideKeyboard(); editGroup(null) }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val db = repo.database.value
                val atRoot = db != null && !model.saving.value && !model.importingDatabase.value &&
                    model.panel == null && model.generator == null && model.editing == null &&
                    !addExpanded && !model.searching && model.entry == null &&
                    (model.group == null || model.group == db.content.group.uuid)
                if (!atRoot) clearExitConfirmation()
                when {
                    db == null && welcomeView?.inForm == true -> { welcomeView?.back(); Unit }
                    db == null -> finish()
                    model.saving.value -> Unit
                    model.importingDatabase.value -> Unit
                    model.panel == Panel.IMPORT -> { model.discardImport(); model.panel = Panel.DATA; hideKeyboard(); render() }
                    model.panel == Panel.JOURNAL -> { model.panel = Panel.DATA; render() }
                    model.panel != null -> { model.panel = null; render() }
                    model.generator != null -> {
                        model.generator?.dispose(); model.generator = null
                        hideKeyboard(); render()
                    }
                    model.editing != null -> closeEntryEditor()
                    addExpanded -> setAddExpanded(false)
                    model.searching -> closeSearch()
                    model.entry != null -> { model.entry = null; render() }
                    model.group != null && model.group != db.content.group.uuid -> {
                        model.group = db.getGroup { it.uuid == model.group }?.first?.uuid ?: db.content.group.uuid; render()
                    }
                    autofillPicking -> finish()
                    else -> requestExit()
                }
            }
        })
        lifecycleScope.launch {
            // Clear secrets from views even while the activity is stopped.
            repo.database.collect { db ->
                if (db == null) {
                    model.discardEditing(); model.discardImport(); model.entry = null; model.query = ""; model.searching = false; model.panel = null; model.syncSourceKind = null
                    mainMenu?.dismiss(); hideKeyboard(); dialogs.toList().forEach { it.dismiss() }; dialogs.clear()
                    render()
                } else if (model.panel != null) {
                    updateToolbar(db); refreshSynchronization?.invoke()
                } else if (model.editing != null) updateToolbar(db)
                else render()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.busy.collect {
                    binding.progress.visibility = if (it) View.VISIBLE else View.GONE
                    if (!it && model.panel != null && repo.database.value != null) render()
                } }
                launch { repo.status.collect { refreshSynchronization?.invoke() } }
                launch { repo.syncing.collect { refreshSynchronization?.invoke() } }
                launch { repo.providers.certificates.information.collect {
                    refreshSynchronization?.invoke(); welcomeView?.refreshCertificateInfo()
                } }
                launch { repo.message.collect { if (it.isNotEmpty()) toast(it) } }
                launch { model.saving.collect { if (repo.database.value != null) render() } }
                launch { model.importingDatabase.collect { if (repo.database.value != null) render() } }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                repo.providers.certificates.pending.collect { challenge ->
                    refreshSynchronization?.invoke()
                    welcomeView?.refreshCertificateInfo()
                    if (challenge != null && model.panel != Panel.DATA && welcomeView?.showingWebDav != true) showCertificateWarning(challenge)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            startupPermissionPending = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    override fun onPostResume() { super.onPostResume(); maybeAutoUnlock() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("autoQuickAttempted", autoQuickAttempted)
        super.onSaveInstanceState(outState)
    }
    private fun maybeAutoUnlock() {
        if (autoQuickAttempted || startupPermissionPending || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (repo.database.value != null || !repo.store.local.exists() || loginPassword == null) return
        autoQuickAttempted = true
        if (repo.secrets.has("quick") && systemUnlockAvailable()) {
            hideKeyboard()
            quickUnlock()
        }
    }
    override fun onStart() { super.onStart(); repo.cancelScheduledLock(); if (repo.database.value != null) lifecycleScope.launch { repo.sync() } }
    override fun onResume() {
        super.onResume()
        refreshAutofill?.invoke()
    }
    override fun onStop() {
        super.onStop()
        clearExitConfirmation()
        importPassword?.maskPasswordInput()
        clearSyncSecrets(dispose = false)
        loginPassword?.text?.clear()
        entryFieldViews.forEach { it.hideValue() }
        editorFieldViews.values.forEach { it.hideValue() }
        generatorView?.hideValue()
        if (!isChangingConfigurations) repo.scheduleLock()
    }
    override fun onDestroy() {
        autofillStatusJob?.cancel(); refreshAutofill = null
        journalJob?.cancel()
        clearImportPassword()
        refreshSynchronization = null
        clearSyncSecrets()
        loginPassword?.text?.clear(); loginPassword = null
        welcomeView?.dispose(); welcomeView = null
        keyExportBytes?.fill(0); keyExportBytes = null
        welcomeFileCallback = null; keyCallback = null
        searchJob?.cancel(); biometric?.cancelAuthentication()
        mainMenu?.dismiss()
        entryFieldViews.forEach { it.hideValue() }
        entryFieldViews.clear()
        editorFieldViews.values.forEach { it.dispose() }; editorFieldViews.clear()
        generatorView?.hideValue(); generatorView = null
        dialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setPadding(dp(8), dp(12), dp(8), dp(12)); setTextIsSelectable(false) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private fun field(hint: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
        this.hint = hint; setText(value); isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_TEXT
        if (secret) maskPasswordInput()
    }
    private fun dialog(builder: AlertDialog.Builder): AlertDialog = builder.create().also { d ->
        dialogs += d; d.setOnDismissListener { dialogs.remove(d) }; d.show(); d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun scroll(content: View) = ScrollView(this).apply {
        setPadding(0, dp(8), 0, dp(24)); clipToPadding = false; addView(content)
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun clearExitConfirmation() {
        lastRootBackAt = -1L
        exitToast?.cancel(); exitToast = null
    }
    private fun requestExit() {
        val now = SystemClock.elapsedRealtime()
        if (lastRootBackAt >= 0 && now - lastRootBackAt <= 2_000L) {
            clearExitConfirmation()
            finishAndRemoveTask()
            repo.lock()
        } else {
            lastRootBackAt = now
            exitToast?.cancel()
            exitToast = Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    private suspend fun ioResult(block: suspend () -> Unit): Boolean = try { withContext(Dispatchers.IO) { block() }; true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { toast(L10n.text(this@MainActivity, R.string.ui_operation_failed_check_access_permissions_and_your_connection_47)); false }

    private fun synchronizationStatusText(): String {
        if (repo.syncing.value) return L10n.text(this@MainActivity, R.string.ui_syncing_48)
        val preferences = repo.store.preferences
        val time = preferences.getLong("lastSyncAttempt", preferences.getLong("lastSync", 0))
        if (time == 0L) return L10n.text(this@MainActivity, R.string.ui_not_synced_yet_49)
        val result = preferences.getString("lastSyncResult", SyncStatus.SYNCED.name)
        val detail = syncFailureDescription(this, preferences.getString("lastSyncDetailCode", null))
        val text = if (result != SyncStatus.SYNCED.name && !detail.isNullOrBlank()) L10n.text(this@MainActivity, R.string.ui_failed_50, detail) else when (result) {
            SyncStatus.SYNCED.name -> L10n.text(this@MainActivity, R.string.ui_successful_51)
            SyncStatus.OFFLINE.name -> L10n.text(this@MainActivity, R.string.ui_failed_source_unavailable_52)
            SyncStatus.AUTH_REQUIRED.name -> L10n.text(this@MainActivity, R.string.ui_failed_authorization_required_53)
            SyncStatus.CONFLICT.name -> L10n.text(this@MainActivity, R.string.ui_failed_data_conflict_54)
            SyncStatus.APPROVAL_REQUIRED.name -> L10n.text(this@MainActivity, R.string.ui_failed_upload_approval_required_55)
            SyncStatus.LOCKED.name -> L10n.text(this@MainActivity, R.string.ui_failed_database_locked_56)
            SyncStatus.PENDING.name -> L10n.text(this@MainActivity, R.string.ui_failed_changes_have_not_been_sent_57)
            else -> L10n.text(this@MainActivity, R.string.ui_failed_58)
        }
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(time))
        return "$date · $text"
    }
    private fun render() {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        autofillStatusJob?.cancel(); refreshAutofill = null
        journalJob?.cancel(); journalJob = null
        clearImportPassword()
        refreshSynchronization = null
        clearSyncSecrets()
        searchJob?.cancel(); listAdapter = null; filterRows = null
        entryFieldViews.forEach { it.hideValue() }
        entryFieldViews.clear()
        editorFieldViews.values.forEach { it.dispose() }; editorFieldViews.clear()
        generatorView?.hideValue(); generatorView = null
        setAddExpanded(false)
        welcomeView?.dispose(); welcomeView = null
        welcomeFileCallback = null
        loginPassword?.text?.clear(); loginPassword = null
        binding.content.removeAllViews()
        val db = repo.database.value
        val fillToken = intent.getStringExtra("fillToken")
        if (db != null && fillToken != null && !model.autofillInitialized) {
            model.autofillInitialized = true
            chooseAutofill(fillToken)
            return
        }
        updateToolbar(db)
        binding.addButton.visibility = View.GONE
        if (db == null) {
            keyCallback = null
            if (repo.store.local.exists()) {
                showLogin()
                maybeAutoUnlock()
            } else {
                showWelcome()
            }
            return
        }
        if (model.panel == Panel.JOURNAL) { showJournalForm(); return }
        if (model.panel == Panel.IMPORT) { showImportForm(); return }
        if (model.panel != null) { showPanel(); return }
        val generator = model.generator
        if (generator != null) { showPasswordGenerator(generator); return }
        val draft = model.editing
        if (draft != null) { showEntryEditor(draft); return }
        val entry = model.entry?.let { id -> db.getEntryBy { uuid == id } }
        if (entry != null) { showEntry(entry); return }
        model.entry = null
        binding.addButton.visibility = if (autofillPicking) View.GONE else View.VISIBLE
        val fillTarget = model.autofillPickerToken?.let(FillRequests::get)
        if (autofillPicking) {
            if (fillTarget == null) { finish(); return }
            val appName = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(fillTarget.packageName, 0)).toString()
            }.getOrDefault(fillTarget.packageName)
            binding.content.addView(panelInfo(if (fillTarget.domain != null)
                L10n.text(this, R.string.autofill_select_for_website, AutofillResponses.websiteUrl(fillTarget) ?: fillTarget.domain)
            else L10n.text(this, R.string.autofill_select_for_app, appName)))
        }
        val group = model.group?.let { Kdbx.group(db, it) } ?: db.content.group
        model.group = group.uuid
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity); setHasFixedSize(false)
            setPadding(0, dp(8), 0, dp(104)); clipToPadding = false
        }
        val adapter = RowsAdapter({ row ->
            if (autofillPicking && !row.folder) {
                db.getEntryBy { uuid == row.id }?.let { selectAutofillEntry(it) }
                return@RowsAdapter
            }
            hideKeyboard(); model.searching = false; model.query = ""
            if (row.folder) model.group = row.id else model.entry = row.id
            render()
        }, { row -> if (!autofillPicking) rowMenu(row) })
        listAdapter = adapter; recycler.adapter = adapter
        val emptyTitle = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.settings_text))
        }
        val emptyHint = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.settings_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(104)); visibility = View.GONE
            addView(emptyTitle); addView(emptyHint)
        }
        binding.content.addView(FrameLayout(this).apply {
            addView(recycler, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val icons = VaultIcons(db.content.meta.customIcons)
        fun filter(query: String) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                delay(120)
                val rows = withContext(Dispatchers.Default) {
                    if (query.isBlank()) {
                        val suggested = if (fillTarget != null && group.uuid == db.content.group.uuid)
                            AutofillResponses.candidates(db, fillTarget, requireTrusted = false).map { entryRow(it, icons) }
                        else emptyList()
                        (suggested + group.groups.map { Row(it.uuid, it.name, L10n.text(this@MainActivity, R.string.ui_folders_entries_59, it.groups.size, it.entries.size), true, icons.resolve(it)) } + group.entries.map { entryRow(it, icons) }).distinctBy { it.id }
                    }
                    else db.searchUnprotected(query)
                        .flatMap { (g, entries) -> entries.map { entryRow(it, icons).copy(subtitle = "${g.name} · ${it.fields.userName?.content.orEmpty()}") } }
                }
                if (repo.database.value === db && listAdapter === adapter && model.query == query) {
                    adapter.submitList(rows)
                    emptyTitle.text = L10n.text(this@MainActivity, if (query.isBlank()) R.string.vault_empty else R.string.search_empty)
                    emptyHint.text = L10n.text(this@MainActivity, if (query.isBlank()) R.string.vault_empty_hint else R.string.search_empty_hint)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
        filterRows = ::filter
        filter(model.query)
    }
    private fun showLogin() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), dp(24))
        }
        val password = field(L10n.text(this@MainActivity, R.string.field_password), secret = true).apply {
            isSingleLine = true
            maskPasswordInput()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        loginPassword = password
        addBrandIntro(body, R.string.ui_unlock_61)
        body.addView(password.withPasswordVisibilityToggle(), LinearLayout.LayoutParams(-1, -2))
        val login = button(L10n.text(this@MainActivity, R.string.ui_unlock_61)) { }
        val fingerprint = ImageButton(this).apply {
            setImageResource(R.drawable.ic_fingerprint)
            imageTintList = password.textColors
            val backgroundValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
            setBackgroundResource(backgroundValue.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = L10n.text(this@MainActivity, R.string.ui_unlock_with_biometrics_or_device_credentials_62)
            tooltipText = contentDescription
            isEnabled = repo.secrets.has("quick") && systemUnlockAvailable()
            alpha = if (isEnabled) 1f else 0.35f
            setOnClickListener { hideKeyboard(); quickUnlock() }
        }
        fun submit() {
            if (repo.busy.value || biometricActive || !login.isEnabled) return
            val value = password.text.toString()
            if (value.isEmpty()) { password.error = L10n.text(this@MainActivity, R.string.ui_enter_your_password_63); return }
            hideKeyboard(); password.text.clear()
            login.isEnabled = false; password.isEnabled = false; fingerprint.isEnabled = false
            lifecycleScope.launch {
                try {
                    repo.unlock(value, null)
                    if (repo.database.value != null) { enableQuickIfAvailable(); repo.sync() }
                    else password.error = L10n.text(this@MainActivity, R.string.ui_could_not_unlock_check_your_password_64)
                } finally {
                    login.isEnabled = true; password.isEnabled = true
                    fingerprint.isEnabled = repo.secrets.has("quick") && systemUnlockAvailable()
                    fingerprint.alpha = if (fingerprint.isEnabled) 1f else 0.35f
                }
            }
        }
        login.apply {
            setOnClickListener { submit() }
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_create_database)
            setTextColor(getColor(R.color.on_accent)); textSize = 16f
        }
        body.addView(login, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
        body.addView(fingerprint, LinearLayout.LayoutParams(dp(72), dp(72)).apply { topMargin = dp(24) })
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
        binding.content.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showWelcome() {
        val welcome = WelcomeView(this,
            title = { name, back ->
                updateToolbar(repo.database.value, settingsForm = back)
                binding.header.text = name; binding.navigateBack.visibility = if (back) View.VISIBLE else View.GONE
            },
            pickKey = { callback -> keyCallback = callback; keyPicker.launch(arrayOf("*/*")) },
            pickFile = { callback -> welcomeFileCallback = callback; welcomeFilePicker.launch(arrayOf("*/*")) },
            create = { password, key, done -> lifecycleScope.launch {
                try { repo.create(password, key); enableQuickIfAvailable() } finally { key?.fill(0); done() }
            } },
            prepare = { uri, connection, done -> lifecycleScope.launch {
                var file: File? = null
                var handedOff = false
                try {
                    val source = withContext(Dispatchers.IO) {
                        val target = File.createTempFile("welcome-", ".kdbx", cacheDir)
                        file = target
                        if (uri != null) {
                            requireNotNull(contentResolver.openInputStream(uri)).use { input -> target.outputStream().use(input::copyTo) }
                            PreparedVault(target, Connection("saf", uri.toString()), documentDisplayPath(uri), canWrite(uri))
                        } else {
                            val cloud = requireNotNull(connection)
                            val metadata = repo.download(cloud, target)
                            PreparedVault(target, cloud, cloud.location, metadata.writable != false)
                        }
                    }
                    handedOff = true
                    done(source, null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val storage = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<StorageException>().firstOrNull()
                    val approval = generateSequence<Throwable>(error) { it.cause }.any { it is WebDavCertificateApprovalRequired }
                    val message = when {
                        approval -> L10n.text(this@MainActivity, R.string.ui_server_certificate_requires_approval_65)
                        storage?.kind == StorageError.AUTH -> L10n.text(this@MainActivity, R.string.ui_check_your_webdav_username_and_password_66)
                        storage?.kind == StorageError.NOT_FOUND -> L10n.text(this@MainActivity, R.string.ui_database_file_not_found_67)
                        storage?.kind == StorageError.PERMISSION -> L10n.text(this@MainActivity, R.string.ui_database_file_access_denied_68)
                        else -> L10n.text(this@MainActivity, R.string.ui_could_not_open_the_source_check_the_address_permissions_and_conne_69)
                    }
                    done(null, message)
                } finally { if (!handedOff) file?.delete() }
            } },
            providers = repo.providers,
            showDialog = { dialog(it) },
            open = { source, password, key, done -> lifecycleScope.launch {
                try {
                    repo.openExternal(source.file, password, key, source.connection.takeIf { source.writable })
                    enableQuickIfAvailable()
                } finally {
                    key?.fill(0)
                    done(if (repo.database.value == null) repo.message.value.ifBlank { L10n.text(this@MainActivity, R.string.ui_could_not_open_the_database_check_the_password_and_key_file_70) } else null)
                }
            } }
        )
        welcomeView = welcome
        binding.content.addView(welcome, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun updateToolbar(db: KeePassDatabase?, settingsForm: Boolean = false) {
        val settingsScreen = settingsForm || (db != null && model.panel != null)
        val screenColor = ContextCompat.getColor(this, R.color.settings_background)
        binding.root.setBackgroundColor(screenColor)
        (binding.header.parent as View).setBackgroundColor(screenColor)
        binding.content.setPadding(if (settingsScreen) 0 else dp(16), 0, if (settingsScreen) 0 else dp(16), 0)
        binding.header.setTextColor(ContextCompat.getColor(this, R.color.settings_text))
        binding.header.textSize = 22f
        binding.header.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val selectable = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true)
        binding.navigateBack.setBackgroundResource(selectable.resourceId)
        if (Build.VERSION.SDK_INT < 35) setLegacySystemBarColor(screenColor)
        val unlocked = db != null
        val draft = if (unlocked) model.editing else null
        val generator = if (unlocked) model.generator else null
        val editing = draft != null
        val entry = model.entry?.let { id -> db?.getEntryBy { uuid == id } }
        val searching = unlocked && model.searching && entry == null && !editing
        val folder = if (db != null) model.group?.takeIf { it != db.content.group.uuid }?.let { Kdbx.group(db, it) } else null
        val panel = model.panel
        val canNavigateUp = !searching && (autofillPicking || panel != null || editing || generator != null || entry != null || folder != null)
        binding.header.text = when {
            panel == Panel.SETTINGS -> L10n.text(this@MainActivity, R.string.ui_settings_71)
            panel == Panel.DATA -> L10n.text(this@MainActivity, R.string.ui_data_72)
            panel == Panel.IMPORT -> L10n.text(this@MainActivity, R.string.ui_import_73)
            panel == Panel.JOURNAL -> L10n.text(this@MainActivity, R.string.ui_log_74)
            generator != null -> getString(R.string.password_generator)
            draft != null -> draft.values["Title"]?.content.orEmpty().ifBlank { getString(if (draft.original == null) R.string.new_entry else R.string.edit_entry) }
            entry != null -> entry.fields.title?.content.orEmpty().ifBlank { getString(R.string.untitled_entry) }
            folder != null -> folder.name
            autofillPicking -> L10n.text(this, R.string.autofill_select_entry)
            else -> getString(R.string.app_name)
        }
        binding.header.visibility = if (searching) View.GONE else View.VISIBLE
        binding.headerSearch.visibility = if (searching) View.VISIBLE else View.GONE
        binding.closeSearch.visibility = if (searching) View.VISIBLE else View.GONE
        binding.clearSearch.visibility = if (searching) View.VISIBLE else View.GONE
        binding.navigateBack.visibility = if (canNavigateUp) View.VISIBLE else View.GONE
        binding.navigateBack.isEnabled = !model.saving.value && !model.importingDatabase.value
        binding.searchButton.visibility = if (panel == null && unlocked && !searching && entry == null && !editing) View.VISIBLE else View.GONE
        binding.editButton.visibility = if (generator != null || (entry != null && !searching && !editing)) View.VISIBLE else View.GONE
        binding.editButton.setImageResource(if (generator != null) R.drawable.ic_wand else R.drawable.ic_edit)
        binding.editButton.contentDescription = getString(if (generator != null) R.string.regenerate_password else R.string.edit_entry)
        binding.editButton.tooltipText = binding.editButton.contentDescription
        binding.saveButton.visibility = if (editing) View.VISIBLE else View.GONE
        binding.saveButton.isEnabled = !model.saving.value && (generator == null || generator.generated != null)
        binding.saveButton.alpha = if (binding.saveButton.isEnabled) 1f else 0.4f
        binding.saveButton.contentDescription = getString(if (generator != null) R.string.use_password else R.string.save_entry)
        binding.saveButton.tooltipText = binding.saveButton.contentDescription
        binding.menuButton.visibility = if (!autofillPicking && panel == null && unlocked && !searching && generator == null) View.VISIBLE else View.GONE
        binding.menuButton.isEnabled = !model.saving.value
        binding.menuButton.contentDescription = getString(if (entry == null) R.string.open_menu else R.string.open_entry_menu)
        updatingSearch = true
        if (binding.headerSearch.text.toString() != model.query) {
            binding.headerSearch.setText(model.query)
            binding.headerSearch.setSelection(binding.headerSearch.length())
        }
        updatingSearch = false
        if (!searching) binding.headerSearch.clearFocus()
    }
    private fun openSearch() {
        if (repo.database.value == null) return
        model.searching = true; model.entry = null
        render()
        binding.headerSearch.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).show(WindowInsetsCompat.Type.ime())
    }
    // API 35+ enforces transparent system bars; older Android still needs explicit colors.
    @Suppress("DEPRECATION")
    private fun setLegacySystemBarColor(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }
    private fun closeSearch() {
        model.searching = false; model.query = ""
        hideKeyboard(); render()
    }
    private fun hideKeyboard() { androidx.core.view.WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime()) }
    private fun setAddExpanded(expanded: Boolean) {
        addExpanded = expanded
        binding.addActions.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.addDismiss.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.addButton.rotation = if (expanded) 45f else 0f
        binding.addButton.setContentDescription(getString(if (expanded) R.string.close_add else R.string.add))
    }
    private fun entryRow(e: Entry, icons: VaultIcons) = Row(e.uuid, e.fields.title?.content.orEmpty().ifBlank { L10n.text(this@MainActivity, R.string.untitled_entry) }, e.fields.userName?.content.orEmpty(), false, icons.resolve(e))
    private fun showEntry(entry: Entry) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        orderedFields(entry.fields).filterNot {
            it.second.isEmpty() || it.first in setOf(ApplicationBinding.SIGNATURE, "Android Signature")
        }.forEach { (name, value) ->
            val row = EntryFieldView(this, fieldLabel(this, name), value, name == "Password" || value is EntryValue.Encrypted, ::copySecret,
                isUrl = name == "URL", isApplication = name == ApplicationBinding.PACKAGE)
            entryFieldViews += row
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (entry.tags.isNotEmpty()) {
            body.addView(label(L10n.text(this@MainActivity, R.string.ui_tags_2), 12f))
            body.addView(TagFlowLayout(this).apply {
                show(entry.tags)
                setPadding(0, 0, 0, dp(12))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        binding.content.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun changePasswordDialog(action: (String) -> Unit) {
        val password = field(L10n.text(this, R.string.ui_new_password_76), secret = true)
        val form = column().apply { addView(password.withPasswordVisibilityToggle()) }
        val d = dialog(AlertDialog.Builder(this).setTitle(R.string.ui_new_password_76).setView(form)
            .setNegativeButton(R.string.ui_cancel_82, null).setPositiveButton(R.string.ui_continue_83, null))
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = password.text.toString()
            if (value.length < 6) { password.error = getString(R.string.ui_at_least_6_characters_84); return@setOnClickListener }
            password.text.clear(); d.dismiss(); action(value)
        }
        d.setOnDismissListener { dialogs.remove(d); password.text.clear() }
    }
    private fun canWrite(uri: Uri) = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    private fun copySecret(value: String) {
        val manager = getSystemService(ClipboardManager::class.java)
        val token = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(getString(R.string.app_name), value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true); putString("keyra.token", token) }
        manager.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            if (manager.primaryClipDescription?.extras?.getString("keyra.token") == token) manager.clearPrimaryClip()
        }, repo.store.preferences.getInt("clipboardSeconds", 30) * 1000L)
    }
    private fun editEntry(original: Entry?) {
        val db = repo.database.value ?: return
        val parent = original?.let { db.getEntry { current -> current.uuid == it.uuid }?.first?.uuid }
            ?: model.group?.takeIf { Kdbx.group(db, it) != null } ?: db.content.group.uuid
        model.discardEditing()
        model.editing = EntryDraft(original, parent)
        model.searching = false; model.query = ""
        hideKeyboard(); render()
    }

    private fun showEntryEditor(draft: EntryDraft) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        orderedFields(draft.values, includeTitle = true).forEach { (key, value) ->
            val canGenerate = key == "Password" || (key !in standardEntryFields && value is EntryValue.Encrypted)
            val generate: (() -> Unit)? = if (canGenerate) ({ openPasswordGenerator(draft, key) }) else null
            val row = EditableEntryFieldView(this, key, value, { text ->
                if (model.editing === draft && repo.database.value != null) {
                    draft.update(key, text)
                    if (key == "Title") updateToolbar(repo.database.value)
                }
            }, generate)
            row.setEditingEnabled(!model.saving.value)
            editorFieldViews[key] = row
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(EntryTagsEditorView(this, draft).apply {
            setEditingEnabled(!model.saving.value)
        }, LinearLayout.LayoutParams(-1, -2))
        binding.content.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun saveEntryDraft() {
        val draft = model.editing ?: return
        if (draft.values["Title"]?.content.isNullOrBlank()) {
            editorFieldViews["Title"]?.showError(getString(R.string.title_required)); return
        }
        hideKeyboard(); model.saveEditing()
    }

    private fun closeEntryEditor() {
        val draft = model.editing ?: return
        fun discard() {
            if (model.editing !== draft) return
            model.discardEditing(); hideKeyboard(); render()
        }
        if (!draft.modified) { discard(); return }
        dialog(AlertDialog.Builder(this).setTitle(R.string.discard_changes).setMessage(R.string.discard_changes_message)
            .setNegativeButton(R.string.keep_editing, null).setPositiveButton(R.string.discard) { _, _ -> discard() })
    }

    private fun openPasswordGenerator(draft: EntryDraft, key: String) {
        if (model.editing !== draft || repo.database.value == null || model.saving.value) return
        val prefs = repo.store.preferences
        val options = PasswordOptions(
            prefs.getInt("passwordLength", 20).coerceIn(PasswordGenerator.MIN_LENGTH, PasswordGenerator.MAX_LENGTH),
            prefs.getBoolean("generatorLowercase", true), prefs.getBoolean("generatorUppercase", true),
            prefs.getBoolean("generatorDigits", true), prefs.getBoolean("generatorSymbols", true)
        )
        model.generator = PasswordGeneratorDraft(key, options)
        hideKeyboard(); render()
    }

    private fun showPasswordGenerator(draft: PasswordGeneratorDraft) {
        val view = PasswordGeneratorView(this, draft, ::copySecret) { updateToolbar(repo.database.value) }
        generatorView = view
        binding.content.addView(scroll(view), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun applyGeneratedPassword() {
        val draft = model.editing ?: return
        val generator = model.generator ?: return
        val value = generator.generated ?: return
        if (repo.database.value == null || generator.field !in draft.values) return
        draft.update(generator.field, value.content)
        generator.options()?.let { options ->
            repo.store.preferences.edit { putInt("passwordLength", options.length).putBoolean("generatorLowercase", options.lowercase).putBoolean("generatorUppercase", options.uppercase).putBoolean("generatorDigits", options.digits).putBoolean("generatorSymbols", options.symbols) }
        }
        generator.dispose(); model.generator = null
        hideKeyboard(); render()
    }

    private fun showEditorMenu(draft: EntryDraft) {
        val popup = PopupMenu(this, binding.menuButton, Gravity.END)
        intArrayOf(R.string.add_custom_field, R.string.field_protection, R.string.remove_custom_field).forEachIndexed { index, title ->
            popup.menu.add(0, index, index, title)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> addCustomField(draft)
                1 -> fieldProtection(draft)
                2 -> removeCustomField(draft)
            }
            true
        }
        mainMenu = popup; popup.show()
    }

    private fun addCustomField(draft: EntryDraft) {
        val body = column(); val name = field(getString(R.string.field_name))
        val protection = CheckBox(this).apply { setText(R.string.protected_field) }
        body.addView(name); body.addView(protection)
        val d = dialog(AlertDialog.Builder(this).setTitle(R.string.add_custom_field).setView(body)
            .setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null).setPositiveButton(L10n.text(this@MainActivity, R.string.add), null))
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (model.editing !== draft || repo.database.value == null) { d.dismiss(); return@setOnClickListener }
            val key = name.text.toString().trim()
            if (key.isBlank() || key in draft.values) { name.error = getString(R.string.field_name_error); return@setOnClickListener }
            draft.add(key, protection.isChecked); d.dismiss(); render()
        }
    }

    private fun fieldProtection(draft: EntryDraft) {
        val keys = orderedFields(draft.values, includeTitle = true).map { it.first }
        val choices = keys.map { it == "Password" || draft.values[it] is EntryValue.Encrypted }.toBooleanArray()
        dialog(AlertDialog.Builder(this).setTitle(R.string.field_protection)
            .setMultiChoiceItems(keys.map { fieldLabel(this, it) }.toTypedArray(), choices) { d, index, enabled ->
                choices[index] = enabled || keys[index] == "Password"
                if (!enabled && keys[index] == "Password") (d as AlertDialog).listView.setItemChecked(index, true)
            }
            .setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null).setPositiveButton(L10n.text(this@MainActivity, R.string.ui_done_86)) { _, _ ->
                if (model.editing === draft && repo.database.value != null) { keys.forEachIndexed { i, key -> draft.protect(key, choices[i]) }; render() }
            })
    }

    private fun removeCustomField(draft: EntryDraft) {
        val keys = draft.values.keys.filterNot { it in standardEntryFields }
        if (keys.isEmpty()) { toast(getString(R.string.no_custom_fields)); return }
        dialog(AlertDialog.Builder(this).setTitle(R.string.remove_custom_field).setItems(keys.toTypedArray()) { _, index ->
            if (model.editing === draft && repo.database.value != null) { draft.remove(keys[index]); render() }
        })
    }
    private fun editGroup(group: Group?) {
        val name = field(L10n.text(this@MainActivity, R.string.field_title), group?.name.orEmpty())
        dialog(AlertDialog.Builder(this).setTitle(if (group == null) L10n.text(this@MainActivity, R.string.ui_new_folder_88) else L10n.text(this@MainActivity, R.string.ui_folder_name_89)).setView(name).setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null).setPositiveButton(L10n.text(this@MainActivity, R.string.ui_save_90)) { _, _ ->
            val newName = name.text.toString().trim()
            if (newName.isNotBlank()) lifecycleScope.launch { repo.mutate { db -> db.modifyContent { copy(group = if (group == null) Kdbx.replaceGroup(this.group, model.group ?: this.group.uuid) { it.copy(groups = it.groups + Group(UUID.randomUUID(), newName)) } else Kdbx.replaceGroup(this.group, group.uuid) { it.copy(name = newName, times = (it.times ?: TimeData.create()).copy(lastModificationTime = Instant.now())) }) } } }
        })
    }
    private fun rowMenu(row: Row) {
        val id = row.id
        val actions = if (row.folder) arrayOf(L10n.text(this@MainActivity, R.string.ui_rename_91), L10n.text(this@MainActivity, R.string.move_entry), L10n.text(this@MainActivity, R.string.delete_entry)) else arrayOf(L10n.text(this@MainActivity, R.string.ui_open_94), L10n.text(this@MainActivity, R.string.move_entry), L10n.text(this@MainActivity, R.string.delete_entry))
        dialog(AlertDialog.Builder(this).setTitle(row.title).setItems(actions) { _, index -> when (index) {
            0 -> if (row.folder) repo.database.value?.let { editGroup(Kdbx.group(it, id)) } else { model.entry = id; render() }
            1 -> move(id, row.folder)
            2 -> remove(id, row.folder)
        } })
    }
    private fun move(id: UUID, isGroup: Boolean) {
        val db = repo.database.value ?: return
        val excluded = hashSetOf(id)
        if (isGroup) Kdbx.group(db, id)?.traverse { excluded += it.uuid }
        val groups = mutableListOf<Pair<String, UUID>>()
        fun walk(g: Group, path: String) { if (g.uuid !in excluded) groups += "$path${g.name}" to g.uuid; g.groups.forEach { walk(it, "$path${g.name}/") } }
        walk(db.content.group, "")
        dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_move_to_folder_95)).setItems(groups.map { it.first }.toTypedArray()) { _, index ->
            val destination = groups[index].second
            lifecycleScope.launch { repo.mutate { current ->
                if (isGroup) {
                    val group = Kdbx.group(current, id) ?: error("MISSING_GROUP")
                    Kdbx.removeGroup(current, id).modifyContent { copy(group = Kdbx.replaceGroup(this.group, destination) { it.copy(groups = it.groups + group.copy(previousParentGroup = current.getGroup { it.uuid == id }?.first?.uuid, times = (group.times ?: TimeData.create()).copy(locationChanged = Instant.now()))) }) }
                } else {
                    val entry = current.getEntryBy { uuid == id } ?: error("MISSING_ENTRY")
                    Kdbx.putEntry(Kdbx.removeEntry(current, id), destination, entry.copy(previousParentGroup = current.getEntry { it.uuid == id }?.first?.uuid, times = (entry.times ?: TimeData.create()).copy(locationChanged = Instant.now())))
                }
            } }
        })
    }
    private fun remove(id: UUID, isGroup: Boolean) {
        val db = repo.database.value ?: return
        if (id == db.content.meta.recycleBinUuid) { toast(L10n.text(this@MainActivity, R.string.ui_the_recycle_bin_folder_cannot_be_deleted_96)); return }
        val parent = if (isGroup) db.getGroup { it.uuid == id }?.first?.uuid else db.getEntry { it.uuid == id }?.first?.uuid
        val inBin = parent == db.content.meta.recycleBinUuid
        dialog(AlertDialog.Builder(this).setTitle(if (inBin) L10n.text(this@MainActivity, R.string.ui_delete_permanently_97) else L10n.text(this@MainActivity, R.string.ui_move_to_recycle_bin_98)).setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null).setPositiveButton(L10n.text(this@MainActivity, R.string.delete_entry)) { _, _ ->
            lifecycleScope.launch { repo.mutate { current ->
                val bin = current.content.meta.recycleBinUuid
                if (bin == null || inBin) {
                    val ids = mutableListOf(id)
                    if (isGroup) Kdbx.group(current, id)?.traverse { ids += it.uuid }
                    val stripped = if (isGroup) Kdbx.removeGroup(current, id) else Kdbx.removeEntry(current, id)
                    stripped.modifyContent { copy(deletedObjects = deletedObjects + ids.distinct().map { DeletedObject(it, Instant.now()) }) }
                } else if (isGroup) {
                    val g = Kdbx.group(current, id)!!
                    Kdbx.removeGroup(current, id).modifyContent { copy(group = Kdbx.replaceGroup(group, bin) { it.copy(groups = it.groups + g.copy(previousParentGroup = parent, times = (g.times ?: TimeData.create()).copy(locationChanged = Instant.now()))) }) }
                } else {
                    val e = current.getEntryBy { uuid == id }!!
                    Kdbx.putEntry(Kdbx.removeEntry(current, id), bin, e.copy(previousParentGroup = parent, times = (e.times ?: TimeData.create()).copy(locationChanged = Instant.now())))
                }
            }; model.entry = null; render() }
        })
    }
    private fun history(entry: Entry) {
        dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_entry_history_99)).setItems(entry.history.map { it.times?.lastModificationTime?.toString() ?: L10n.text(this@MainActivity, R.string.ui_no_date_100) }.toTypedArray()) { _, index ->
            val old = entry.history[index]
            val body = column(); old.fields.forEach { (name, value) ->
                body.addView(label("${fieldLabel(this, name)}: ${if (value is EntryValue.Encrypted || name == "Password") getString(R.string.hidden_value) else value.content}"))
                if (value is EntryValue.Encrypted || name == "Password") body.addView(button(L10n.text(this@MainActivity, R.string.ui_copy_101, fieldLabel(this, name))) { copySecret(value.content) })
            }
            dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_entry_version_102)).setView(scroll(body)).setNegativeButton(L10n.text(this@MainActivity, R.string.ui_close_103), null).setPositiveButton(L10n.text(this@MainActivity, R.string.ui_restore_104)) { _, _ -> lifecycleScope.launch { repo.mutate { db -> Kdbx.putEntry(db, db.getEntry { it.uuid == entry.uuid }!!.first.uuid, Kdbx.edit(entry, old.fields, old.tags)) } } })
        })
    }
    private fun menu() {
        val db = repo.database.value ?: return
        setAddExpanded(false)
        mainMenu?.dismiss()
        val draft = model.editing
        if (draft != null) { showEditorMenu(draft); return }
        val entry = model.entry?.let { id -> db.getEntryBy { uuid == id } }
        if (entry != null) {
            val popup = PopupMenu(this, binding.menuButton, Gravity.END)
            val actions = intArrayOf(R.string.entry_history, R.string.move_entry, R.string.delete_entry)
            actions.forEachIndexed { index, title -> popup.menu.add(0, index, index, title) }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> history(entry)
                    1 -> move(entry.uuid, false)
                    2 -> remove(entry.uuid, false)
                }
                true
            }
            mainMenu = popup
            popup.show()
            return
        }
        val actions = arrayOf(L10n.text(this@MainActivity, R.string.ui_settings_71), L10n.text(this@MainActivity, R.string.ui_data_72), L10n.text(this@MainActivity, R.string.ui_lock_105))
        val popup = PopupMenu(this, binding.menuButton, Gravity.END)
        actions.forEachIndexed { index, title -> popup.menu.add(0, index, index, title) }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> { model.panel = Panel.SETTINGS; render() }
                1 -> { model.panel = Panel.DATA; render() }
                2 -> repo.lock()
            }
            true
        }
        mainMenu = popup
        popup.show()
    }
    private fun export() { exportPicker.launch(repo.store.local.name) }
    private fun synchronization(): LinearLayout {
        val body = settingsList()
        var connection = runCatching { repo.providers.connection() }.getOrNull()
        var saveWebdav: (() -> Job)? = null
        var webdavUi: WebDavSettingsUi? = null
        val kinds = listOf("off", "saf", "webdav")
        val names = listOf(L10n.text(this@MainActivity, R.string.ui_off_106), L10n.text(this@MainActivity, R.string.ui_file_107), getString(R.string.webdav_name))
        val selectedKind = model.syncSourceKind?.takeIf { it in kinds } ?: if (repo.store.preferences.getBoolean("syncEnabled", true)) connection?.kind ?: "off" else "off"
        model.syncSourceKind = selectedKind
        val sourcePicker = settingsAction(L10n.text(this@MainActivity, R.string.ui_sync_108), names[kinds.indexOf(selectedKind).coerceAtLeast(0)]) { }
        body.addView(sourcePicker)
        val parameters = settingsList()
        body.addView(parameters, LinearLayout.LayoutParams(-1, -2))
        fun showParameters() {
            clearSyncSecrets()
            parameters.removeAllViews()
            parameters.visibility = if (selectedKind == "off") View.GONE else View.VISIBLE
            val current = connection?.takeIf { it.kind == selectedKind }
            when (selectedKind) {
                "saf" -> {
                    val summary = if (current == null) L10n.text(this@MainActivity, R.string.ui_no_file_selected_109) else L10n.text(this@MainActivity, R.string.ui_getting_file_path_110)
                    val fileRow = settingsAction(L10n.text(this@MainActivity, R.string.ui_file_107), summary) {
                        sourceSelection = true; filePicker.launch(arrayOf("*/*"))
                    }.apply {
                        if (current == null) text = settingsText(L10n.text(this@MainActivity, R.string.ui_file_107), summary, R.color.settings_error)
                    }
                    parameters.addView(fileRow)
                    parameters.addView(panelInfo(L10n.text(this@MainActivity, R.string.ui_choose_a_database_on_your_device_or_in_cloud_storage_such_as_goog_111)))
                    if (current != null) lifecycleScope.launch {
                        val path = withContext(Dispatchers.IO) {
                            runCatching { documentDisplayPath(current.location.toUri()) }.getOrDefault(current.location)
                        }
                        if (fileRow.isAttachedToWindow) fileRow.text = settingsText(L10n.text(this@MainActivity, R.string.ui_file_107), path)
                    }
                }
                "webdav" -> {
                    val url = field("https://server/path/database.kdbx", current?.location.orEmpty()).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        isSingleLine = true
                    }
                    val user = field(L10n.text(this@MainActivity, R.string.field_login), current?.user.orEmpty())
                    val password = field(L10n.text(this@MainActivity, R.string.field_password), current?.password.orEmpty(), secret = true)
                    listOf(url, user, password).forEach {
                        it.background = null; it.textSize = 16f
                        it.minimumHeight = dp(60)
                        it.setPadding(dp(16), dp(14), dp(16), dp(14))
                        it.setTextColor(ContextCompat.getColor(this, R.color.settings_text))
                        it.setHintTextColor(ContextCompat.getColor(this, R.color.settings_secondary))
                    }
                    syncSecretFields += password
                    val urlRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                    val browse = ImageButton(this).apply {
                        setImageResource(R.drawable.ic_open_database)
                        imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.settings_control))
                        background = null
                        contentDescription = L10n.text(this@MainActivity, R.string.dav_choose_file)
                        setOnClickListener { webdavUi?.browse() }
                    }
                    urlRow.addView(url, LinearLayout.LayoutParams(0, -2, 1f))
                    urlRow.addView(browse, LinearLayout.LayoutParams(dp(48), dp(60)))
                    parameters.addView(urlRow); parameters.addView(user); parameters.addView(password)
                    var savedPassword = current?.password.orEmpty()
                    fun persist(): Job {
                        val source = Connection("webdav", url.text.toString().trim(), user.text.toString(), savedPassword)
                        connection = source
                        return repo.saveWebDavSettings(source).also { job ->
                            job.invokeOnCompletion { lifecycleScope.launch { refreshSynchronization?.invoke() } }
                        }
                    }
                    val ui = WebDavSettingsUi(this, repo.providers,
                        source = { Connection("webdav", url.text.toString().trim(), user.text.toString(), savedPassword) },
                        showDialog = { dialog(it) }, selected = { url.setText(it); url.setSelection(url.length()) },
                        browsingChanged = { busy -> browse.isEnabled = !busy; browse.alpha = if (busy) 0.4f else 1f })
                    webdavUi = ui
                    parameters.addView(ui.certificateView)
                    fun updateUrl() {
                        val parsed = url.text.toString().trim().toHttpUrlOrNull()
                        browse.visibility = if (parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty()) View.VISIBLE else View.GONE
                        ui.changed()
                    }
                    urlRow.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) { updateUrl() }
                        override fun onViewDetachedFromWindow(view: View) { ui.dispose() }
                    })
                    updateUrl()
                    saveWebdav = { persist() }
                    url.doAfterTextChanged { if (!clearingSyncSecrets) { persist(); updateUrl() } }
                    user.doAfterTextChanged { if (!clearingSyncSecrets) persist() }
                    password.doAfterTextChanged {
                        if (!clearingSyncSecrets) { savedPassword = it.toString(); persist() }
                    }
                }
            }
        }
        showParameters()
        val common = settingsList().apply { visibility = if (selectedKind == "off") View.GONE else View.VISIBLE }
        body.addView(common)
        val actions = settingsList()
        common.addView(actions)
        fun refreshActions() {
            actions.removeAllViews()
            if (selectedKind == "off") return
            val summary = if (connection?.kind != selectedKind || !repo.store.preferences.getBoolean("syncEnabled", true)) L10n.text(this@MainActivity, R.string.ui_configure_the_selected_source_to_enable_sync_113) else synchronizationStatusText()
            actions.addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_sync_now_114), summary) { lifecycleScope.launch { saveWebdav?.invoke()?.join(); repo.sync() } }.apply {
                isEnabled = !repo.syncing.value && (selectedKind == "webdav" || (connection?.kind == selectedKind && repo.store.preferences.getBoolean("syncEnabled", true)))
                alpha = if (isEnabled || repo.syncing.value) 1f else 0.4f
            })
            synchronizationActions(actions)
            if (selectedKind != "webdav" && !repo.store.preferences.getBoolean("syncEnabled", true)) {
                fun disable(view: View) {
                    view.isEnabled = false
                    if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
                }
                disable(actions)
            }
        }
        refreshActions()
        refreshSynchronization = { refreshActions(); webdavUi?.refresh() }
        sourcePicker.setOnClickListener {
            dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_sync_108))
                .setSingleChoiceItems(names.toTypedArray(), kinds.indexOf(selectedKind)) { dialog, position ->
                    val kind = kinds[position]
                    dialog.dismiss()
                    if (kind != selectedKind) {
                        sourcePicker.isEnabled = false
                        lifecycleScope.launch {
                            try {
                                // A newly selected source stays paused until its parameters are saved.
                                repo.setSyncEnabled(kind != "off" && kind == connection?.kind)
                                model.syncSourceKind = kind
                                if (model.panel == Panel.DATA && repo.database.value != null) render()
                                if (kind != "off" && kind == connection?.kind) repo.sync()
                            } catch (_: Exception) {
                                toast(L10n.text(this@MainActivity, R.string.ui_could_not_change_sync_settings_115))
                                sourcePicker.isEnabled = true
                            }
                        }
                    }
                }.setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null))
        }
        return body
    }
    private fun synchronizationActions(body: LinearLayout) {
        val source = runCatching { repo.providers.connection() }.getOrNull()
        if (source?.kind == "webdav" && model.panel != Panel.DATA) repo.providers.certificates.pendingFor(source.location)?.let { challenge ->
            body.addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_review_server_certificate_116), L10n.text(this@MainActivity, R.string.ui_certificate_approval_required_117)) {
                showCertificateWarning(challenge, force = true)
            })
        }
        if (repo.status.value == SyncStatus.APPROVAL_REQUIRED) body.addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_approve_upload_118)) {
            dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_upload_database_119)).setMessage(L10n.text(this@MainActivity, R.string.ui_this_storage_provider_does_not_guarantee_conditional_writes_uploa_120))
                .setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null).setPositiveButton(L10n.text(this@MainActivity, R.string.ui_upload_121)) { _, _ -> lifecycleScope.launch { repo.sync(true) } })
        })
        if (repo.store.preferences.getBoolean("conflict", false)) {
            body.addView(label(repo.store.preferences.getString("issues", L10n.text(this@MainActivity, R.string.ui_upload_result_unknown_122)).orEmpty(), 12f))
            body.addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_keep_local_versions_of_conflicting_items_123)) { lifecycleScope.launch { repo.resolve(false) } })
            body.addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_keep_remote_versions_of_conflicting_items_124)) { lifecycleScope.launch { repo.resolve(true) } })
            body.addView(label(L10n.text(this@MainActivity, R.string.ui_independent_changes_are_merged_choose_which_versions_to_keep_for__125)))
        }
    }
    private fun showCertificateWarning(challenge: WebDavCertificateChallenge, force: Boolean = false) {
        if (!force && lastCertificatePrompt == challenge.identity) return
        lastCertificatePrompt = challenge.identity
        val certificate = challenge.certificate
        val dates = java.text.DateFormat.getDateTimeInstance()
        val warning = if (challenge.replacing) L10n.text(this@MainActivity, R.string.ui_the_certificate_differs_from_the_one_previously_accepted_the_serv_126) else L10n.text(this@MainActivity, R.string.ui_the_server_certificate_is_not_verified_by_a_trusted_certificate_a_127)
        dialog(AlertDialog.Builder(this).setTitle(if (challenge.replacing) L10n.text(this@MainActivity, R.string.ui_webdav_certificate_changed_128) else L10n.text(this@MainActivity, R.string.ui_untrusted_webdav_certificate_129))
            .setMessage(L10n.text(this@MainActivity, R.string.ui_server_subject_issuer_valid_sha_256_verify_the_fingerprint_with_t_130, warning, challenge.origin, certificate.subjectX500Principal.name, certificate.issuerX500Principal.name, dates.format(certificate.notBefore), dates.format(certificate.notAfter), challenge.fingerprint))
            .setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null)
            .setPositiveButton(L10n.text(this@MainActivity, R.string.ui_accept_certificate_131)) { _, _ -> lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { repo.providers.certificates.accept(challenge) }
                    if (repo.database.value != null) repo.sync()
                    else toast(L10n.text(this@MainActivity, R.string.ui_certificate_saved_try_opening_the_database_again_132))
                } catch (_: Exception) { toast(L10n.text(this@MainActivity, R.string.ui_could_not_accept_the_certificate_check_its_validity_dates_133)) }
            } })
    }
    private fun biometric(cipher: Cipher, action: (Cipher) -> Unit) {
        if (biometricActive) return
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                biometricActive = false; result.cryptoObject?.cipher?.let(action)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                biometricActive = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) toast(errString.toString())
                if (model.panel == Panel.SETTINGS && repo.database.value != null) render()
            }
        })
        biometric = prompt
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(L10n.text(this@MainActivity, R.string.ui_unlock_keyra_134)).setSubtitle(L10n.text(this@MainActivity, R.string.ui_biometrics_or_device_credentials_135))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build()
        biometricActive = true
        try { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) }
        catch (error: Exception) { biometricActive = false; throw error }
    }
    private fun systemUnlockAvailable() = androidx.biometric.BiometricManager.from(this)
        .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    private fun enableQuickIfAvailable() {
        if (repo.database.value == null || repo.secrets.has("quick") || repo.store.preferences.getBoolean("quickDisabled", false)) return
        if (systemUnlockAvailable()) enableQuick()
    }
    private fun enableQuick() {
        runCatching { repo.secrets.encryptCipher(true) }.onSuccess { cipher -> biometric(cipher) { authorized ->
            runCatching { val material = repo.quickMaterial(); try { repo.secrets.save("quick", authorized, material) } finally { material.fill(0) } }
                .onSuccess {
                    repo.store.preferences.edit { putBoolean("quickDisabled", false) }
                    toast(L10n.text(this@MainActivity, R.string.ui_quick_unlock_enabled_136))
                    if (model.panel == Panel.SETTINGS) render()
                }.onFailure {
                    toast(L10n.text(this@MainActivity, R.string.ui_could_not_protect_the_unlock_key_set_up_a_device_screen_lock_137))
                    if (model.panel == Panel.SETTINGS) render()
                }
        } }.onFailure {
            toast(L10n.text(this@MainActivity, R.string.ui_set_up_a_device_screen_lock_first_138))
            if (model.panel == Panel.SETTINGS) render()
        }
    }
    private fun quickUnlock() {
        if (biometricActive || repo.busy.value || repo.database.value != null) return
        runCatching { repo.secrets.decryptCipher("quick", true) }.onSuccess { cipher -> biometric(cipher) { authorized ->
            runCatching { repo.secrets.read("quick", authorized) }.onSuccess { bytes -> lifecycleScope.launch { repo.quickUnlock(bytes); repo.sync() } }
                .onFailure { repo.secrets.remove("quick"); toast(L10n.text(this@MainActivity, R.string.ui_unlock_the_database_with_your_password_139)) }
        } }.onFailure { repo.secrets.remove("quick"); toast(L10n.text(this@MainActivity, R.string.ui_unlock_key_unavailable_use_your_database_password_140)) }
    }
    private fun panelInfo(value: String) = label(value, 13f).apply {
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }
    private fun panelSwitch(title: String, summary: String, checked: Boolean, changed: (Boolean) -> Unit) = androidx.appcompat.widget.SwitchCompat(this).apply {
        text = settingsText(title, summary); textSize = 16f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_text))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(80); switchPadding = dp(20)
        gravity = Gravity.CENTER_VERTICAL; background = null
        thumbTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.settings_surface))
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ContextCompat.getColor(this@MainActivity, R.color.settings_control), ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
        )
        isChecked = checked
        setOnCheckedChangeListener { _, active -> changed(active) }
    }
    private fun showJournalForm() {
        val journal = JournalView(this)
        binding.content.addView(journal, LinearLayout.LayoutParams(-1, 0, 1f))
        journalJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.store.journalRevision.collectLatest {
                    val entries = withContext(Dispatchers.IO) { runCatching { repo.store.journalEntries() }.getOrNull() }
                    if (entries != null) journal.show(entries)
                    else toast(L10n.text(this@MainActivity, R.string.ui_could_not_read_the_log_141))
                }
            }
        }
    }

    private fun showImportForm() {
        val draft = model.importDraft
        if (draft == null) { model.panel = Panel.DATA; render(); return }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(24), dp(14), dp(32))
        }
        fun card(title: String) = settingsCard(body, title, topPadding = 16)
        val fileName = panelInfo(L10n.text(this@MainActivity, R.string.ui_getting_file_path_110))
        card(L10n.text(this@MainActivity, R.string.ui_database_to_import_142)).addView(fileName)
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { documentDisplayPath(draft.uri) }.getOrDefault(draft.uri.toString()) }
            if (fileName.isAttachedToWindow) fileName.text = path
        }
        card(L10n.text(this@MainActivity, R.string.ui_database_access_143)).apply {
            addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_key_file_144), if (draft.key == null) L10n.text(this@MainActivity, R.string.ui_not_selected_optional_145) else L10n.text(this@MainActivity, R.string.ui_key_file_selected_79)) {
                keyCallback = { bytes ->
                    if (model.importDraft !== draft || model.importingDatabase.value) bytes?.fill(0)
                    else if (bytes != null) { draft.selectKey(bytes); render() }
                }
                keyPicker.launch(arrayOf("*/*"))
            })
            val password = field(L10n.text(this@MainActivity, R.string.field_password), draft.passwordText(), secret = true).apply {
                isSingleLine = true; maskPasswordInput(); background = null
                minimumHeight = dp(64)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_text))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_secondary))
            }
            importPassword = password
            password.doAfterTextChanged { if (importPassword === password && model.importDraft === draft) draft.updatePassword(it.toString()) }
            addView(password.withPasswordVisibilityToggle(), LinearLayout.LayoutParams(-1, -2))
        }
        if (draft.error.isNotBlank()) body.addView(panelInfo(draft.error).apply { setTextColor(ContextCompat.getColor(this@MainActivity, R.color.settings_error)) })
        body.addView(button(if (model.importingDatabase.value) L10n.text(this@MainActivity, R.string.ui_importing_146) else L10n.text(this@MainActivity, R.string.ui_import_147)) {
            if (draft.passwordText().isEmpty()) { importPassword?.error = L10n.text(this@MainActivity, R.string.ui_enter_a_password_148); return@button }
            hideKeyboard(); model.startImport()
        }.apply {
            backgroundTintList = null; setBackgroundResource(R.drawable.bg_create_database)
            setTextColor(getColor(R.color.on_accent)); textSize = 16f
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(24) })
        if (model.importingDatabase.value) {
            fun disable(view: View) {
                view.isEnabled = false
                if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
            }
            disable(body)
        }
        binding.content.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showPanel() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(32))
        }
        fun group(name: String) = settingsCard(body, name)
        if (model.panel == Panel.SETTINGS) {
            group(L10n.text(this@MainActivity, R.string.ui_appearance_149)).apply {
                addView(settingsAction(getString(R.string.ui_app_theme_150), resources.getStringArray(R.array.app_theme_options)[ApplicationTheme.selectedIndex(this@MainActivity)]) {
                    dialog(AlertDialog.Builder(this@MainActivity).setTitle(R.string.ui_app_theme_150)
                        .setSingleChoiceItems(R.array.app_theme_options, ApplicationTheme.selectedIndex(this@MainActivity)) { dialog, index ->
                            dialog.dismiss()
                            ApplicationTheme.select(this@MainActivity, index)
                        }.setNegativeButton(R.string.ui_cancel_82, null))
                })
                addView(settingsAction(getString(R.string.ui_app_language_297), resources.getStringArray(R.array.app_language_options)[ApplicationLanguage.selectedIndex(this@MainActivity)]) {
                    dialog(AlertDialog.Builder(this@MainActivity).setTitle(R.string.ui_app_language_297)
                        .setSingleChoiceItems(R.array.app_language_options, ApplicationLanguage.selectedIndex(this@MainActivity)) { dialog, index ->
                            dialog.dismiss()
                            ApplicationLanguage.select(this@MainActivity, index)
                        }.setNegativeButton(R.string.ui_cancel_82, null))
                })
            }
            group(L10n.text(this@MainActivity, R.string.ui_security_151)).apply {
                preference(this, L10n.text(this@MainActivity, R.string.ui_clear_clipboard_seconds_152), "clipboardSeconds", intArrayOf(15, 30, 60), 30)
                preference(this, L10n.text(this@MainActivity, R.string.ui_lock_after_leaving_seconds_153), "lockSeconds", intArrayOf(0, 15, 60, 300), 60)
            }
            group(L10n.text(this@MainActivity, R.string.ui_integration_154)).apply {
                val androidAutofill = settingsAction(L10n.text(this@MainActivity, R.string.ui_android_autofill_155), "") {
                    try { startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, "package:$packageName".toUri())) }
                    catch (_: ActivityNotFoundException) { toast(L10n.text(this@MainActivity, R.string.autofill_settings_unavailable)) }
                }
                addView(androidAutofill)
                val chromeAutofill = settingsAction(L10n.text(this@MainActivity, R.string.chrome_autofill), L10n.text(this@MainActivity, R.string.autofill_checking)) {
                    try { startActivity(ChromeAutofill.settingsIntent()) }
                    catch (_: ActivityNotFoundException) { toast(L10n.text(this@MainActivity, R.string.chrome_autofill_manual)) }
                }
                addView(chromeAutofill)
                addView(panelInfo(L10n.text(this@MainActivity, R.string.chrome_autofill_help)))
                refreshAutofill = {
                    val enabled = getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
                    androidAutofill.text = settingsText(L10n.text(this@MainActivity, R.string.ui_android_autofill_155),
                        L10n.text(this@MainActivity, if (enabled) R.string.autofill_keyra_enabled else R.string.autofill_keyra_disabled))
                    autofillStatusJob?.cancel()
                    autofillStatusJob = lifecycleScope.launch {
                        val state = withContext(Dispatchers.IO) { ChromeAutofill.state(this@MainActivity) }
                        val message = when (state) {
                            ChromeAutofill.State.ENABLED -> R.string.chrome_autofill_enabled
                            ChromeAutofill.State.DISABLED -> R.string.chrome_autofill_disabled
                            ChromeAutofill.State.UNAVAILABLE -> R.string.chrome_autofill_unknown
                            ChromeAutofill.State.NOT_INSTALLED -> R.string.chrome_not_installed
                        }
                        chromeAutofill.text = settingsText(L10n.text(this@MainActivity, R.string.chrome_autofill), L10n.text(this@MainActivity, message))
                        chromeAutofill.isEnabled = state != ChromeAutofill.State.NOT_INSTALLED
                    }
                }
                refreshAutofill?.invoke()
                addView(panelSwitch(L10n.text(this@MainActivity, R.string.ui_quick_unlock_157), L10n.text(this@MainActivity, R.string.ui_biometrics_or_device_screen_lock_158), repo.secrets.has("quick")) { enabled ->
                    if (enabled) enableQuick()
                    else {
                        repo.secrets.remove("quick")
                        repo.store.preferences.edit { putBoolean("quickDisabled", true) }
                    }
                })
            }
            group(getString(R.string.about_title)).addView(settingsAction(getString(R.string.about_title), getString(R.string.app_developer_name)) {
                showAppInfo(::dialog)
            })
        } else {
            group(L10n.text(this@MainActivity, R.string.ui_data_72)).apply {
                addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_import_73), L10n.text(this@MainActivity, R.string.ui_merge_entries_from_a_kdbx_file_159)) { importing = true; sourceSelection = false; filePicker.launch(arrayOf("*/*")) })
                addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_export_160), L10n.text(this@MainActivity, R.string.ui_save_the_database_as_a_kdbx_file_161)) { export() })
                addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_export_key_162), L10n.text(this@MainActivity, R.string.ui_save_the_access_key_to_a_file_163)) { exportStoredKey() }.apply {
                    visibility = if (runCatching { repo.secrets.getVaultKeyfile()?.let { it.fill(0); true } ?: false }.getOrDefault(false)) View.VISIBLE else View.GONE
                })
            }
            group(L10n.text(this@MainActivity, R.string.ui_security_151)).addView(settingsAction(L10n.text(this@MainActivity, R.string.field_password), L10n.text(this@MainActivity, R.string.ui_change_the_database_password_164)) {
                changePasswordDialog { password ->
                    lifecycleScope.launch {
                        var key: ByteArray? = null
                        try {
                            key = withContext(Dispatchers.IO) { repo.secrets.getVaultKeyfile() }
                            repo.changePassword(password, key)
                            if (repo.message.value.isEmpty()) toast(L10n.text(this@MainActivity, R.string.ui_password_changed_reconnect_the_source_using_the_new_password_165))
                        } catch (_: Exception) { toast(L10n.text(this@MainActivity, R.string.ui_could_not_change_the_database_password_166)) }
                        finally { key?.fill(0) }
                    }
                }
            })
            group(L10n.text(this@MainActivity, R.string.ui_log_74)).addView(settingsAction(L10n.text(this@MainActivity, R.string.ui_log_74), L10n.text(this@MainActivity, R.string.ui_entry_and_folder_changes_and_sync_results_167)) {
                model.panel = Panel.JOURNAL; render()
            })
            group(L10n.text(this@MainActivity, R.string.ui_sync_108)).addView(synchronization())
        }
        binding.content.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun preference(body: LinearLayout, title: String, key: String, values: IntArray, default: Int) {
        fun valueText(value: Int) = when (key) {
            "lockSeconds" -> if (value == 0) L10n.text(this@MainActivity, R.string.ui_immediately_after_leaving_168) else L10n.text(this@MainActivity, R.string.ui_after_s_169, value)
            "clipboardSeconds" -> L10n.text(this@MainActivity, R.string.ui_s_170, value)
            else -> value.toString()
        }
        val selected = repo.store.preferences.getInt(key, default)
        val caption = when (key) {
            "clipboardSeconds" -> L10n.text(this@MainActivity, R.string.ui_clear_clipboard_172)
            "lockSeconds" -> L10n.text(this@MainActivity, R.string.ui_lock_after_leaving_173)
            else -> title
        }
        lateinit var row: Button
        row = settingsAction(caption, valueText(selected)) {
            val current = repo.store.preferences.getInt(key, default)
            dialog(AlertDialog.Builder(this).setTitle(caption)
                .setSingleChoiceItems(values.map(::valueText).toTypedArray(), values.indexOf(current)) { dialog, index ->
                    repo.store.preferences.edit { putInt(key, values[index]) }
                    row.text = settingsText(caption, valueText(values[index]))
                    dialog.dismiss()
                }.setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82), null))
        }
        body.addView(row)
    }
    private fun startKeyExport(bytes: ByteArray) {
        try {
            val xml = Kdbx.exportKeyfile(bytes)
            keyExportBytes?.fill(0); keyExportBytes = xml
            try { keyExportPicker.launch("Keyra.keyx") }
            catch (e: Exception) {
                xml.fill(0); keyExportBytes = null
                throw e
            }
        } finally { bytes.fill(0) }
    }
    private fun exportStoredKey() {
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { repo.secrets.getVaultKeyfile() }
                if (bytes == null) { toast(L10n.text(this@MainActivity, R.string.ui_no_key_file_is_stored_175)); return@launch }
                if (repo.database.value == null) { bytes.fill(0); return@launch }
                startKeyExport(bytes)
            } catch (_: Exception) { toast(L10n.text(this@MainActivity, R.string.ui_could_not_export_the_key_file_176)) }
        }
    }
    private fun chooseAutofill(token: String) {
        val target = FillRequests.get(token) ?: run { finish(); return }
        if (runCatching { packageCertificate(packageManager, target.packageName) }.getOrNull() != target.certificate) { finish(); return }
        val db = repo.database.value ?: return
        if (!target.chooseInApp) {
            val candidates = if (target.domain == null || AutofillResponses.trustedBrowser(repo.store.preferences, target))
                AutofillResponses.candidates(db, target) else emptyList()
            val response = AutofillResponses.entries(this, target, candidates, VaultIcons(db.content.meta.customIcons))
            FillRequests.remove(token)
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
            finish()
            return
        }
        val prefs = repo.store.preferences
        if (target.domain != null && prefs.getString("browser.${target.packageName}", null) != target.certificate) {
            dialog(AlertDialog.Builder(this).setTitle(L10n.text(this@MainActivity, R.string.ui_trust_this_browser_177))
                .setMessage(L10n.text(this@MainActivity, R.string.ui_certificate_this_app_reports_the_website_address_allow_only_brows_178, target.packageName, target.certificate, target.domain))
                .setNegativeButton(L10n.text(this@MainActivity, R.string.ui_cancel_82)) { _, _ -> finish() }
                .setPositiveButton(L10n.text(this@MainActivity, R.string.ui_trust_this_certificate_179)) { _, _ -> prefs.edit { putString("browser.${target.packageName}", target.certificate) }; chooseAutofill(token) })
            return
        }
        showAutofillPicker(token)
    }
    private fun showAutofillPicker(token: String) {
        model.autofillPickerToken = token
        model.group = null; model.entry = null; model.query = ""; model.searching = true
        render()
        binding.headerSearch.requestFocus()
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).show(WindowInsetsCompat.Type.ime())
    }
    private fun selectAutofillEntry(entry: Entry) {
        if (model.linkingAutofill) return
        val token = model.autofillPickerToken ?: return
        val target = FillRequests.get(token) ?: run { finish(); return }
        if (target.domain != null) {
            selectWebsiteAutofillEntry(token, target, entry)
            return
        }
        if (ApplicationBinding.trusted(entry, target.packageName, target.certificate)) {
            finishAutofill(token, entry)
            return
        }
        hideKeyboard()
        model.linkingAutofill = true
        lifecycleScope.launch {
            try {
                if (FillRequests.get(token) == null || runCatching { packageCertificate(packageManager, target.packageName) }.getOrNull() != target.certificate) {
                    finish(); return@launch
                }
                repo.mutate { current ->
                    val (parent, latest) = current.getEntry { it.uuid == entry.uuid } ?: error("ENTRY_NO_LONGER_EXISTS")
                    Kdbx.putEntry(current, parent.uuid, Kdbx.edit(latest,
                        ApplicationBinding.fields(latest, target.packageName, target.certificate)))
                }
                val saved = repo.database.value?.getEntryBy { uuid == entry.uuid }
                if (repo.message.value.isEmpty() && saved != null && ApplicationBinding.trusted(saved, target.packageName, target.certificate)) {
                    finishAutofill(token, saved)
                }
            } finally { model.linkingAutofill = false }
        }
    }
    private fun selectWebsiteAutofillEntry(token: String, target: FillTarget, entry: Entry) {
        val url = AutofillResponses.websiteUrl(target) ?: return
        if (!AutofillResponses.trustedBrowser(repo.store.preferences, target)) return
        hideKeyboard()
        model.linkingAutofill = true
        lifecycleScope.launch {
            try {
                if (FillRequests.get(token) == null || runCatching { packageCertificate(packageManager, target.packageName) }.getOrNull() != target.certificate) {
                    finish(); return@launch
                }
                repo.mutate { current ->
                    val (parent, latest) = current.getEntry { it.uuid == entry.uuid } ?: error("ENTRY_NO_LONGER_EXISTS")
                    val value = if (latest.fields.url is EntryValue.Encrypted)
                        EntryValue.Encrypted(app.keemobile.kotpass.cryptography.EncryptedValue.fromString(url))
                    else EntryValue.Plain(url)
                    Kdbx.putEntry(current, parent.uuid, Kdbx.edit(latest, latest.fields + ("URL" to value)))
                }
                val saved = repo.database.value?.getEntryBy { uuid == entry.uuid }
                if (repo.message.value.isEmpty() && saved != null && saved.fields.url?.content == url) {
                    finishAutofill(token, saved)
                }
            } finally { model.linkingAutofill = false }
        }
    }
    @Suppress("DEPRECATION")
    private fun finishAutofill(token: String, entry: Entry) {
        val target = FillRequests.get(token) ?: return
        val db = repo.database.value ?: return
        if (runCatching { packageCertificate(packageManager, target.packageName) }.getOrNull() != target.certificate) return
        val dataset = AutofillResponses.dataset(this, target, entry, VaultIcons(db.content.meta.customIcons))
        val response: android.os.Parcelable = if (target.chooseInApp) dataset
            else android.service.autofill.FillResponse.Builder().addDataset(dataset).build()
        FillRequests.remove(token)
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response)); finish()
    }
}
