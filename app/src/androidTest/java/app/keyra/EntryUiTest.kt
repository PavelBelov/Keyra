package app.keyra

import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ToggleButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntryBy
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keyra.core.Kdbx
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual Android views, synthetic vault and separate app.keyra.qa package. */
@RunWith(AndroidJUnit4::class)
class EntryUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var app: KeyraApp
    private lateinit var originalRepository: VaultRepository
    private lateinit var repository: VaultRepository
    private val entryId = UUID.randomUUID()
    private val folderId = UUID.randomUUID()
    private val password = "Abc19!?"
    private var originalTheme: Int? = null

    @Before fun setUp() {
        assumeTrue("UI fixtures may run only in the isolated QA package", context.packageName.endsWith(".qa"))
        InstrumentationRegistry.getArguments().getString("keyraThemeIndex")?.toInt()?.let { index ->
            originalTheme = ApplicationTheme.selectedIndex(context)
            instrumentation.runOnMainSync { ApplicationTheme.select(context, index) }
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        app = context.applicationContext as KeyraApp
        originalRepository = app.repository
        val fixtureId = UUID.randomUUID().toString()
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "ui-$fixtureId").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("ui-$fixtureId-$name", mode)
        }
        repository = VaultRepository(sandbox)
        repository.store.preferences.edit().putInt("lockSeconds", 300).commit()
        val base = Kdbx.create(Kdbx.credentials("qa-fixture-password")) as KeePassDatabase.Ver4x
        val quick = base.copy(header = base.header.copy(kdfParameters = KdfParameters.Aes(2UL, base.header.masterSeed)))
        val entry = Entry(entryId, fields = EntryFields.of(
            "Custom first" to EntryValue.Plain("custom-value"),
            "Notes" to EntryValue.Plain("notes-value"),
            "Title" to EntryValue.Plain("UI Synthetic Entry"),
            "URL" to EntryValue.Plain("https://example.test"),
            "Empty custom" to EntryValue.Plain(""),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password)),
            "UserName" to EntryValue.Plain("qa-user")
        ))
        val fixture = quick.modifyContent { copy(group = group.copy(groups = group.groups + Group(folderId, "QA Folder", entries = listOf(entry)))) }
        Kdbx.write(fixture, repository.store.local)
        runBlocking { repository.unlock("qa-fixture-password", null) }
        KeyraApp::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(app, repository)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await { activity.findViewById<ImageButton>(R.id.addButton).visibility == View.VISIBLE }
    }

    @After fun tearDown() {
        if (::activity.isInitialized) onUi { activity.finish() }
        instrumentation.waitForIdleSync()
        if (::repository.isInitialized) repository.lock()
        if (::app.isInitialized && ::originalRepository.isInitialized) {
            KeyraApp::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(app, originalRepository)
        }
        originalTheme?.let { index -> instrumentation.runOnMainSync { ApplicationTheme.select(context, index) } }
    }

    @Test fun folderHeaderRecordFieldOrderAndSecretToggle() {
        screenshot("list")
        click(R.id.menuButton)
        clickPopupText(activity.getString(R.string.ui_settings_71))
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.ui_settings_71) }
        screenshot("settings")
        click(R.id.navigateBack)
        click(R.id.menuButton)
        clickPopupText(activity.getString(R.string.ui_data_72))
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.ui_data_72) }
        screenshot("data")
        click(R.id.navigateBack)
        openEntry()
        onUi {
            assertEquals("UI Synthetic Entry", activity.findViewById<TextView>(R.id.header).text.toString())
            val rows = descendants(activity.findViewById(R.id.content)).filterIsInstance<EntryFieldView>()
            val labels = listOf(R.string.field_login, R.string.field_password, R.string.field_url, R.string.field_notes).map { activity.getString(it) }
            assertEquals(labels + "Custom first", rows.map { it.findViewById<TextView>(R.id.fieldLabel).text.toString() })
            val secret = rows[1]
            val value = secret.findViewById<TextView>(R.id.fieldValue)
            assertEquals("********", value.text.toString())
            secret.findViewById<ImageButton>(R.id.toggleField).performClick()
            assertEquals(password, value.text.toString())
            val colored = value.text as Spanned
            assertEquals(3, colored.getSpans(0, colored.length, ForegroundColorSpan::class.java).map { it.foregroundColor }.toSet().size)
        }
        screenshot("record")
        onUi {
            val secret = descendants(activity.findViewById(R.id.content)).filterIsInstance<EntryFieldView>()[1]
            secret.findViewById<ImageButton>(R.id.toggleField).performClick()
            assertEquals("********", secret.findViewById<TextView>(R.id.fieldValue).text.toString())
            activity.findViewById<ImageButton>(R.id.menuButton).performClick()
        }
        clickPopupText(activity.getString(R.string.entry_history))
        clickPopupText(activity.getString(R.string.ui_cancel_82), optional = true)
    }

    @Test fun editorAndGeneratorApplyNumericPasswordAndSaveToKdbx() {
        openEntry(); click(R.id.editButton)
        await { activity.findViewById<ImageButton>(R.id.saveButton).visibility == View.VISIBLE }
        onUi {
            assertNull(activity.findViewById<View>(R.id.copyField))
            val input = editor(activity.getString(R.string.field_password)).findViewById<EditText>(R.id.fieldInput)
            assertEquals("*".repeat(password.length), input.transformationMethod.getTransformation(input.text, input).toString())
        }
        screenshot("editor")
        onUi { editor(activity.getString(R.string.field_password)).findViewById<ImageButton>(R.id.generateField).performClick() }
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.password_generator) }
        onUi {
            val content = activity.findViewById<ViewGroup>(R.id.content)
            val checks = descendants(content).filterIsInstance<ToggleButton>()
            checks[0].isChecked = false; checks[1].isChecked = false; checks[2].isChecked = true; checks[3].isChecked = false
            descendants(content).filterIsInstance<EditText>().single().setText("12")
            assertTrue(activity.findViewById<ImageButton>(R.id.saveButton).isEnabled)
            val preview = descendants(content).filterIsInstance<EntryFieldView>().single()
            preview.findViewById<ImageButton>(R.id.toggleField).performClick()
            val generated = preview.findViewById<TextView>(R.id.fieldValue).text.toString()
            assertEquals(12, generated.length); assertTrue(generated.all(Char::isDigit))
        }
        screenshot("generator")
        onUi {
            val preview = descendants(activity.findViewById(R.id.content)).filterIsInstance<EntryFieldView>().single()
            preview.findViewById<ImageButton>(R.id.copyField).performClick()
            assertEquals(preview.findViewById<TextView>(R.id.fieldValue).text.toString(), context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        click(R.id.saveButton)
        onUi {
            val generated = editor(activity.getString(R.string.field_password)).findViewById<EditText>(R.id.fieldInput).text.toString()
            assertEquals(12, generated.length); assertTrue(generated.all(Char::isDigit))
        }
        click(R.id.saveButton)
        await { activity.findViewById<ImageButton>(R.id.saveButton).visibility == View.GONE }
        val db = repository.database.value!!
        val persisted = Kdbx.read(repository.store.local, db.credentials).getEntryBy { uuid == entryId }!!
        assertEquals(12, persisted.fields.password!!.content.length)
        assertTrue(persisted.fields.password!!.content.all(Char::isDigit))
        assertTrue(persisted.history.isNotEmpty())
    }

    @Test fun invalidGeneratorCannotApplyAndBackPreservesDraftPassword() {
        openEntry(); click(R.id.editButton)
        onUi { editor(activity.getString(R.string.field_password)).findViewById<ImageButton>(R.id.generateField).performClick() }
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.password_generator) }
        onUi {
            descendants(activity.findViewById(R.id.content)).filterIsInstance<ToggleButton>().forEach { it.isChecked = false }
            assertFalse(activity.findViewById<ImageButton>(R.id.saveButton).isEnabled)
        }
        click(R.id.navigateBack)
        onUi { assertEquals(password, editor(activity.getString(R.string.field_password)).findViewById<EditText>(R.id.fieldInput).text.toString()) }
    }

    @Test fun rotationPreservesEditorAndCancelDoesNotWrite() {
        openEntry(); click(R.id.editButton)
        onUi { editor(activity.getString(R.string.field_login)).findViewById<EditText>(R.id.fieldInput).setText("draft-only-user") }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            onUi { activity.recreate() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 8_000) as MainActivity
            await { descendants(activity.findViewById(R.id.content)).filterIsInstance<EditableEntryFieldView>().isNotEmpty() }
            onUi { assertEquals("draft-only-user", editor(activity.getString(R.string.field_login)).findViewById<EditText>(R.id.fieldInput).text.toString()) }
        } finally { instrumentation.removeMonitor(monitor) }
        click(R.id.navigateBack)
        clickPopupText(activity.getString(R.string.discard))
        await { activity.findViewById<ImageButton>(R.id.saveButton).visibility == View.GONE }
        assertEquals("qa-user", repository.database.value!!.getEntryBy { uuid == entryId }!!.fields.userName!!.content)
    }

    @Test fun lockingDiscardsEditorAndGeneratorState() {
        openEntry(); click(R.id.editButton)
        onUi { editor(activity.getString(R.string.field_password)).findViewById<ImageButton>(R.id.generateField).performClick() }
        onUi { repository.lock() }
        await { activity.findViewById<ImageButton>(R.id.saveButton).visibility == View.GONE && activity.findViewById<ImageButton>(R.id.menuButton).visibility == View.GONE }
        screenshot("unlock")
        runBlocking { repository.unlock("qa-fixture-password", null) }
        await { activity.findViewById<ImageButton>(R.id.addButton).visibility == View.VISIBLE }
        assertTrue(onUiValue { descendants(activity.findViewById(R.id.content)).none { it is EditableEntryFieldView || it is PasswordGeneratorView } })
    }

    @Test fun welcomeFormsNavigateWithoutOpeningExternalSources() {
        onUi { activity.finish() }
        instrumentation.waitForIdleSync()
        val fixtureId = UUID.randomUUID().toString()
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "welcome-$fixtureId").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("welcome-$fixtureId-$name", mode)
        }
        val emptyRepository = VaultRepository(sandbox)
        KeyraApp::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(app, emptyRepository)
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        await { descendants(activity.findViewById(R.id.content)).any { it is WelcomeView } }
        screenshot("welcome")
        clickPopupText(activity.getString(R.string.ui_create_a_new_database_214))
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.ui_new_database_216) }
        screenshot("create")
        click(R.id.navigateBack)
        clickPopupText(activity.getString(R.string.ui_open_an_existing_database_215))
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.ui_open_database_222) }
        screenshot("sources")
        onUi {
            descendants(activity.findViewById(R.id.content)).filterIsInstance<android.widget.Button>()
                .first { it.text.toString().startsWith(activity.getString(R.string.webdav_name)) }.performClick()
        }
        await { activity.findViewById<TextView>(R.id.header).text.toString() == activity.getString(R.string.webdav_name) }
        screenshot("webdav")
        emptyRepository.lock()
    }

    private fun openEntry() {
        await { descendants(activity.findViewById(R.id.content)).filterIsInstance<TextView>().any { it.text.toString() == "QA Folder" } }
        onUi { descendants(activity.findViewById(R.id.content)).filterIsInstance<TextView>().first { it.text.toString() == "QA Folder" }.parent.let { (it as View).parent as View }.performClick() }
        await { activity.findViewById<TextView>(R.id.header).text.toString() == "QA Folder" }
        await { descendants(activity.findViewById(R.id.content)).filterIsInstance<TextView>().any { it.text.toString() == "UI Synthetic Entry" } }
        onUi { descendants(activity.findViewById(R.id.content)).filterIsInstance<TextView>().first { it.text.toString() == "UI Synthetic Entry" }.parent.let { (it as View).parent as View }.performClick() }
        await { activity.findViewById<ImageButton>(R.id.editButton).visibility == View.VISIBLE }
    }

    private fun editor(label: String) = descendants(activity.findViewById(R.id.content)).filterIsInstance<EditableEntryFieldView>()
        .first { it.findViewById<TextView>(R.id.fieldLabel).text.toString() == label }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun click(id: Int) { onUi { activity.findViewById<View>(id).performClick() }; instrumentation.waitForIdleSync() }
    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onUiValue(block: () -> T): T {
        val value = AtomicReference<T>(); onUi { value.set(block()) }; return value.get()
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) { if (onUiValue(check)) return; Thread.sleep(50) }
        assertTrue("UI condition timed out", onUiValue(check))
    }
    private fun clickPopupText(text: String, optional: Boolean = false) {
        val deadline = System.currentTimeMillis() + if (optional) 500 else 4_000
        while (System.currentTimeMillis() < deadline) {
            val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            for (node in nodes.filter { it.text?.toString()?.equals(text, ignoreCase = true) == true }) {
                var target: android.view.accessibility.AccessibilityNodeInfo? = node
                while (target != null) {
                    if (target.isClickable && target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        instrumentation.waitForIdleSync(); return
                    }
                    target = target.parent
                }
            }
            Thread.sleep(50)
        }
        if (!optional) fail("Popup item not found: $text")
    }
    private fun screenshot(name: String) {
        onUi { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            instrumentation.waitForIdleSync(); Thread.sleep(800)
            val image = instrumentation.uiAutomation.takeScreenshot() ?: return
            val directory = File(context.getExternalFilesDir(null), "ui-report").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } finally { onUi { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}
