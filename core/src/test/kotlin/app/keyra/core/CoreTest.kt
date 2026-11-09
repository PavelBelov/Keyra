package app.keyra.core

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private fun db(): KeePassDatabase.Ver4x {
        val db = Kdbx.create(Kdbx.credentials("correct horse battery staple")) as KeePassDatabase.Ver4x
        // Deliberately cheap KDF only in tests; production defaults are unchanged.
        return db.copy(header = db.header.copy(kdfParameters = KdfParameters.Aes(2UL, db.header.masterSeed)))
    }
    private fun entry(id: UUID = UUID.randomUUID(), password: String = "initial", time: Instant = now) = Entry(id,
        fields = EntryFields.of("Title" to EntryValue.Plain("Example"), "UserName" to EntryValue.Plain("alice"), "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password))),
        times = TimeData.create(time))
    private fun add(db: KeePassDatabase, e: Entry) = Kdbx.putEntry(db, db.content.group.uuid, e)
    private fun get(db: KeePassDatabase, id: UUID) = db.getEntryBy { uuid == id }!!

    @Test fun encryptedRoundtripAndWrongPassword() {
        val original = add(db(), entry())
        val file = temp.newFile(); Kdbx.write(original, file)
        val decoded = Kdbx.read(file, original.credentials)
        assertEquals("initial", Kdbx.entries(decoded).first().fields.password?.content)
        assertFails { Kdbx.read(file, Kdbx.credentials("wrong")) }
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("initial"))
    }
    @Test fun v31Roundtrip() {
        val db = KeePassDatabase.Ver3x.create("root", Meta(), Kdbx.credentials("password"))
        val file = temp.newFile(); Kdbx.write(add(db, entry()), file)
        assertEquals(3.toShort(), Kdbx.read(file, db.credentials).header.version.major)
    }
    @Test fun argon2AndChacha20Roundtrip() {
        val original = db()
        val argon = KdfParameters.Argon2.default(original.header.masterSeed).copy(memory = 1024UL * 1024UL, iterations = 1UL)
        val configured = original.copy(header = original.header.copy(cipherId = BaseCiphers.ChaCha20.uuid, kdfParameters = argon))
        val file = temp.newFile(); Kdbx.write(add(configured, entry()), file)
        assertEquals("initial", Kdbx.entries(Kdbx.read(file, configured.credentials)).first().fields.password?.content)
    }
    @Test fun keyfileRequiredAfterRoundtrip() {
        val base = db(); val creds = Kdbx.credentials("password", ByteArray(32) { it.toByte() })
        val configured = base.copy(credentials = creds)
        val file = temp.newFile(); Kdbx.write(configured, file)
        Kdbx.read(file, creds)
        assertFails { Kdbx.read(file, Kdbx.credentials("password")) }
    }
    @Test fun unknownXmlCannotBeSilentlyDropped() {
        assertFails { StrictXml.unmarshalContent("<KeePassFile><Meta><PluginSecret>value</PluginSecret></Meta></KeePassFile>".toByteArray()) { error("SHOULD_NOT_DECODE") } }
    }
    @Test fun xmlAndKeyfileDtdAreRejected() {
        val xml = "<!DOCTYPE KeePassFile [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><KeePassFile/>".toByteArray()
        assertFails { StrictXml.unmarshalContent(xml) { error("SHOULD_NOT_DECODE") } }
        assertFails { Kdbx.credentials("pw", xml) }
        assertFails { Kdbx.credentials("pw", xml.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16LE)) }
    }
    @Test fun saveNormalizationDoesNotCreateMetadataConflict() {
        val base = db(); val a = entry(); val b = entry()
        val file = temp.newFile(); Kdbx.write(base, file)
        val normalized = Kdbx.read(file, base.credentials)
        val result = Merge.combine(add(base, a), add(normalized, b), normalized)
        assertFalse(result.blocked); assertEquals(2, Kdbx.entries(result.database).size)
    }
    @Test fun remoteGroupMoveIsPreserved() {
        val base = db(); val a = Group(UUID.randomUUID(), "A"); val b = Group(UUID.randomUUID(), "B")
        val initialized = base.modifyContent { copy(group = group.copy(groups = listOf(a, b))) }
        val moved = initialized.modifyContent { copy(group = group.copy(groups = listOf(b.copy(groups = listOf(a))))) }
        val result = Merge.combine(initialized, moved, initialized)
        assertFalse(result.blocked); assertEquals(a.uuid, Kdbx.group(result.database, b.uuid)!!.groups.first().uuid)
    }
    @Test fun groupCycleStopsPublication() {
        val base = db(); val a = Group(UUID.randomUUID(), "A"); val b = Group(UUID.randomUUID(), "B")
        val initialized = base.modifyContent { copy(group = group.copy(groups = listOf(a, b))) }
        val left = initialized.modifyContent { copy(group = group.copy(groups = listOf(b.copy(groups = listOf(a))))) }
        val right = initialized.modifyContent { copy(group = group.copy(groups = listOf(a.copy(groups = listOf(b))))) }
        assertTrue(Merge.combine(left, right, initialized).blocked)
    }
    @Test fun unrelatedChangesMerge() {
        val base = db(); val a = entry(); val b = entry()
        val result = Merge.combine(add(base, a), add(base, b), base)
        assertFalse(result.blocked); assertEquals(setOf(a.uuid, b.uuid), Kdbx.entries(result.database).map { it.uuid }.toSet())
    }
    @Test fun importingV4IntoV3PreservesNewFormatFieldsAndAttachments() {
        val local = KeePassDatabase.Ver3x.create("root", Meta(), Kdbx.credentials("password"))
        val binary = BinaryData.Uncompressed(false, "attachment data".toByteArray())
        val custom = Group(UUID.randomUUID(), "Imported", tags = listOf("group-tag"), customData = mapOf("plugin" to CustomDataValue("payload", now)))
        val incoming = db().modifyContent { copy(group = group.copy(groups = listOf(custom))) }.modifyBinaries { mapOf(binary.hash to binary) }
        val result = Merge.combine(local, incoming, null, importing = true)
        assertFalse(result.blocked); assertEquals(4.toShort(), result.database.header.version.major)
        val file = temp.newFile(); Kdbx.write(result.database, file)
        val roundtrip = Kdbx.read(file, local.credentials)
        assertEquals(listOf("group-tag"), Kdbx.group(roundtrip, custom.uuid)?.tags)
        assertEquals("payload", Kdbx.group(roundtrip, custom.uuid)?.customData?.get("plugin")?.value)
        assertTrue(roundtrip.binaries.containsKey(binary.hash))
    }
    @Test fun unrelatedExternalFileNeverReplacesUnchangedLocal() {
        val w = Workspace(); val local = db(); Kdbx.write(local, w.local); atomicCopy(w.local, w.baseline)
        val source = temp.newFile(); Kdbx.write(db(), source)
        val hash = w.local.sha256(); val p = Provider(source)
        assertEquals(SyncStatus.ERROR, SyncEngine(w).run(p, local).status)
        assertEquals(hash, w.local.sha256()); assertEquals(0, p.writes)
    }
    @Test fun separatelyDecodedSecretsAreEqual() {
        val first = entry(); val second = entry(first.uuid)
        assertEquals(Merge.entryKey(first), Merge.entryKey(second))
        val base = db()
        val result = Merge.combine(add(base, first), add(base, second), base)
        assertTrue(result.issues.isEmpty())
    }
    @Test fun parallelVersionsStayWhole() {
        val original = entry(); val base = add(db(), original)
        val left = original.copy(fields = original.fields + ("UserName" to EntryValue.Plain("left-user")), times = TimeData.create(now.plusSeconds(1)))
        val right = entry(original.uuid, "right-password", now.plusSeconds(2))
        val result = Merge.combine(add(base, left), add(base, right), base)
        val merged = get(result.database, original.uuid)
        assertFalse(result.blocked)
        assertEquals("alice", merged.fields.userName?.content)
        assertEquals("right-password", merged.fields.password?.content)
        assertTrue(merged.history.any { it.fields.userName?.content == "left-user" && it.fields.password?.content == "initial" })
    }
    @Test fun equalTimestampConflictRequiresChoice() {
        val original = entry(); val base = add(db(), original)
        val local = add(base, entry(original.uuid, "left")); val remote = add(base, entry(original.uuid, "right"))
        assertTrue(Merge.combine(local, remote, base).blocked)
        val resolved = Merge.combine(local, remote, base, choices = mapOf(original.uuid to true))
        assertFalse(resolved.blocked); assertEquals("right", get(resolved.database, original.uuid).fields.password?.content)
    }
    @Test fun deletionWinsOverUnchangedObject() {
        val item = entry(); val base = add(db(), item)
        val remote = Kdbx.removeEntry(base, item.uuid).modifyContent { copy(deletedObjects = listOf(DeletedObject(item.uuid, now.plusSeconds(5)))) }
        val result = Merge.combine(base, remote, base)
        assertFalse(result.blocked); assertTrue(Kdbx.entries(result.database).isEmpty())
    }
    @Test fun deletionAgainstEditIsBlockedAndResolvable() {
        val item = entry(); val base = add(db(), item)
        val remote = Kdbx.removeEntry(base, item.uuid).modifyContent { copy(deletedObjects = listOf(DeletedObject(item.uuid, now.plusSeconds(5)))) }
        val local = add(base, entry(item.uuid, "edited", now.plusSeconds(2)))
        assertTrue(Merge.combine(local, remote, base).blocked)
        val kept = Merge.combine(local, remote, base, choices = mapOf(item.uuid to false))
        assertFalse(kept.blocked); assertEquals("edited", get(kept.database, item.uuid).fields.password?.content)
        assertTrue(kept.database.content.deletedObjects.none { it.id == item.uuid })
    }
    @Test fun differentDatabaseIsRejectedForSyncButCanBeImported() {
        val a = db(); val b = add(db(), entry())
        assertTrue(Merge.combine(a, b, null).blocked)
        assertFalse(Merge.combine(a, b, null, importing = true).blocked)
    }
    @Test fun importDoesNotApplyForeignDeletions() {
        val item = entry(); val a = add(db(), item)
        val b = db().modifyContent { copy(deletedObjects = listOf(DeletedObject(item.uuid, now.plusSeconds(5)))) }
        val result = Merge.combine(a, b, null, importing = true)
        assertEquals(item.uuid, Kdbx.entries(result.database).first().uuid)
    }
    @Test fun atomicFailurePreservesPreviousFile() {
        val file = temp.newFile().apply { writeText("previous") }
        assertFails { atomicWrite(file) { it.writeText("partial"); error("power loss") } }
        assertEquals("previous", file.readText())
    }
    @Test fun updatingOneGroupPreservesOtherBranches() {
        val child = Group(UUID.randomUUID(), "Child")
        val sibling = Group(UUID.randomUUID(), "Sibling")
        val root = Group(UUID.randomUUID(), "Root", groups = listOf(child, sibling))
        val updated = Kdbx.replaceGroup(root, child.uuid) { it.copy(name = "Renamed") }
        assertEquals("Renamed", updated.groups.first().name)
        assertSame(sibling, updated.groups[1])
        assertSame(root, Kdbx.replaceGroup(root, UUID.randomUUID()) { error("Missing group must not be updated") })
        assertEquals("Child", root.groups.first().name)
    }
    @Test fun mergingWideTreesPreservesOrderAndParents() {
        val base = db()
        val groups = List(1000) { index -> Group(UUID.randomUUID(), "Group $index", entries = listOf(entry())) }
        val local = base.modifyContent { copy(group = group.copy(groups = groups)) }
        val added = entry()
        val remote = Kdbx.putEntry(local, groups.last().uuid, added)
        val merged = Merge.combine(local, remote, local)
        assertFalse(merged.blocked)
        assertEquals(groups.map { it.uuid }, merged.database.content.group.groups.map { it.uuid })
        assertEquals(added.uuid, merged.database.content.group.groups.last().entries.last().uuid)
        assertEquals(1001, Kdbx.entries(merged.database).size)
    }
    @Test fun webDavUsesConditionalHeaderAndChecksActualBytes() {
        MockWebServer().use { server ->
            server.start()
            val file = temp.newFile().apply { writeText("encrypted-database") }
            server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("old"))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setHeader("ETag", "\"v2\"").setBody("encrypted-database"))
            val provider = WebDavStorage(server.url("/vault.kdbx").toString(), "u", "p", OkHttpClient())
            provider.download(temp.newFile())
            assertTrue(provider.conditionalWrite)
            provider.write(file, WriteCondition.Unchanged("\"v1\""))
            server.takeRequest()
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        }
    }
    @Test fun webDavRejectsChangedVersion() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(412))
            val p = WebDavStorage(server.url("/db").toString(), "u", "p", OkHttpClient())
            assertEquals(StorageError.CHANGED, assertFailsWith<StorageException> { p.write(temp.newFile(), WriteCondition.Unchanged("\"old\"")) }.kind)
        }
    }
    @Test fun weakEtagDoesNotEnableAutomaticWrites() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setHeader("ETag", "W/\"v1\"").setBody("data"))
            val p = WebDavStorage(server.url("/db").toString(), "u", "p", OkHttpClient())
            p.download(temp.newFile()); assertFalse(p.conditionalWrite)
        }
    }
    private inner class Workspace : SyncWorkspace {
        val dir = temp.newFolder()
        override val local = File(dir, "local"); override val baseline = File(dir, "base")
        override val remote = File(dir, "remote"); override val candidate = File(dir, "candidate"); override val transaction = File(dir, "transaction")
        var pending: String? = null
        override fun log(event: String) { }
        override fun beginWrite(hash: String) { pending = hash }
        override fun pendingWrite() = pending
        override fun complete(hash: String) { pending = null }
    }
    private inner class Provider(val source: File, val conditional: Boolean = true) : StorageProvider {
        var writes = 0; var fail = false
        override val conditionalWrite = conditional
        override fun metadata() = RemoteFile(source.sha256())
        override fun download(target: File): RemoteFile { atomicCopy(source, target); return metadata() }
        override fun write(source: File, condition: WriteCondition): RemoteFile {
            writes++; if (fail) throw StorageException(StorageError.UNKNOWN_WRITE)
            atomicCopy(source, this.source); return metadata()
        }
    }
    @Test fun remoteOnlyUpdateIsPulled() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val source = temp.newFile(); Kdbx.write(add(base, entry()), source)
        val p = Provider(source); val result = SyncEngine(w).run(p, base)
        assertEquals(SyncStatus.SYNCED, result.status); assertEquals(0, p.writes); assertEquals(source.sha256(), w.local.sha256())
    }
    @Test fun localOnlyUpdateIsPushed() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val source = temp.newFile(); atomicCopy(w.local, source)
        val local = add(base, entry()); Kdbx.write(local, w.local)
        val p = Provider(source); val result = SyncEngine(w).run(p, local)
        assertEquals(SyncStatus.SYNCED, result.status); assertEquals(1, p.writes); assertEquals(source.sha256(), w.baseline.sha256())
    }
    @Test fun lockedDatabaseDoesNotDecryptOrWrite() {
        val w = Workspace(); val source = temp.newFile().apply { writeText("encrypted") }; val p = Provider(source)
        assertEquals(SyncStatus.LOCKED, SyncEngine(w).run(p, null).status); assertEquals(0, p.writes)
    }
    @Test fun lockDuringDownloadPreventsDecryptionStage() {
        val w = Workspace(); val local = db(); Kdbx.write(local, w.local)
        // Deliberately invalid bytes: reaching decoding would produce ERROR rather than LOCKED.
        val source = temp.newFile().apply { writeText("encrypted") }; val p = Provider(source)
        assertEquals(SyncStatus.LOCKED, SyncEngine(w).run(p, local, isUnlocked = { false }).status)
        assertEquals(0, p.writes)
    }
    @Test fun unsupportedCasRequiresExplicitApproval() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val source = temp.newFile(); atomicCopy(w.local, source)
        val changed = add(base, entry()); Kdbx.write(changed, w.local)
        val p = Provider(source, false)
        assertEquals(SyncStatus.APPROVAL_REQUIRED, SyncEngine(w).run(p, changed).status); assertEquals(0, p.writes)
    }
    @Test fun unknownWritePreservesIntentAndLocalData() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val source = temp.newFile(); atomicCopy(w.local, source)
        val changed = add(base, entry()); Kdbx.write(changed, w.local)
        val hash = w.local.sha256(); val p = Provider(source).apply { fail = true }
        assertEquals(StorageError.UNKNOWN_WRITE, assertFailsWith<StorageException> { SyncEngine(w).run(p, changed) }.kind)
        assertEquals(hash, w.pendingWrite()); assertEquals(hash, w.transaction.sha256()); assertEquals(hash, w.local.sha256())
    }
    @Test fun interruptedSuccessfulUploadPreservesNewerLocalEdits() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val first = add(base, entry()); Kdbx.write(first, w.transaction)
        w.beginWrite(w.transaction.sha256())
        val source = temp.newFile(); atomicCopy(w.transaction, source)
        val newer = add(first, entry()); Kdbx.write(newer, w.local)
        val result = SyncEngine(w).run(Provider(source), newer)
        assertEquals(SyncStatus.SYNCED, result.status)
        assertEquals(2, Kdbx.entries(Kdbx.read(w.local, newer.credentials)).size)
        assertEquals(w.local.sha256(), source.sha256())
    }
    @Test fun corruptRemoteDoesNotReplaceOrOverwriteLocal() {
        val w = Workspace(); val base = db(); Kdbx.write(base, w.local); atomicCopy(w.local, w.baseline)
        val before = w.local.sha256()
        val source = temp.newFile().apply { writeText("corrupt") }; val p = Provider(source)
        assertEquals(SyncStatus.ERROR, SyncEngine(w).run(p, base).status)
        assertEquals(before, w.local.sha256()); assertEquals(0, p.writes)
    }
}
