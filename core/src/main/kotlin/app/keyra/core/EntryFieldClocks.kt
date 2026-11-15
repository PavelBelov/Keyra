package app.keyra.core

import app.keemobile.kotpass.models.*
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Stored inside encrypted KDBX entry CustomData, including tombstones for removed fields. */
object EntryFieldClocks {
    private const val KEY = "Keyra.FieldClocks.v1"
    fun isServiceKey(key: String) = key == KEY
    private fun Instant.seconds() = truncatedTo(ChronoUnit.SECONDS)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it) }
    internal fun same(a: EntryValue?, b: EntryValue?) = when {
        a == null || b == null -> a == null && b == null
        else -> a.content == b.content && (a is EntryValue.Encrypted) == (b is EntryValue.Encrypted)
    }
    private fun valueHash(value: EntryValue?) = hash(if (value == null) "absent" else
        "present:${value is EntryValue.Encrypted}:${value.content.length}:${value.content}")
    private fun fieldsHash(entry: Entry) = hash(entry.uuid.toString() + "\n" + entry.fields.toSortedMap().entries.joinToString("\n") { (key, value) ->
        "${Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(UTF_8))}:${valueHash(value)}"
    })
    private fun normalized(time: TimeData?) = time?.copy(
        creationTime = time.creationTime?.seconds(), lastModificationTime = time.lastModificationTime?.seconds(),
        lastAccessTime = time.lastAccessTime?.seconds(), locationChanged = time.locationChanged?.seconds(), expiryTime = time.expiryTime?.seconds())
    private fun snapshotKey(entry: Entry) = hash(fieldsHash(entry) + entry.copy(fields = EntryFields(emptyMap()),
        history = emptyList(), times = normalized(entry.times), customData = entry.customData.filterKeys { !isServiceKey(it) }
            .mapValues { (_, value) -> value.copy(lastModified = value.lastModified?.seconds()) }.toSortedMap()).toString())
    private fun historyHashes(entries: List<Entry>): List<String> = buildList {
        add(hash("history:v1"))
        entries.forEach { add(hash(last() + "\n" + snapshotKey(it))) }
    }
    private fun historyHash(entries: List<Entry>) = historyHashes(entries).last()
    private data class Stamp(val hash: String, val time: Instant?)
    private data class Record(val merged: Boolean, val historySize: Int, val historyHash: String, val stamps: Map<String, Stamp>)

    private fun read(entry: Entry): Record? {
        return runCatching {
            val lines = entry.customData[KEY]?.value?.split('\n') ?: return null
            if (lines.size < 6 || lines[0] != "1" || lines[1] !in setOf("edit", "merge")) return null
            val captured = Instant.ofEpochSecond(lines[2].toLong())
            if (entry.times?.lastModificationTime?.seconds() != captured || lines[3] != fieldsHash(entry)) return null
            val count = lines[4].toInt().takeIf { it >= 0 } ?: return null
            val stamps = linkedMapOf<String, Stamp>()
            for (line in lines.drop(6)) {
                val parts = line.split('\t')
                if (parts.size != 3) return null
                val name = String(Base64.getUrlDecoder().decode(parts[0]), UTF_8)
                if (stamps.containsKey(name) || parts[1] != valueHash(entry.fields[name])) return null
                val time = if (parts[2] == "?") null else Instant.ofEpochSecond(parts[2].toLong())
                if (time != null && time > captured) return null
                stamps[name] = Stamp(parts[1], time)
            }
            if (!stamps.keys.containsAll(entry.fields.keys)) return null
            Record(lines[1] == "merge", count, lines[5], stamps)
        }.getOrNull()
    }

    /** No sorting: a merged history is a set of branches, not a chronological edit log. */
    internal fun dates(entry: Entry, base: Entry? = null): Map<String, Instant?> {
        val direct = read(entry)
        val fallback = direct?.stamps?.mapValues { it.value.time }.orEmpty()
        // Unknown clocks on a merged snapshot are deliberate: its branches cannot fill them.
        if (direct != null && (direct.merged || fallback.values.all { it != null })) return fallback
        val snapshots = entry.history + entry.copy(history = emptyList())
        val prefixes = historyHashes(entry.history)
        val records = entry.history.map(::read)
        var anchor = -1
        var known = emptyMap<String, Instant?>()
        for (index in entry.history.indices) {
            val candidate = snapshots[index]
            if (candidate.uuid != entry.uuid) return fallback
            val record = records[index]
            if (record != null && record.historySize == index && record.historyHash == prefixes[index] && (anchor < 0 || record.merged)) {
                anchor = index; known = record.stamps.mapValues { it.value.time }
            }
        }
        if (base != null && base.uuid == entry.uuid && entry.history.size > base.history.size &&
            prefixes[base.history.size] == historyHash(base.history) &&
            snapshotKey(snapshots[base.history.size]) == snapshotKey(base) && base.history.size >= anchor) {
            anchor = base.history.size
            known = read(base)?.stamps?.mapValues { it.value.time }.orEmpty()
        }
        // A legacy history can start at creation. It must still pass every chronological check below.
        if (anchor < 0 && entry.history.isNotEmpty()) {
            val first = snapshots.first()
            val created = first.times?.creationTime?.seconds()
            if (first.customData.containsKey(KEY) || created == null || first.times?.lastModificationTime?.seconds() != created) return fallback
            anchor = 0; known = first.fields.keys.associateWith { created }
        }
        if (anchor < 0) return fallback
        val dates = known.toMutableMap()
        val mergedMarkers = (0..anchor).mapNotNull { index ->
            val record = records[index]
            snapshots[index].customData[KEY]?.value?.takeIf {
                record?.merged == true && record.historySize == index && record.historyHash == prefixes[index]
            }
        }.toMutableSet()
        var previous = snapshots[anchor]
        for (index in anchor + 1 until snapshots.size) {
            val current = snapshots[index]
            val before = previous.times?.lastModificationTime?.seconds() ?: return fallback
            val after = current.times?.lastModificationTime?.seconds() ?: return fallback
            if (current.uuid != entry.uuid || after <= before) return fallback
            val record = records.getOrNull(index)
            if (record?.merged == true) {
                if (record.historySize != index || record.historyHash != prefixes[index]) return fallback
                current.customData[KEY]?.value?.let(mergedMarkers::add)
            }
            // A stale merge marker may be copied by another client. Only traverse it if its
            // original checkpoint is in the verified prefix; never traverse a foreign merged branch.
            if (current.customData[KEY]?.value?.startsWith("1\nmerge\n") == true && record == null &&
                current.customData[KEY]?.value !in mergedMarkers) return fallback
            for (name in previous.fields.keys + current.fields.keys) {
                if (!same(previous.fields[name], current.fields[name])) dates[name] = after
            }
            previous = current
        }
        return dates + fallback.filterValues { it != null }
    }

    fun lastChange(entry: Entry, name: String, base: Entry? = null): Instant? = dates(entry, base)[name]

    internal fun capture(entry: Entry, dates: Map<String, Instant?>, merged: Boolean): Entry {
        val captured = entry.times?.lastModificationTime?.seconds()
            ?: return entry.copy(customData = entry.customData - KEY)
        // Version, snapshot kind/time/field digest, history prefix length/digest, then
        // base64(field name), state digest, last-change epoch second ("?" means unknown).
        val value = buildString {
            append("1\n").append(if (merged) "merge" else "edit").append('\n')
            append(captured.epochSecond).append('\n').append(fieldsHash(entry)).append('\n')
            append(entry.history.size).append('\n').append(historyHash(entry.history))
            for (name in (entry.fields.keys + dates.keys).sorted()) {
                val time = dates[name]?.seconds()?.takeIf { it <= captured }
                append('\n').append(Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(UTF_8)))
                    .append('\t').append(valueHash(entry.fields[name])).append('\t').append(time?.epochSecond ?: "?")
            }
        }
        return entry.copy(customData = entry.customData + (KEY to CustomDataValue(value, captured)))
    }

    fun trackCreation(entry: Entry): Entry = capture(entry,
        entry.fields.keys.associateWith { entry.times?.lastModificationTime }, merged = false)

    internal fun edited(before: Entry, after: Entry): Entry {
        val dates = dates(before).toMutableMap()
        val time = after.times?.lastModificationTime?.seconds()
        for (name in before.fields.keys + after.fields.keys) {
            if (!same(before.fields[name], after.fields[name])) {
                dates[name] = time?.takeUnless { previous -> before.times?.lastModificationTime?.seconds()?.let { previous < it } == true }
            }
        }
        return capture(after, dates, merged = false)
    }
}
