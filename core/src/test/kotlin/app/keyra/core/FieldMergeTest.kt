package app.keyra.core

import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FieldMergeTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private fun at(second: Long) = start.plusSeconds(second)
    private fun entry(tracked: Boolean = true): Entry {
        val entry = Entry(UUID.randomUUID(), fields = EntryFields(mapOf(
            "A" to EntryValue.Plain("a0"), "B" to EntryValue.Plain("b0"),
            "C" to EntryValue.Plain("c0"), "D" to EntryValue.Plain("d0"))), times = TimeData.create(start))
        return if (tracked) EntryFieldClocks.trackCreation(entry) else entry
    }
    private fun edit(entry: Entry, second: Long, vararg changes: Pair<String, String?>, external: Boolean = false, history: Boolean = true): Entry {
        val fields = entry.fields.toMutableMap()
        changes.forEach { (key, value) -> if (value == null) fields.remove(key) else fields[key] = EntryValue.Plain(value) }
        val result = entry.copy(fields = EntryFields(fields),
            history = if (history) entry.history + entry.copy(history = emptyList()) else emptyList(),
            times = entry.times!!.copy(lastModificationTime = at(second)))
        return if (external) result else EntryFieldClocks.edited(entry, result)
    }
    private fun merge(local: Entry, remote: Entry, base: Entry?, choices: Map<MergeConflictKey, Boolean> = emptyMap()): MergeResult {
        val db = Kdbx.create(Kdbx.credentials("password"))
        return Merge.combine(Kdbx.putEntry(db, db.content.group.uuid, local),
            Kdbx.putEntry(db, db.content.group.uuid, remote), base?.let { Kdbx.putEntry(db, db.content.group.uuid, it) }, resolutions = choices)
    }
    private fun result(merge: MergeResult) = Kdbx.entries(merge.database).single()
    private fun values(entry: Entry) = listOf("A", "B", "C", "D").map { entry.fields[it]?.content }

    @Test fun combinesLatestChangeOfEachField() {
        val base = entry()
        val local = edit(edit(base, 10, "A" to "a1", "B" to "b1", "C" to "c1"), 30, "C" to "c3")
        val remote = edit(base, 20, "B" to "b2", "C" to "c2")
        val merged = merge(local, remote, base)
        assertFalse(merged.blocked); assertTrue(merged.issues.isEmpty())
        assertEquals(listOf("a1", "b2", "c3", "d0"), values(result(merged)))
        assertEquals(at(10), EntryFieldClocks.lastChange(result(merged), "A"))
        assertEquals(at(20), EntryFieldClocks.lastChange(result(merged), "B"))
        assertEquals(at(30), EntryFieldClocks.lastChange(result(merged), "C"))
    }
    @Test fun restoresDatesFromLegacyHistory() {
        val base = entry(tracked = false)
        val local = edit(edit(base, 10, "A" to "a1", "B" to "b1", "C" to "c1", external = true), 30, "C" to "c3", external = true)
        val remote = edit(base, 20, "B" to "b2", "C" to "c2", external = true)
        val merged = merge(local, remote, base)
        assertFalse(merged.blocked)
        assertEquals(listOf("a1", "b2", "c3", "d0"), values(result(merged)))
    }
    @Test fun unrelatedLaterEditDoesNotMakeEveryFieldNewer() {
        val base = entry()
        val local = edit(edit(base, 10, "B" to "b1"), 30, "A" to "a1")
        val remote = edit(base, 20, "B" to "b2")
        val merged = merge(local, remote, base)
        assertFalse(merged.blocked); assertEquals("b2", result(merged).fields["B"]?.content)
    }
    @Test fun mergeDoesNotRedateSelectedFields() {
        val base = entry()
        val first = result(merge(edit(base, 10, "A" to "a1"), edit(base, 20, "B" to "b2"), base))
        val lateBranch = edit(base, 15, "A" to "a2")
        val merged = merge(first, lateBranch, base)
        assertFalse(merged.blocked)
        assertEquals(listOf("a2", "b2", "c0", "d0"), values(result(merged)))
        assertEquals(at(15), EntryFieldClocks.lastChange(result(merged), "A"))
    }
    @Test fun perFieldTimeCanPredateTheLatestCommonEntrySave() {
        val base = entry()
        val common = result(merge(edit(base, 10, "A" to "a1"), edit(base, 20, "B" to "b2"), base))
        val local = edit(common, 40, "D" to "d1")
        val olderBranch = edit(base, 15, "A" to "a2")
        val merged = merge(local, olderBranch, common)
        assertFalse(merged.blocked)
        assertEquals(listOf("a2", "b2", "c0", "d1"), values(result(merged)))
    }
    @Test fun externalEditsAfterMergeUseCheckpointRatherThanFlattenedBranches() {
        val base = entry()
        val common = result(merge(edit(base, 10, "A" to "a1"), edit(base, 20, "B" to "b2"), base))
        val local = edit(edit(common, 25, "B" to "b3", external = true), 40, "C" to "c1", external = true)
        val remote = edit(common, 30, "B" to "b4", external = true)
        val merged = merge(local, remote, common)
        assertFalse(merged.blocked)
        assertEquals(listOf("a1", "b4", "c1", "d0"), values(result(merged)))
    }
    @Test fun equalDatesRequireOnlyConflictingFieldToBeChosen() {
        val base = entry()
        val local = edit(base, 10, "A" to "a1", "B" to "left")
        val remote = edit(base, 10, "B" to "right", "C" to "c1")
        val pending = merge(local, remote, base)
        assertEquals(listOf(MergeConflictKey(base.uuid, "ENTRY_FIELD", "B")), pending.issues.map { it.key })
        val resolved = merge(local, remote, base, mapOf(pending.issues.single().key to true))
        assertFalse(resolved.blocked)
        assertEquals(listOf("a1", "right", "c1", "d0"), values(result(resolved)))
        assertTrue(result(resolved).history.any { it.fields["B"]?.content == "left" })
    }
    @Test fun propertyChoiceDoesNotDiscardAutomaticallyMergedFields() {
        val base = entry()
        val local = edit(base, 10, "A" to "a1").copy(tags = listOf("local"))
        val remote = edit(base, 20, "B" to "b2").copy(tags = listOf("remote"))
        val pending = merge(local, remote, base)
        assertEquals(listOf("ENTRY_PROPERTIES"), pending.issues.map { it.reason })
        val resolved = merge(local, remote, base, mapOf(pending.issues.single().key to true))
        assertFalse(resolved.blocked)
        assertEquals(listOf("a1", "b2", "c0", "d0"), values(result(resolved)))
        assertEquals(listOf("remote"), result(resolved).tags)
    }
    @Test fun eachUnresolvedFieldHasAnIndependentChoice() {
        val base = entry()
        val local = edit(base, 10, "A" to "left-a", "B" to "left-b")
        val remote = edit(base, 10, "A" to "right-a", "B" to "right-b")
        val pending = merge(local, remote, base)
        assertEquals(setOf("A", "B"), pending.issues.map { it.fieldName }.toSet())
        val resolved = merge(local, remote, base, mapOf(
            MergeConflictKey(base.uuid, "ENTRY_FIELD", "A") to false,
            MergeConflictKey(base.uuid, "ENTRY_FIELD", "B") to true))
        assertFalse(resolved.blocked)
        assertEquals(listOf("left-a", "right-b", "c0", "d0"), values(result(resolved)))
    }
    @Test fun missingHistoryNeverUsesWholeEntryTimeAsFieldTime() {
        val base = entry(tracked = false)
        val local = edit(base, 10, "B" to "left", external = true, history = false)
        val remote = edit(base, 20, "B" to "right", external = true, history = false)
        val pending = merge(local, remote, base)
        assertEquals(listOf("B"), pending.issues.map { it.fieldName })
        assertTrue(pending.blocked)
    }
    @Test fun staleMetadataDoesNotSupplyADateForAnExternalChangeWithoutHistory() {
        val base = entry()
        val local = edit(base, 30, "B" to "left", external = true, history = false)
        val remote = edit(base, 20, "B" to "right")
        assertNull(EntryFieldClocks.lastChange(local, "B", base))
        assertTrue(merge(local, remote, base).blocked)
    }
    @Test fun fieldDeletionHasItsOwnClockAndDiffersFromEmptyValue() {
        val base = entry()
        val deleted = edit(base, 20, "B" to null)
        val olderEdit = edit(base, 10, "B" to "old")
        val removed = merge(deleted, olderEdit, base)
        assertFalse(removed.blocked); assertFalse(result(removed).fields.containsKey("B"))
        assertEquals(at(20), EntryFieldClocks.lastChange(result(removed), "B"))
        val newerEmpty = edit(base, 30, "B" to "")
        val restored = merge(deleted, newerEmpty, base)
        assertFalse(restored.blocked); assertTrue(result(restored).fields.containsKey("B"))
        assertEquals("", result(restored).fields["B"]?.content)
    }
    @Test fun revertingAFieldStillCountsAsALaterChange() {
        val base = entry()
        val reverted = edit(edit(base, 10, "A" to "a1"), 30, "A" to "a0")
        val remote = edit(base, 20, "A" to "a2")
        val merged = merge(reverted, remote, base)
        assertFalse(merged.blocked); assertEquals("a0", result(merged).fields["A"]?.content)
    }
    @Test fun malformedMetadataAndUnorderedHistoryRemainAmbiguous() {
        val base = entry(tracked = false)
        val local = edit(edit(base, 20, "B" to "b1", external = true), 30, "B" to "b2", external = true)
        val unordered = local.copy(history = local.history.reversed(), customData = mapOf("Keyra.FieldClocks.v1" to CustomDataValue("broken")))
        val remote = edit(base, 25, "B" to "b3", external = true)
        assertTrue(merge(unordered, remote, base).blocked)
    }
    @Test fun importedFieldsWithoutACommonVersionNeedReliableDates() {
        val base = entry(tracked = false)
        val local = edit(base, 10, "B" to "left", external = true, history = false)
        val remote = edit(base, 20, "B" to "right", external = true, history = false)
        assertTrue(merge(local, remote, null).blocked)
        val tracked = entry()
        val automatic = merge(edit(tracked, 10, "B" to "left"), edit(tracked, 20, "B" to "right"), null)
        assertFalse(automatic.blocked); assertEquals("right", result(automatic).fields["B"]?.content)
    }
    @Test fun missingDatesInAnEditCheckpointCanBeRecoveredWithTheBaseline() {
        val origin = entry(tracked = false)
        // The retained history starts after creation; only the baseline identifies its anchor.
        val base = edit(origin, 5, "D" to "d1", external = true, history = false)
        val external = edit(base, 10, "B" to "b1", external = true)
        val local = edit(external, 30, "A" to "a1")
        val remote = edit(base, 20, "B" to "b2", external = true)
        val merged = merge(local, remote, base)
        assertFalse(merged.blocked)
        assertEquals(listOf("a1", "b2", "c0", "d1"), values(result(merged)))
    }
    @Test fun protectedValuesAreComparedLogicallyAndProtectionChangesAreTracked() {
        val base = entry()
        val encrypted = EntryValue.Encrypted(app.keemobile.kotpass.cryptography.EncryptedValue.fromString("secret"))
        val local = EntryFieldClocks.edited(base, base.copy(fields = base.fields + ("B" to encrypted),
            history = listOf(base.copy(history = emptyList())), times = base.times!!.copy(lastModificationTime = at(10))))
        val remote = edit(base, 20, "A" to "a1")
        val merged = merge(local, remote, base)
        assertFalse(merged.blocked)
        assertTrue(result(merged).fields["B"] is EntryValue.Encrypted)
        assertEquals("secret", result(merged).fields["B"]?.content)
        assertEquals(at(10), EntryFieldClocks.lastChange(result(merged), "B"))
    }
    @Test fun backwardsLocalClockDoesNotCreateATrustedNewFieldDate() {
        val base = edit(entry(), 20, "B" to "b1")
        val local = edit(base, 10, "B" to "backwards")
        val remote = edit(base, 30, "B" to "remote")
        assertNull(EntryFieldClocks.lastChange(local, "B", base))
        assertTrue(merge(local, remote, base).blocked)
    }
    @Test fun trimmedHistoryAfterMergeCannotFabricateFieldDates() {
        val base = entry()
        val common = result(merge(edit(base, 10, "A" to "a1"), edit(base, 20, "B" to "b2"), base))
        val local = edit(common, 40, "B" to "local", external = true).copy(history = emptyList())
        val remote = edit(common, 30, "B" to "remote")
        assertTrue(merge(local, remote, common).blocked)
    }
    @Test fun trackedClocksAndMergedHistorySurviveKdbxRoundtrip() {
        val base = entry()
        val merged = merge(edit(base, 10, "A" to "a1"), edit(base, 20, "B" to "b2"), base)
        val db = merged.database as KeePassDatabase.Ver4x
        val fast = db.copy(header = db.header.copy(kdfParameters = KdfParameters.Aes(2UL, db.header.masterSeed)))
        val file = temp.newFile(); Kdbx.write(fast, file)
        val read = Kdbx.entries(Kdbx.read(file, db.credentials)).single()
        assertEquals(listOf("a1", "b2", "c0", "d0"), values(read))
        assertEquals(at(10), EntryFieldClocks.lastChange(read, "A"))
        assertEquals(at(20), EntryFieldClocks.lastChange(read, "B"))
        assertTrue(read.history.any { it.fields["A"]?.content == "a1" })
    }
}
