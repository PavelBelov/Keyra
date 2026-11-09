package app.keyra

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import app.keyra.core.Merge
import java.util.UUID

/** Identifiers only: no titles, passwords, URLs, notes or attachment contents in the journal. */
fun LocalStore.logChanges(before: KeePassDatabase, after: KeePassDatabase) {
    data class Location<T>(val value: T, val parent: UUID?, val inBin: Boolean)
    fun index(db: KeePassDatabase): Pair<Map<UUID, Location<Group>>, Map<UUID, Location<Entry>>> {
        val groups = linkedMapOf<UUID, Location<Group>>()
        val entries = linkedMapOf<UUID, Location<Entry>>()
        fun walk(group: Group, parent: UUID?, inheritedBin: Boolean) {
            val inBin = inheritedBin || group.uuid == db.content.meta.recycleBinUuid
            if (parent != null) groups[group.uuid] = Location(group, parent, inBin)
            group.entries.forEach { entries[it.uuid] = Location(it, group.uuid, inBin) }
            group.groups.forEach { walk(it, group.uuid, inBin) }
        }
        walk(db.content.group, null, false)
        return groups to entries
    }
    val (oldGroups, oldEntries) = index(before)
    val (newGroups, newEntries) = index(after)
    val events = mutableListOf<String>()
    fun <T> changes(kind: String, old: Map<UUID, Location<T>>, new: Map<UUID, Location<T>>, edited: (T, T) -> Boolean) {
        (old.keys + new.keys).forEach { id ->
            val previous = old[id]
            val current = new[id]
            val event = when {
                previous == null -> "ADDED"
                current == null -> "DELETED"
                !previous.inBin && current.inBin -> "TRASHED"
                previous.inBin && !current.inBin -> "RESTORED"
                previous.parent != current.parent -> "MOVED"
                edited(previous.value, current.value) -> "EDITED"
                else -> null
            }
            if (event != null) events += "${kind}_$event ${id.toString().uppercase()}"
        }
    }
    changes("ENTRY", oldEntries, newEntries) { left, right -> !Merge.entriesEquivalent(left, right) }
    changes("GROUP", oldGroups, newGroups) { left, right ->
        left !== right && left.copy(groups = emptyList(), entries = emptyList()) != right.copy(groups = emptyList(), entries = emptyList())
    }
    logEvents(events)
}
