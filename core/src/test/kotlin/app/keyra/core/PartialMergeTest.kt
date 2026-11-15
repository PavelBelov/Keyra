package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntryBy
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PartialMergeTest {
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private fun entry() = Entry(UUID.randomUUID(), fields = EntryFields.of(
        "Title" to EntryValue.Plain("Synthetic"), "UserName" to EntryValue.Plain("user0"),
        "Password" to EntryValue.Plain("password0")), times = TimeData.create(time))
    private fun add(db: KeePassDatabase, value: Entry) = Kdbx.putEntry(db, db.content.group.uuid, value)
    private fun edit(value: Entry, second: Long, key: String, content: String) = value.copy(
        fields = value.fields + (key to EntryValue.Plain(content)), history = value.history + value.copy(history = emptyList()),
        times = value.times!!.copy(lastModificationTime = time.plusSeconds(second)))

    @Test fun independentEntriesAndAutomaticMergesAreVisibleWhileConflictingEntryStaysLocal() {
        val conflict = entry(); val automatic = entry(); val removed = entry()
        val base = add(add(add(Kdbx.create(Kdbx.credentials("synthetic")), conflict), automatic), removed)
        val localConflict = edit(conflict, 10, "Password", "left")
        val remoteConflict = edit(conflict, 10, "Password", "right").copy(fields = conflict.fields + mapOf(
            "Password" to EntryValue.Plain("right"), "UserName" to EntryValue.Plain("remote-user")))
        val localOnly = entry(); val remoteOnly = entry()
        val local = add(add(add(base, localConflict), edit(automatic, 10, "UserName", "auto-user")), localOnly)
        val remote = Kdbx.removeEntry(add(add(add(base, remoteConflict), edit(automatic, 20, "Password", "auto-password")), remoteOnly), removed.uuid)
            .modifyContent { copy(deletedObjects = deletedObjects + DeletedObject(removed.uuid, time.plusSeconds(20))) }
        val result = Merge.combine(local, remote, base)
        assertTrue(result.blocked)
        val visible = result.partialDatabase
        assertTrue(Merge.entriesEquivalent(localConflict, visible.getEntryBy { uuid == conflict.uuid }!!))
        val merged = visible.getEntryBy { uuid == automatic.uuid }!!
        assertEquals("auto-user", merged.fields.userName?.content)
        assertEquals("auto-password", merged.fields.password?.content)
        assertNotNull(visible.getEntryBy { uuid == remoteOnly.uuid })
        assertNotNull(visible.getEntryBy { uuid == localOnly.uuid })
        assertNull(visible.getEntryBy { uuid == removed.uuid })
        assertEquals(listOf(conflict.uuid), result.issues.mapNotNull { it.objectId }.distinct())
    }

    @Test fun cyclicMovesDoNotHideIndependentRemoteEntries() {
        val a = Group(UUID.randomUUID(), "A"); val b = Group(UUID.randomUUID(), "B")
        val base = Kdbx.create(Kdbx.credentials("synthetic")).modifyContent { copy(group = group.copy(groups = listOf(a, b))) }
        val local = base.modifyContent { copy(group = group.copy(groups = listOf(b.copy(groups = listOf(a))))) }
        val added = entry()
        val remote = add(base.modifyContent { copy(group = group.copy(groups = listOf(a.copy(groups = listOf(b))))) }, added)
        val result = Merge.combine(local, remote, base)
        assertTrue(result.blocked)
        assertNotNull(result.partialDatabase.getEntryBy { uuid == added.uuid })
        assertEquals(a.uuid, Kdbx.group(result.partialDatabase, b.uuid)!!.groups.single().uuid)
    }

    @Test fun additionsUnderAConflictingDeletedParentAreDeferredButOtherAdditionsAreVisible() {
        val folder = Group(UUID.randomUUID(), "Original")
        val base = Kdbx.create(Kdbx.credentials("synthetic")).modifyContent { copy(group = group.copy(groups = listOf(folder))) }
        val local = Kdbx.removeGroup(base, folder.uuid).modifyContent {
            copy(deletedObjects = listOf(DeletedObject(folder.uuid, time.plusSeconds(10))))
        }
        val child = entry(); val independent = entry()
        val remote = add(base.modifyContent { copy(group = group.copy(groups = listOf(folder.copy(name = "Changed", entries = listOf(child))))) }, independent)
        val result = Merge.combine(local, remote, base)
        assertTrue(result.blocked)
        assertNull(Kdbx.group(result.partialDatabase, folder.uuid))
        assertNull(result.partialDatabase.getEntryBy { uuid == child.uuid })
        assertNotNull(result.partialDatabase.getEntryBy { uuid == independent.uuid })
        val resolved = Merge.combine(local, remote, base, resolutions = result.issues.associate { it.key to true })
        assertFalse(resolved.blocked)
        assertNotNull(resolved.database.getEntryBy { uuid == child.uuid })
    }

    @Test fun settingsConflictDoesNotPreventReadingIndependentRecords() {
        val base = Kdbx.create(Kdbx.credentials("synthetic"))
        val local = base.modifyContent { copy(meta = meta.copy(name = "Local name")) }
        val added = entry()
        val remote = add(base.modifyContent { copy(meta = meta.copy(name = "Remote name")) }, added)
        val result = Merge.combine(local, remote, base)
        assertTrue(result.blocked)
        assertEquals("Local name", result.partialDatabase.content.meta.name)
        assertNotNull(result.partialDatabase.getEntryBy { uuid == added.uuid })
    }
}
