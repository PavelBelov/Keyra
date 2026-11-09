package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class MergeIssue(val objectId: UUID?, val reason: String, val requiresChoice: Boolean)
data class MergeResult(val database: KeePassDatabase, val issues: List<MergeIssue>) {
    val blocked get() = issues.any { it.requiresChoice }
}

/** Three-way object merge. Alternate complete entries survive in history; ambiguous changes stop publication. */
object Merge {
    /** Compare logical values rather than the randomized buffers of protected fields. */
    fun entriesEquivalent(left: Entry, right: Entry): Boolean = left === right || entryKey(left) == entryKey(right)
    private val emptyFields = EntryFields(emptyMap())
    private fun Instant.seconds() = truncatedTo(ChronoUnit.SECONDS)
    private fun normalized(t: TimeData?) = t?.copy(creationTime = t.creationTime?.seconds(), lastModificationTime = t.lastModificationTime?.seconds(),
        lastAccessTime = t.lastAccessTime?.seconds(), locationChanged = t.locationChanged?.seconds(), expiryTime = t.expiryTime?.seconds())
    private class Node(val value: DatabaseElement, val parent: UUID?) {
        // Each snapshot is immutable. Hash protected fields/history at most once per node.
        val entryFingerprint by lazy(LazyThreadSafetyMode.NONE) { (value as? Entry)?.let(::entryKey) }
        fun withParent(parent: UUID?) = Node(value, parent)
    }
    private fun index(db: KeePassDatabase): Map<UUID, Node> = buildMap {
        fun walk(g: Group, parent: UUID?) {
            require(!containsKey(g.uuid)) { "DUPLICATE_UUID" }
            put(g.uuid, Node(g.copy(groups = emptyList(), entries = emptyList()), parent))
            for (e in g.entries) { require(!containsKey(e.uuid)) { "DUPLICATE_UUID" }; put(e.uuid, Node(e, g.uuid)) }
            g.groups.forEach { walk(it, g.uuid) }
        }
        walk(db.content.group, null)
    }
    // EncryptedValue equality compares randomized buffers. Compare logical contents instead.
    internal fun entryKey(e: Entry): String = buildString {
        append(e.copy(fields = emptyFields, history = emptyList(), times = normalized(e.times)).toString())
        e.fields.toSortedMap().forEach { (key, value) ->
            val content = value.content
            append(key.length).append(':').append(key).append(value is EntryValue.Encrypted)
                .append(content.length).append(':').append(content)
        }
        e.history.forEach { append(entryKey(it)) }
    }.let { text -> java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } }
    private fun equivalent(a: Node?, b: Node?): Boolean = when {
        a == null || b == null -> a == b
        a.parent != b.parent -> false
        a.value is Entry && b.value is Entry -> a.entryFingerprint == b.entryFingerprint
        a.value is Group && b.value is Group -> a.value.copy(times = normalized(a.value.times)) == b.value.copy(times = normalized(b.value.times))
        else -> a.value == b.value
    }
    private fun modified(n: Node) = n.value.times?.lastModificationTime ?: Instant.EPOCH
    private fun security(db: KeePassDatabase): String {
        val kdf = when (db) {
            is KeePassDatabase.Ver3x -> "AES:${db.header.transformRounds}"
            is KeePassDatabase.Ver4x -> when (val p = db.header.kdfParameters) {
                is KdfParameters.Aes -> "AES:${p.rounds}"
                is KdfParameters.Argon2 -> "${p.variant}:${p.memory}:${p.iterations}:${p.parallelism}:${p.version}"
            }
        }
        return "${db.header.cipherId}:$kdf"
    }
    private fun metaKey(db: KeePassDatabase): Meta {
        val m = db.modifyBinaries { emptyMap() }.content.meta
        return m.copy(generator = "", headerHash = null, lastSelectedGroup = null, lastTopVisibleGroup = null, customIcons = emptyMap(), customData = emptyMap(),
            settingsChanged = m.settingsChanged?.seconds(), nameChanged = m.nameChanged?.seconds(), descriptionChanged = m.descriptionChanged?.seconds(),
            defaultUserChanged = m.defaultUserChanged?.seconds(), masterKeyChanged = m.masterKeyChanged?.seconds(), recycleBinChanged = m.recycleBinChanged?.seconds(), entryTemplatesGroupChanged = m.entryTemplatesGroupChanged?.seconds())
    }

    fun combine(local: KeePassDatabase, remote: KeePassDatabase, base: KeePassDatabase?, importing: Boolean = false, choices: Map<UUID?, Boolean> = emptyMap()): MergeResult {
        val issues = mutableListOf<MergeIssue>()
        val root = local.content.group.uuid
        if (!importing && root != remote.content.group.uuid) return MergeResult(local, listOf(MergeIssue(null, "DIFFERENT_DATABASE", true)))
        val left = index(local)
        val right = index(remote).toMutableMap()
        if (importing && root != remote.content.group.uuid) right[remote.content.group.uuid] = right.getValue(remote.content.group.uuid).withParent(root)
        val ancestor = base?.let(::index) ?: emptyMap()
        val selected = linkedMapOf<UUID, Node>()
        val deleted = (local.content.deletedObjects + if (importing) emptyList() else remote.content.deletedObjects)
            .groupBy { it.id }.mapValues { (_, v) -> v.maxBy { it.deletionTime } }.toMutableMap()
        for (id in left.keys + right.keys + ancestor.keys) {
            val l = left[id]; val r = right[id]; val b = ancestor[id]
            val deletion = deleted[id]
            if (deletion != null && id != root) {
                val survivor = l ?: r
                if (survivor == null) continue
                if (b != null && equivalent(survivor, b)) continue
                if (choices.containsKey(id)) {
                    val chosen = if (choices[id] == true) r else l
                    if (chosen != null) { selected[id] = chosen; deleted.remove(id) }
                    continue
                }
                issues += MergeIssue(id, "DELETE_EDIT", true)
                selected[id] = survivor
                continue
            }
            val chosen = when {
                equivalent(l, r) -> l
                l == null -> r // Absence alone is not a deletion.
                r == null -> l
                equivalent(l, b) -> r
                equivalent(r, b) -> l
                l.value is Entry && r.value is Entry -> {
                    val newer = if (choices.containsKey(id)) { if (choices[id] == true) r else l } else if (modified(r) > modified(l)) r else l
                    val older = if (newer === l) r else l
                    val current = newer.value as Entry; val alternate = older.value as Entry
                    if (!choices.containsKey(id)) issues += MergeIssue(id, "PARALLEL_ENTRY", modified(l) == modified(r))
                    val histories = (current.history + alternate.history + alternate.copy(history = emptyList()))
                        .distinctBy(::entryKey)
                    Node(current.copy(history = histories), newer.parent)
                }
                else -> {
                    if (choices.containsKey(id)) { if (choices[id] == true) r else l }
                    else { issues += MergeIssue(id, "GROUP_OR_TYPE_CONFLICT", true); l }
                }
            }
            if (chosen != null) selected[id] = chosen
        }
        val childrenByParent = selected.values.groupBy { it.parent }
        fun build(id: UUID, path: Set<UUID>): Group {
            val node = selected[id]?.value as? Group ?: error("MISSING_GROUP")
            if (id in path) error("GROUP_CYCLE")
            val children = childrenByParent[id].orEmpty()
            return node.copy(
                groups = children.filter { it.value is Group }.map { build(it.value.uuid, path + id) },
                entries = children.mapNotNull { it.value as? Entry }
            )
        }
        val tree = try {
            selected.forEach { (id, node) ->
                if (id != root && selected[node.parent]?.value !is Group) error("ORPHAN")
                var cursor: UUID? = id; val seen = hashSetOf<UUID>()
                while (cursor != null) { check(seen.add(cursor)) { "GROUP_CYCLE" }; cursor = selected[cursor]?.parent }
            }
            build(root, emptySet())
        } catch (_: IllegalStateException) {
            return MergeResult(local, issues + MergeIssue(null, "INVALID_GROUP_TREE", true))
        }
        val lm = local.content.meta; val rm = remote.content.meta
        var meta = lm
        if (!importing && metaKey(local) != metaKey(remote)) {
            if (base != null && metaKey(local) == metaKey(base)) meta = rm
            else if (base == null || metaKey(remote) != metaKey(base)) {
                if (choices.containsKey(null)) meta = if (choices[null] == true) rm else lm
                else issues += MergeIssue(null, "DATABASE_SETTINGS", true)
            }
        }
        for (id in lm.customIcons.keys.intersect(rm.customIcons.keys)) {
            if (lm.customIcons[id] != rm.customIcons[id] && !choices.containsKey(id)) issues += MergeIssue(id, "CUSTOM_ICON", true)
        }
        for (key in lm.customData.keys.intersect(rm.customData.keys)) {
            if (lm.customData[key] != rm.customData[key] && !choices.containsKey(null)) issues += MergeIssue(null, "CUSTOM_METADATA", true)
        }
        meta = meta.copy(customIcons = (rm.customIcons + lm.customIcons).mapValues { (id, v) -> if (choices[id] == true) rm.customIcons[id] ?: v else v },
            customData = if (choices[null] == true) lm.customData + rm.customData else rm.customData + lm.customData)
        var template = Kdbx.mergeTemplate(local, remote)
        if (!importing && security(local) != security(remote)) {
            if (base != null && security(remote) == security(base)) Unit
            else if (choices.containsKey(null)) {
                if (choices[null] == true) template = when (remote) {
                    is KeePassDatabase.Ver3x -> (template as? KeePassDatabase.Ver3x)?.copy(header = remote.header) ?: template
                    is KeePassDatabase.Ver4x -> (template as KeePassDatabase.Ver4x).copy(header = remote.header)
                }
            } else issues += MergeIssue(null, "ENCRYPTION_SETTINGS", true)
        }
        if (template is KeePassDatabase.Ver4x && remote is KeePassDatabase.Ver4x) {
            val mine = template.header.publicCustomData; val theirs = remote.header.publicCustomData
            if (mine.keys.intersect(theirs.keys).any { mine[it] != theirs[it] } && !choices.containsKey(null)) issues += MergeIssue(null, "HEADER_METADATA", true)
            template = template.copy(header = template.header.copy(publicCustomData = if (choices[null] == true) mine + theirs else theirs + mine))
        }
        val combined = template.modifyContent { copy(group = tree, meta = meta, deletedObjects = deleted.values.toList()) }
            .modifyBinaries { it + remote.binaries }
        return MergeResult(combined, issues)
    }
}
