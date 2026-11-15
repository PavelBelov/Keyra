package app.keyra

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.DocumentsContract
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import app.keyra.core.*
import app.keyra.storage.Connection
import app.keyra.storage.SafStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/** Real repository, encrypted files and SAF provider; all fixtures belong to the QA package. */
@RunWith(AndroidJUnit4::class)
class SyncMergeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private lateinit var sandbox: Context
    private lateinit var repo: VaultRepository
    private lateinit var base: KeePassDatabase
    private lateinit var original: Entry
    private lateinit var source: SafStorage

    @Before fun prepare() {
        assumeTrue("Synthetic sync tests require the QA package", context.packageName.endsWith(".qa"))
        val id = UUID.randomUUID().toString()
        sandbox = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "sync-test-$id").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("sync-test-$id-$name", mode)
        }
        repo = VaultRepository(sandbox)
        val created = Kdbx.create(Kdbx.credentials("synthetic-sync-password")) as KeePassDatabase.Ver4x
        base = created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(2UL, created.header.masterSeed)))
        original = Entry(UUID.randomUUID(), fields = EntryFields(mapOf(
            "Title" to EntryValue.Plain("Synthetic conflict"), "A" to EntryValue.Plain("a0"),
            "B" to EntryValue.Plain("b0"), "C" to EntryValue.Plain("c0"), "D" to EntryValue.Plain("d0"),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("synthetic-secret-original"))
        )), times = TimeData.create(time))
        base = withEntry(original)
        val uri = DocumentsContract.buildDocumentUri(instrumentation.context.packageName + ".documents", "sync-$id")
        source = SafStorage(sandbox, uri)
        repo.providers.save(Connection("saf", uri.toString()))
    }

    @After fun close() {
        if (::repo.isInitialized) {
            repo.lock()
            repo.store.preferences.edit().putBoolean("syncEnabled", false).commit()
            SyncWorker.schedule(sandbox)
            repo.providers.clear()
        }
    }

    private fun withEntry(entry: Entry) = Kdbx.putEntry(base, base.content.group.uuid, entry)
    private fun change(entry: Entry, second: Long, vararg changes: Pair<String, String>): Entry {
        val fields = entry.fields.toMutableMap()
        changes.forEach { (key, value) -> fields[key] = if (fields[key] is EntryValue.Encrypted)
            EntryValue.Encrypted(EncryptedValue.fromString(value)) else EntryValue.Plain(value) }
        return entry.copy(fields = EntryFields(fields), history = entry.history + entry.copy(history = emptyList()),
            times = entry.times!!.copy(lastModificationTime = time.plusSeconds(second)))
    }
    private suspend fun fixtures(local: Entry, remote: Entry, localExtra: List<Entry> = emptyList(), remoteExtra: List<Entry> = emptyList()) {
        Kdbx.write(localExtra.fold(withEntry(local)) { db, value -> Kdbx.putEntry(db, db.content.group.uuid, value) }, repo.store.local)
        Kdbx.write(base, repo.store.baseline)
        val file = File.createTempFile("remote-fixture-", ".kdbx", context.cacheDir)
        try {
            Kdbx.write(remoteExtra.fold(withEntry(remote)) { db, value -> Kdbx.putEntry(db, db.content.group.uuid, value) }, file)
            source.write(file, WriteCondition.UserApproved)
        }
        finally { file.delete() }
        repo.unlock("synthetic-sync-password", null)
        assertNotNull(repo.database.value)
    }
    private fun entry(db: KeePassDatabase = requireNotNull(repo.database.value)) = Kdbx.entries(db).single { it.uuid == original.uuid }
    private fun values(entry: Entry) = listOf("A", "B", "C", "D").map { entry.fields[it]?.content }
    private fun remote(): KeePassDatabase {
        val file = File.createTempFile("remote-check-", ".kdbx", context.cacheDir)
        return try { source.download(file); Kdbx.read(file, base.credentials) } finally { file.delete() }
    }

    @Test fun automaticFieldMergeNeedsOnlySafUploadApproval() = runBlocking {
        val local = change(change(original, 10, "A" to "a1", "B" to "b1", "C" to "c1"), 30, "C" to "c3")
        val external = change(original, 20, "B" to "b2", "C" to "c2")
        fixtures(local, external)
        assertEquals(SyncStatus.APPROVAL_REQUIRED, repo.sync())
        assertFalse(repo.store.preferences.getBoolean("conflict", false))
        assertEquals(listOf("a1", "b2", "c3", "d0"), values(entry()))
        assertEquals("a0", entry(remote()).fields["A"]?.content)
        assertEquals(SyncStatus.SYNCED, repo.sync(true))
        assertEquals(listOf("a1", "b2", "c3", "d0"), values(entry(remote())))
        assertEquals(time.plusSeconds(10), EntryFieldClocks.lastChange(entry(remote()), "A"))
    }

    @Test fun manualFieldChoicePreservesAutomaticFieldsAndThenUploads() = runBlocking {
        fixtures(change(original, 10, "A" to "a1", "B" to "left"), change(original, 10, "B" to "right", "C" to "c1"))
        val baselineHash = repo.store.baseline.sha256()
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        assertEquals(baselineHash, repo.store.baseline.sha256())
        // This entry still needs a choice: its complete local version stays visible.
        assertEquals(listOf("a1", "left", "c0", "d0"), values(entry()))
        assertEquals("right", entry(remote()).fields["B"]?.content)
        val review = requireNotNull(repo.conflictReview())
        assertEquals(listOf("B"), review.issues.map { it.fieldName })
        repo.resolve(review, mapOf(review.issues.single().key to true))
        assertEquals("", repo.message.value)
        assertEquals(listOf("a1", "right", "c1", "d0"), values(entry()))
        assertFalse(repo.store.preferences.getBoolean("conflict", false))
        assertEquals(SyncStatus.SYNCED, repo.sync(true))
        assertEquals(listOf("a1", "right", "c1", "d0"), values(entry(remote())))
    }

    @Test fun staleReviewCannotReplaceVaultAfterRelocking() = runBlocking {
        fixtures(change(original, 10, "B" to "left"), change(original, 10, "B" to "right"))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val review = requireNotNull(repo.conflictReview())
        val hash = repo.store.local.sha256()
        repo.lock(); repo.unlock("synthetic-sync-password", null)
        repo.resolve(review, review.issues.associate { it.key to true })
        assertEquals(hash, repo.store.local.sha256())
        assertTrue(repo.store.preferences.getBoolean("conflict", false))
        assertEquals(L10n.text(context, R.string.merge_review_changed), repo.message.value)
    }

    @Test fun previouslySavedConflictIsReevaluatedWithoutManualChoice() = runBlocking {
        val local = change(original, 10, "B" to "older")
        val external = change(original, 20, "B" to "newer")
        fixtures(local, external)
        Kdbx.write(withEntry(external), repo.store.remote)
        repo.store.preferences.edit().putBoolean("conflict", true).commit()
        assertEquals(SyncStatus.SYNCED, repo.sync(true))
        assertFalse(repo.store.preferences.getBoolean("conflict", false))
        assertEquals("newer", entry().fields["B"]?.content)
    }

    @Test fun preparedResolutionFreezesChoicesAndStillRejectsStaleSnapshots() = runBlocking {
        fixtures(change(original, 10, "B" to "left"), change(original, 10, "B" to "right"))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val review = requireNotNull(repo.conflictReview())
        val choices = review.issues.associate { it.key to true }.toMutableMap()
        val prepared = repo.prepareConflictResolution(review, choices)
        choices.keys.toList().forEach { choices[it] = false }
        assertTrue(prepared.choices.values.all { it })
        val hash = repo.store.local.sha256()
        repo.lock(); repo.unlock("synthetic-sync-password", null)
        repo.resolve(prepared)
        assertEquals(hash, repo.store.local.sha256())
        assertEquals(L10n.text(context, R.string.merge_review_changed), repo.message.value)
        val fresh = requireNotNull(repo.conflictReview())
        repo.resolve(repo.prepareConflictResolution(fresh, prepared.choices))
        assertEquals("", repo.message.value)
        assertEquals("right", entry().fields["B"]?.content)
        assertFalse(repo.store.preferences.getBoolean("conflict", false))
    }

    @Test fun preparedResolutionRejectsChangedRemoteFile() = runBlocking {
        fixtures(change(original, 10, "B" to "left"), change(original, 10, "B" to "right"))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val review = requireNotNull(repo.conflictReview())
        val prepared = repo.prepareConflictResolution(review, review.issues.associate { it.key to true })
        val hash = repo.store.local.sha256()
        Kdbx.write(Kdbx.putEntry(review.remote, review.remote.content.group.uuid, change(original, 30, "B" to "new remote")), repo.store.remote)
        repo.resolve(prepared)
        assertEquals(hash, repo.store.local.sha256())
        assertTrue(repo.store.preferences.getBoolean("conflict", false))
        assertEquals(L10n.text(context, R.string.merge_review_changed), repo.message.value)
    }

    @Test fun manualFormRequiresEveryChoiceAndKeepsPasswordsHidden() = runBlocking {
        fixtures(change(original, 10, "B" to "left", "Password" to "synthetic-secret-local"),
            change(original, 10, "B" to "right", "Password" to "synthetic-secret-remote"))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val draft = ConflictDraft(requireNotNull(repo.conflictReview()))
        val registered = mutableListOf<EntryFieldView>()
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(context, R.style.Theme_Keyra)
            val form = ConflictResolutionView(themed, draft, false, {}, {}, { registered += it }, {}, {})
            val buttons = descendants(form).filterIsInstance<Button>()
            val apply = buttons.single { it.text.toString() == L10n.text(context, R.string.merge_apply) }
            val groups = descendants(form).filterIsInstance<RadioGroup>()
            assertEquals(2, groups.size)
            assertFalse(apply.isEnabled)
            assertFalse(buttons.any { it.text.toString() in listOf(
                L10n.text(context, R.string.merge_view_version), L10n.text(context, R.string.merge_use_local), L10n.text(context, R.string.merge_use_remote)) })
            val previews = descendants(form).filterIsInstance<ImageButton>()
            assertEquals(4, previews.size)
            previews.forEach { it.performClick() }
            assertTrue(draft.choices.isEmpty()) // Expanding a version must not select it.
            val displayed = descendants(form).filterIsInstance<TextView>().joinToString("\n") { it.text }
            assertFalse(displayed.contains("synthetic-secret-local"))
            assertFalse(displayed.contains("synthetic-secret-remote"))
            val first = descendants(groups[0]).filterIsInstance<RadioButton>()
            val second = descendants(groups[1]).filterIsInstance<RadioButton>()
            assertEquals(2, first.size); assertEquals(2, second.size)
            assertTrue(first[0].text.toString().contains(L10n.text(context, R.string.merge_local_database)))
            assertTrue(first[1].text.toString().contains(L10n.text(context, R.string.merge_remote_database)))
            assertTrue((first[0].parent as ViewGroup).getChildAt(1) is ImageButton)
            first[0].performClick()
            first[1].performClick()
            assertFalse(first[0].isChecked); assertTrue(first[1].isChecked)
            first[0].performClick()
            assertEquals(first[0].id, groups[0].checkedRadioButtonId)
            assertFalse(apply.isEnabled)
            second[1].performClick()
            assertTrue(apply.isEnabled); assertTrue(draft.complete)
            val passwordRow = registered.first { it.findViewById<TextView>(R.id.fieldLabel).text.toString() == context.getString(R.string.field_password) }
            passwordRow.findViewById<ImageButton>(R.id.toggleField).performClick()
            assertEquals("synthetic-secret-local", passwordRow.findViewById<TextView>(R.id.fieldValue).text.toString())
            previews[2].performClick(); previews[2].performClick()
            assertEquals(context.getString(R.string.hidden_value), passwordRow.findViewById<TextView>(R.id.fieldValue).text.toString())
            val restored = ConflictResolutionView(themed, draft, false, {}, {}, { registered += it }, {}, {})
            val restoredGroups = descendants(restored).filterIsInstance<RadioGroup>()
            assertEquals(listOf(false, true), restoredGroups.map { group ->
                descendants(group).filterIsInstance<RadioButton>().indexOfFirst { it.isChecked } == 1
            })
            registered.forEach { it.hideValue() }
        }
        repo.resolve(draft.review, draft.resolutions())
        assertEquals("", repo.message.value)
        assertEquals("left", entry().fields["B"]?.content)
        assertEquals("synthetic-secret-remote", entry().fields["Password"]?.content)
    }

    @Test fun equalMetadataValuesAreAppliedWithoutReviewIncludingSavedConflicts() = runBlocking {
        fixtures(original, original)
        repo.mutate { db -> db.modifyContent {
            copy(meta = meta.copy(customData = mapOf("same" to CustomDataValue("value", time))))
        } }
        val remote = base.modifyContent {
            copy(meta = meta.copy(customData = mapOf("same" to CustomDataValue("value", time.plusSeconds(10)))))
        }
        val file = File.createTempFile("metadata-fixture-", ".kdbx", context.cacheDir)
        try {
            Kdbx.write(remote, file)
            source.write(file, WriteCondition.UserApproved)
            assertEquals(SyncStatus.SYNCED, repo.sync(true))
            assertFalse(repo.store.preferences.getBoolean("conflict", false))
            assertNull(repo.conflictReview())
            assertEquals(time.plusSeconds(10), repo.database.value!!.content.meta.customData.getValue("same").lastModified)
            repo.lock()
            // A conflict saved by an older app must disappear during unlock, before opening a form.
            Kdbx.write(remote.modifyContent {
                copy(meta = meta.copy(customData = mapOf("same" to CustomDataValue("value", time))))
            }, repo.store.local)
            Kdbx.write(remote, repo.store.remote)
            repo.store.preferences.edit().putBoolean("conflict", true).putBoolean("importConflict", false).commit()
            repo.unlock("synthetic-sync-password", null)
            assertEquals("", repo.message.value)
            assertFalse(repo.store.preferences.getBoolean("conflict", false))
            assertNull(repo.conflictReview())
            assertEquals(time.plusSeconds(10), repo.database.value!!.content.meta.customData.getValue("same").lastModified)
        } finally { file.delete() }
    }

    @Test fun lastModifiedDoesNotBlockSyncAndIsRefreshedAfterManualResolution() = runBlocking {
        fixtures(original, original)
        val key = DatabaseMetadataMerge.LAST_MODIFIED_KEY
        repo.mutate { db -> db.modifyContent {
            copy(meta = meta.copy(customData = mapOf(key to CustomDataValue("old local"))))
        } }
        val external = base.modifyContent { copy(meta = meta.copy(customData = mapOf(key to CustomDataValue("old remote")))) }
        val file = File.createTempFile("modified-fixture-", ".kdbx", context.cacheDir)
        try {
            Kdbx.write(external, file); source.write(file, WriteCondition.UserApproved)
            assertEquals(SyncStatus.SYNCED, repo.sync(true))
            assertFalse(repo.store.preferences.getBoolean("conflict", false))
            val automaticStamp = repo.database.value!!.content.meta.customData.getValue(key).value
            assertTrue(automaticStamp.endsWith(" GMT"))
            assertNotEquals("old local", automaticStamp); assertNotEquals("old remote", automaticStamp)
            repo.mutate { db -> Kdbx.putEntry(db, db.content.group.uuid, change(original, 10, "B" to "left")).modifyContent {
                copy(meta = meta.copy(customData = meta.customData + (key to CustomDataValue("before choice"))))
            } }
            Kdbx.write(Kdbx.putEntry(remote(), base.content.group.uuid, change(original, 10, "B" to "right")), file)
            source.write(file, WriteCondition.UserApproved)
            assertEquals(SyncStatus.CONFLICT, repo.sync(true))
            assertEquals("before choice", repo.database.value!!.content.meta.customData[key]?.value)
            val review = requireNotNull(repo.conflictReview())
            assertEquals(listOf("ENTRY_FIELD"), review.issues.map { it.reason })
            repo.resolve(review, review.issues.associate { it.key to true })
            assertEquals("", repo.message.value)
            assertNotEquals("before choice", repo.database.value!!.content.meta.customData[key]?.value)
            assertEquals("right", entry().fields["B"]?.content)
        } finally { file.delete() }
    }

    @Test fun preparedReviewOpensWithoutReadingSnapshotsAndIsRebuiltAfterUnlock() = runBlocking {
        fixtures(change(original, 10, "B" to "left"), change(original, 10, "B" to "right"))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val files = listOf(repo.store.local, repo.store.remote, repo.store.baseline, repo.store.conflictLocal)
        val moved = mutableListOf<Pair<File, File>>()
        val review = try {
            files.forEach { file ->
                val backup = File(file.parentFile, file.name + ".review-test")
                assertTrue(file.renameTo(backup))
                moved += file to backup
            }
            // Missing encrypted snapshots make any read, hashing or merge-on-open fail.
            val review = requireNotNull(repo.conflictReview())
            repeat(3) { assertSame(review, repo.conflictReview()) }
            val issue = review.issues.single()
            assertTrue(review.side(sandbox, issue, false).first.contains("Synthetic conflict"))
            assertEquals("left", review.details(sandbox, issue, false).first { it.first == "B" }.second.content)
            review
        } finally { moved.asReversed().forEach { (file, backup) -> assertTrue(backup.renameTo(file)) } }
        repo.lock()
        assertTrue(runCatching { repo.conflictReview() }.isFailure)
        repo.unlock("synthetic-sync-password", null)
        assertEquals("", repo.message.value)
        val reopened = requireNotNull(repo.conflictReview())
        assertNotSame(review, reopened)
        repo.resolve(review, review.issues.associate { it.key to true })
        assertTrue(repo.store.preferences.getBoolean("conflict", false))
        repo.resolve(reopened, reopened.issues.associate { it.key to true })
        assertEquals("", repo.message.value)
        assertEquals("right", entry().fields["B"]?.content)
        assertNull(repo.conflictReview())
    }

    @Test fun partialEntriesSurviveRestartAndResolutionUsesFrozenOriginals() = runBlocking {
        val automatic = original.copy(uuid = UUID.randomUUID())
        base = Kdbx.putEntry(base, base.content.group.uuid, automatic)
        val added = original.copy(uuid = UUID.randomUUID())
        fixtures(change(original, 10, "A" to "a1", "B" to "left"), change(original, 10, "B" to "right", "C" to "c1"),
            localExtra = listOf(change(automatic, 10, "A" to "auto-a")),
            remoteExtra = listOf(change(automatic, 20, "B" to "auto-b"), added))
        val baselineHash = repo.store.baseline.sha256()
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        assertEquals(listOf("a1", "left", "c0", "d0"), values(entry()))
        assertTrue(Kdbx.entries(repo.database.value!!).any { it.uuid == added.uuid })
        val autoVisible = Kdbx.entries(repo.database.value!!).single { it.uuid == automatic.uuid }
        assertEquals("auto-a", autoVisible.fields["A"]?.content); assertEquals("auto-b", autoVisible.fields["B"]?.content)
        val visibleHash = repo.store.local.sha256()
        val frozenHash = repo.store.conflictLocal.sha256()
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        assertEquals(visibleHash, repo.store.local.sha256())
        assertEquals(frozenHash, repo.store.conflictLocal.sha256())
        assertEquals(baselineHash, repo.store.baseline.sha256())
        repo.lock()
        repo = VaultRepository(sandbox) // New repository instance, same durable encrypted workspace.
        repo.unlock("synthetic-sync-password", null)
        assertEquals("", repo.message.value)
        assertEquals(visibleHash, repo.store.local.sha256())
        val review = requireNotNull(repo.conflictReview())
        assertEquals("c0", entry(review.local).fields["C"]?.content)
        assertEquals(listOf("B"), review.issues.map { it.fieldName })
        assertEquals("b0", Kdbx.entries(review.local).single { it.uuid == automatic.uuid }.fields["B"]?.content)
        repo.resolve(review, review.issues.associate { it.key to true })
        assertEquals("", repo.message.value)
        assertEquals(listOf("a1", "right", "c1", "d0"), values(entry()))
        assertFalse(repo.store.conflictLocal.exists())
        assertEquals(SyncStatus.SYNCED, repo.sync(true))
        assertTrue(Kdbx.entries(remote()).any { it.uuid == added.uuid })
        assertEquals("auto-b", Kdbx.entries(remote()).single { it.uuid == automatic.uuid }.fields["B"]?.content)
    }

    @Test fun interruptedPartialApplicationIsRecoveredFromTheFrozenOriginal() = runBlocking {
        val added = original.copy(uuid = UUID.randomUUID())
        fixtures(change(original, 10, "B" to "left"), change(original, 10, "B" to "right"), remoteExtra = listOf(added))
        assertEquals(SyncStatus.CONFLICT, repo.sync(true))
        val frozenHash = repo.store.conflictLocal.sha256()
        repo.lock()
        // Simulate a crash after durable intent, before the atomic replacement of local.
        atomicCopy(repo.store.conflictLocal, repo.store.local)
        repo = VaultRepository(sandbox)
        repo.unlock("synthetic-sync-password", null)
        assertEquals("", repo.message.value)
        assertTrue(Kdbx.entries(repo.database.value!!).any { it.uuid == added.uuid })
        assertEquals(frozenHash, repo.store.conflictLocal.sha256())
        assertEquals(repo.store.local.sha256(), repo.store.preferences.getString("partialAppliedHash", null))
        assertEquals(listOf("B"), requireNotNull(repo.conflictReview()).issues.map { it.fieldName })
    }

    @Test fun importAppliesIndependentRecordsAndKeepsChoicesAfterRestart() = runBlocking {
        fixtures(change(original, 10, "B" to "left"), original)
        repo.providers.clear()
        val added = original.copy(uuid = UUID.randomUUID())
        var imported = Kdbx.create(Kdbx.credentials("synthetic-import-password")) as KeePassDatabase.Ver4x
        imported = imported.copy(header = imported.header.copy(kdfParameters = KdfParameters.Aes(2UL, imported.header.masterSeed)))
        val other = Kdbx.putEntry(Kdbx.putEntry(imported, imported.content.group.uuid, change(original, 10, "B" to "right")), imported.content.group.uuid, added)
        val file = File.createTempFile("import-partial-", ".kdbx", context.cacheDir)
        try {
            Kdbx.write(other, file)
            repo.import(file, "synthetic-import-password", null)
        } finally { file.delete() }
        assertEquals("", repo.message.value)
        assertTrue(Kdbx.entries(repo.database.value!!).any { it.uuid == added.uuid })
        assertEquals("left", entry().fields["B"]?.content)
        repo.lock(); repo = VaultRepository(sandbox); repo.unlock("synthetic-sync-password", null)
        assertEquals("", repo.message.value)
        val review = requireNotNull(repo.conflictReview())
        assertTrue(review.importing)
        repo.resolve(review, review.issues.associate { it.key to true })
        assertEquals("", repo.message.value)
        assertEquals("right", entry().fields["B"]?.content)
        assertTrue(Kdbx.entries(repo.database.value!!).any { it.uuid == added.uuid })
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
