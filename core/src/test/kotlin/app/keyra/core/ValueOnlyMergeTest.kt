package app.keyra.core

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.Test

class ValueOnlyMergeTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private fun db() = Kdbx.create(Kdbx.credentials("synthetic-values"))

    @Test fun lastModifiedIsAutomaticButOtherMetadataStillNeedsAChoice() {
        val original = db()
        val key = DatabaseMetadataMerge.LAST_MODIFIED_KEY
        val left = original.modifyContent { copy(meta = meta.copy(customData = mapOf(key to CustomDataValue("old local")))) }
        val right = original.modifyContent { copy(meta = meta.copy(customData = mapOf(key to CustomDataValue("old remote")))) }
        val automatic = Merge.combine(left, right, null)
        assertFalse(automatic.blocked)
        // Pure merge/preview is deterministic; a completed save supplies the current time.
        assertEquals(automatic.database.content.meta.customData, Merge.combine(left, right, null).database.content.meta.customData)
        val completed = DatabaseMetadataMerge.completed(automatic.database, time)
        assertEquals("Thu Jan 1 00:00:00 2026 GMT", completed.content.meta.customData[key]?.value)
        assertSame(original, DatabaseMetadataMerge.completed(original, time))
        val differentLeft = left.modifyContent { copy(meta = meta.copy(customData = meta.customData + ("other" to CustomDataValue("left")))) }
        val differentRight = right.modifyContent { copy(meta = meta.copy(customData = meta.customData + ("other" to CustomDataValue("right")))) }
        val conflict = Merge.combine(differentLeft, differentRight, null)
        assertEquals(listOf("CUSTOM_METADATA"), conflict.issues.map { it.reason })
        assertEquals("old local", conflict.partialDatabase.content.meta.customData[key]?.value)
        for (remote in listOf(false, true)) {
            val resolved = Merge.combine(differentLeft, differentRight, null, choices = mapOf(null to remote))
            assertFalse(resolved.blocked)
            val saved = DatabaseMetadataMerge.completed(resolved.database, time)
            assertEquals("Thu Jan 1 00:00:00 2026 GMT", saved.content.meta.customData[key]?.value)
            assertEquals(if (remote) "right" else "left", saved.content.meta.customData["other"]?.value)
        }
    }

    @Test fun keepassxcRandomSlugAlwaysUsesExternalStateIncludingDuringPartialMerge() {
        val original = db()
        val key = DatabaseMetadataMerge.REMOTE_RANDOM_SLUG_KEY
        val localSlug = CustomDataValue("local", time.plusSeconds(10))
        val remoteSlug = CustomDataValue("remote", time)
        val left = original.modifyContent { copy(meta = meta.copy(customData = mapOf(key to localSlug))) }
        val right = original.modifyContent { copy(meta = meta.copy(customData = mapOf(key to remoteSlug))) }
        for (importing in listOf(false, true)) {
            for (preference in listOf(false, true)) {
                val result = Merge.combine(left, right, null, importing, choices = mapOf(null to preference))
                assertFalse(result.blocked)
                assertEquals(remoteSlug, result.database.content.meta.customData[key])
            }
        }
        assertFalse(Merge.combine(left, right, null).blocked)
        assertNull(Merge.combine(left, original, null).database.content.meta.customData[key])
        assertEquals(remoteSlug, Merge.combine(original, right, null).database.content.meta.customData[key])
        val entry = Entry(UUID.randomUUID(), fields = EntryFields(mapOf("A" to EntryValue.Plain("local"))))
        val conflictingLeft = Kdbx.putEntry(left, original.content.group.uuid, entry)
        val conflictingRight = Kdbx.putEntry(right, original.content.group.uuid,
            entry.copy(fields = EntryFields(mapOf("A" to EntryValue.Plain("remote")))))
        val partial = Merge.combine(conflictingLeft, conflictingRight, null)
        assertEquals(listOf("ENTRY_FIELD"), partial.issues.map { it.reason })
        assertEquals(remoteSlug, partial.partialDatabase.content.meta.customData[key])
        val otherLeft = left.modifyContent { copy(meta = meta.copy(customData = meta.customData + ("other" to CustomDataValue("left")))) }
        val otherRight = right.modifyContent { copy(meta = meta.copy(customData = meta.customData + ("other" to CustomDataValue("right")))) }
        val manual = Merge.combine(otherLeft, otherRight, null)
        assertEquals(listOf("CUSTOM_METADATA"), manual.issues.map { it.reason })
        val resolved = Merge.combine(otherLeft, otherRight, null, choices = mapOf(null to false))
        assertEquals("left", resolved.database.content.meta.customData["other"]?.value)
        assertEquals(remoteSlug, resolved.database.content.meta.customData[key])
    }

    @Test fun databaseMetadataIgnoresDatesButRetainsLatestAndDifferentValuesRequireChoice() {
        val original = db()
        val left = original.modifyContent { copy(meta = meta.copy(customData = mapOf("key" to CustomDataValue("same", time)))) }
        val right = original.modifyContent { copy(meta = meta.copy(customData = mapOf("key" to CustomDataValue("same", time.plusSeconds(10))))) }
        for ((a, b) in listOf(left to right, right to left)) {
            val result = Merge.combine(a, b, null)
            assertFalse(result.blocked)
            assertEquals(time.plusSeconds(10), result.database.content.meta.customData.getValue("key").lastModified)
        }
        val different = right.modifyContent { copy(meta = meta.copy(customData = mapOf("key" to CustomDataValue("different", time)))) }
        assertTrue(Merge.combine(left, different, null).issues.any { it.reason == "CUSTOM_METADATA" })
    }

    @Test fun settingsBookkeepingDatesDoNotCreateAConflict() {
        val original = db()
        val left = original.modifyContent { copy(meta = meta.copy(nameChanged = time, descriptionChanged = time, settingsChanged = time)) }
        val right = original.modifyContent { copy(meta = meta.copy(nameChanged = time.plusSeconds(10), descriptionChanged = time.plusSeconds(10), settingsChanged = time.plusSeconds(10))) }
        val result = Merge.combine(left, right, null)
        assertFalse(result.blocked)
        assertEquals(time.plusSeconds(10), result.database.content.meta.nameChanged)
        val different = right.modifyContent { copy(meta = meta.copy(description = "different")) }
        assertTrue(Merge.combine(left, different, null).issues.any { it.reason == "DATABASE_SETTINGS" })
    }

    @Test fun folderBookkeepingDoesNotHideARealFolderEdit() {
        val original = db()
        val folder = Group(UUID.randomUUID(), "folder", times = TimeData.create(time),
            customData = mapOf("key" to CustomDataValue("same", time)))
        val other = folder.copy(expanded = false, lastTopVisibleEntry = UUID.randomUUID(), previousParentGroup = UUID.randomUUID(),
            times = folder.times!!.copy(lastAccessTime = time.plusSeconds(10), usageCount = 3),
            customData = mapOf("key" to CustomDataValue("same", time.plusSeconds(10))))
        val left = original.modifyContent { copy(group = group.copy(groups = listOf(folder))) }
        val right = original.modifyContent { copy(group = group.copy(groups = listOf(other))) }
        val result = Merge.combine(left, right, null)
        assertFalse(result.blocked)
        val merged = result.database.content.group.groups.single()
        assertEquals(3, merged.times!!.usageCount)
        assertEquals(time.plusSeconds(10), merged.customData.getValue("key").lastModified)
        val edited = right.modifyContent { copy(group = group.copy(groups = listOf(other.copy(notes = "edited")))) }
        assertTrue(Merge.combine(left, edited, null).issues.any { it.reason == "GROUP_OR_TYPE_CONFLICT" })
    }

    @Test fun equalEntryValuesRetainProtectionAndLatestMetadataDate() {
        val original = db()
        val entry = Entry(UUID.randomUUID(), fields = EntryFields(mapOf("Password" to EntryValue.Plain("same"))),
            times = TimeData.create(time), customData = mapOf("key" to CustomDataValue("same", time)))
        val other = entry.copy(fields = EntryFields(mapOf("Password" to EntryValue.Encrypted(EncryptedValue.fromString("same")))),
            previousParentGroup = UUID.randomUUID(), customData = mapOf("key" to CustomDataValue("same", time.plusSeconds(10))))
        val left = Kdbx.putEntry(original, original.content.group.uuid, entry)
        val right = Kdbx.putEntry(original, original.content.group.uuid, other)
        for ((a, b) in listOf(left to right, right to left)) {
            val result = Merge.combine(a, b, null)
            assertFalse(result.blocked)
            val merged = Kdbx.entries(result.database).single()
            assertTrue(merged.fields["Password"] is EntryValue.Encrypted)
            assertEquals(time.plusSeconds(10), merged.customData.getValue("key").lastModified)
        }
    }

    @Test fun automaticMetadataDoesNotRemoveOtherManualConflicts() {
        val original = db()
        val entry = Entry(UUID.randomUUID(), fields = EntryFields(mapOf("A" to EntryValue.Plain("left"))), times = TimeData.create(time))
        val left = Kdbx.putEntry(original, original.content.group.uuid, entry).modifyContent {
            copy(meta = meta.copy(customData = mapOf("key" to CustomDataValue("same", time))))
        }
        val right = Kdbx.putEntry(original, original.content.group.uuid, entry.copy(fields = EntryFields(mapOf("A" to EntryValue.Plain("right"))))).modifyContent {
            copy(meta = meta.copy(customData = mapOf("key" to CustomDataValue("same", time.plusSeconds(10)))))
        }
        val result = Merge.combine(left, right, null)
        assertEquals(listOf("ENTRY_FIELD"), result.issues.map { it.reason })
        assertEquals(listOf("A"), result.issues.map { it.fieldName })
    }

    @Test fun inactiveExpiryDatesAreNotConflictingValues() {
        val original = db()
        val entry = Entry(UUID.randomUUID(), times = TimeData.create(time).copy(expires = false, expiryTime = time))
        val other = entry.copy(times = entry.times!!.copy(expiryTime = time.plusSeconds(10)))
        val result = Merge.combine(Kdbx.putEntry(original, original.content.group.uuid, entry),
            Kdbx.putEntry(original, original.content.group.uuid, other), null)
        assertFalse(result.blocked)
        assertFalse(Kdbx.entries(result.database).single().times!!.expires)
    }
}
