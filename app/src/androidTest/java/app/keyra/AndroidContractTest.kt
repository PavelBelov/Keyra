package app.keyra

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keyra.core.*
import app.keyra.storage.SafStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val documentsAuthority get() = InstrumentationRegistry.getInstrumentation().context.packageName + ".documents"
    @Test fun repositoryCreatesEncryptedDatabaseAndLocks() = runBlocking {
        val id = UUID.randomUUID().toString()
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "test-$id").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences("test-$id-$name", mode)
        }
        val repo = VaultRepository(sandbox)
        repo.create("Synthetic master password only", null)
        assertNotNull(repo.database.value)
        assertTrue(repo.store.local.exists())
        repo.lock(); assertNull(repo.database.value)
        repo.unlock("wrong", null); assertNull(repo.database.value)
        repo.unlock("Synthetic master password only", null); assertNotNull(repo.database.value)
        repo.lock()
    }
    @Test fun keystoreTransportSecretsAreEncryptedAtRest() {
        val id = UUID.randomUUID().toString()
        val sandbox = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("test-$id-$name", mode)
        }
        val secrets = SecureSecrets(sandbox)
        secrets.putTransport("test", "Synthetic token".toByteArray())
        assertEquals("Synthetic token", secrets.getTransport("test").toString(Charsets.UTF_8))
        assertFalse(sandbox.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("test", "")!!.contains("Synthetic token"))
        secrets.remove("test"); assertFalse(secrets.has("test"))
    }
    @Test fun safReadWriteContract() {
        val uri = DocumentsContract.buildDocumentUri(documentsAuthority, "vault")
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("initial encrypted fixture".toByteArray()) }
        val provider = SafStorage(context, uri)
        assertFalse(provider.conditionalWrite)
        val file = File.createTempFile("test-saf-", ".kdbx", context.cacheDir)
        provider.download(file)
        assertEquals("initial encrypted fixture", file.readText())
        file.writeText("updated encrypted fixture")
        val result = provider.write(file, WriteCondition.UserApproved)
        assertEquals(file.sha256(), result.sha256)
        file.delete()
    }
    @Test fun safRejectsConditionalWriteRatherThanPretendingItIsAtomic() {
        val provider = SafStorage(context, DocumentsContract.buildDocumentUri(documentsAuthority, "vault"))
        val file = File.createTempFile("test-saf-", ".kdbx", context.cacheDir)
        try {
            provider.write(file, WriteCondition.Unchanged("fake-version")); fail("must reject unsupported CAS")
        } catch (e: StorageException) { assertEquals(StorageError.UNSUPPORTED, e.kind) }
        finally { file.delete() }
    }
    @Test fun journalBatchesVisibleChangesAndKeepsSecretsOut() {
        val id = UUID.randomUUID().toString()
        val sandbox = object : ContextWrapper(context) {
            override fun getFilesDir() = File(context.cacheDir, "journal-$id").apply { mkdirs() }
        }
        val store = LocalStore(sandbox)
        store.logEvents(listOf("ENTRY_ADDED ${id.uppercase()}", "ENTRY_EDITED ${id.uppercase()}", "SYNC_START"))
        assertEquals(1L, store.journalRevision.value)
        assertEquals(2, store.journalEntries().size)
        assertFalse(store.journal.readText().contains("SYNC_START"))
        try {
            store.logEvents(listOf("ENTRY_ADDED secret@example.com"))
            fail("Raw user data must not be accepted into the journal")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, store.journalEntries().size)
    }
}
