package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class MergeConflictKey(val objectId: UUID?, val reason: String, val fieldName: String? = null)
data class MergeIssue(val objectId: UUID?, val reason: String, val requiresChoice: Boolean, val fieldName: String? = null) {
    val key get() = MergeConflictKey(objectId, reason, fieldName)
    val isObjectConflict get() = reason in objectReasons

    companion object {
        private val objectReasons = setOf("ENTRY_FIELD", "ENTRY_PROPERTIES", "PARALLEL_ENTRY", "DELETE_EDIT", "GROUP_OR_TYPE_CONFLICT")
    }
}
data class MergeResult(val database: KeePassDatabase, val issues: List<MergeIssue>, val partialDatabase: KeePassDatabase = database) {
    val blocked get() = issues.any { it.requiresChoice }
}

/** Three-way object merge. Alternate complete entries survive in history; fields merge by baseline and verified clocks; ambiguous changes require a choice. */
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
        a.value is Group && b.value is Group -> MergeValues.groupKey(a.value) == MergeValues.groupKey(b.value)
        else -> a.value == b.value
    }
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
        return m.copy(generator = "", headerHash = null, lastSelectedGroup = null, lastTopVisibleGroup = null, customIcons = emptyMap(), customData = emptyMap(), color = m.color.orEmpty(),
            settingsChanged = null, nameChanged = null, descriptionChanged = null,
            defaultUserChanged = null, masterKeyChanged = m.masterKeyChanged?.seconds(), recycleBinChanged = null, entryTemplatesGroupChanged = null)
    }

    private fun buildGroups(root: UUID, nodes: Map<UUID, Node>): Group {
        val children = nodes.values.groupBy { it.parent }
        fun build(id: UUID): Group {
            val group = nodes[id]?.value as? Group ?: error("MISSING_GROUP")
            return group.copy(
                groups = children[id].orEmpty().filter { it.value is Group }.map { build(it.value.uuid) },
                entries = children[id].orEmpty().mapNotNull { it.value as? Entry })
        }
        return build(root)
    }

    /** Keep unresolved objects exactly as they were locally. Defer dependent moves/additions
     * when their parent cannot be made reachable without deciding a structural conflict. */
    private fun partial(local: KeePassDatabase, remote: KeePassDatabase, left: Map<UUID, Node>,
                        selected: Map<UUID, Node>, deleted: Map<UUID, DeletedObject>, issues: List<MergeIssue>): KeePassDatabase {
        val root = local.content.group.uuid
        val visible = selected.toMutableMap()
        for (id in issues.filter { it.requiresChoice && it.isObjectConflict }.mapNotNull { it.objectId }.distinct()) {
            val original = left[id]
            if (original == null) visible.remove(id) else visible[id] = original
        }
        if (issues.any { it.reason == "INVALID_GROUP_TREE" }) {
            visible.toMap().forEach { (id, node) -> left[id]?.let { visible[id] = node.withParent(it.parent) } }
        }
        visible[root] = left.getValue(root)
        fun reachable(id: UUID): Boolean {
            var cursor: UUID? = id
            val seen = hashSetOf<UUID>()
            while (cursor != root) {
                if (cursor == null || !seen.add(cursor)) return false
                val parent = visible[cursor]?.parent ?: return false
                if (visible[parent]?.value !is Group) return false
                cursor = parent
            }
            return true
        }
        fun restoreParents(id: UUID?) {
            if (id == null || reachable(id) && visible[id]?.value is Group) return
            val original = left[id]?.takeIf { it.value is Group } ?: return
            visible[id] = original
            restoreParents(original.parent)
        }
        for ((id, node) in visible.toMap()) {
            if (!reachable(id)) left[id]?.let { original ->
                visible[id] = node.withParent(original.parent)
                restoreParents(original.parent)
            }
        }
        val connected = visible.filterKeys(::reachable)
        val meta = local.content.meta.copy(
            customData = DatabaseMetadataMerge.preferRemote(local.content.meta.customData, remote.content.meta.customData),
            customIcons = remote.content.meta.customIcons + local.content.meta.customIcons)
        return Kdbx.mergeTemplate(local, remote).modifyContent {
            copy(group = buildGroups(root, connected), meta = meta, deletedObjects = deleted.filterKeys { it !in connected }.values.toList())
        }.modifyBinaries { it + remote.binaries }
    }

    fun combine(local: KeePassDatabase, remote: KeePassDatabase, base: KeePassDatabase?, importing: Boolean = false, choices: Map<UUID?, Boolean> = emptyMap(), resolutions: Map<MergeConflictKey, Boolean> = emptyMap()): MergeResult {
        fun choice(id: UUID?, reason: String, field: String? = null): Boolean? =
            resolutions[MergeConflictKey(id, reason, field)] ?: resolutions[MergeConflictKey(id, "PARALLEL_ENTRY")] ?: choices[id]
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
                if (choice(id, "DELETE_EDIT") != null) {
                    val chosen = if (choice(id, "DELETE_EDIT") == true) r else l
                    if (chosen != null) { selected[id] = chosen; deleted.remove(id) }
                    continue
                }
                issues += MergeIssue(id, "DELETE_EDIT", true)
                selected[id] = survivor
                continue
            }
            val chosen = when {
                equivalent(l, r) -> if (l?.value is Group && r?.value is Group)
                    Node(MergeValues.group(l.value, r.value), l.parent) else l
                l == null -> r // Absence alone is not a deletion.
                r == null -> l
                l.value is Entry && r.value is Entry -> {
                    val result = EntryMerge.combine(l.value, r.value, b?.value as? Entry, l.parent, r.parent, b?.parent) { reason, field ->
                        choice(id, reason, field)
                    }
                    issues += result.issues
                    Node(result.entry, result.parent)
                }
                equivalent(l, b) -> r
                equivalent(r, b) -> l
                else -> {
                    if (choice(id, "GROUP_OR_TYPE_CONFLICT") != null) { if (choice(id, "GROUP_OR_TYPE_CONFLICT") == true) r else l }
                    else { issues += MergeIssue(id, "GROUP_OR_TYPE_CONFLICT", true); l }
                }
            }
            if (chosen != null) selected[id] = chosen
        }
        fun buildTree(): Group {
            selected.forEach { (id, node) ->
                if (id != root && selected[node.parent]?.value !is Group) error("ORPHAN")
                var cursor: UUID? = id; val seen = hashSetOf<UUID>()
                while (cursor != null) { check(seen.add(cursor)) { "GROUP_CYCLE" }; cursor = selected[cursor]?.parent }
            }
            return buildGroups(root, selected)
        }
        val tree = try { buildTree() } catch (_: IllegalStateException) {
            val external = choice(null, "INVALID_GROUP_TREE")
            if (external == null) {
                issues += MergeIssue(null, "INVALID_GROUP_TREE", true)
                local.content.group // Keep collecting settings conflicts as well.
            } else {
                // A structural choice takes precedence over individual move choices. Retain merged values
                // and independent additions, restoring ancestors needed to keep them reachable.
                val preferred = if (external) right else left
                val alternate = if (external) left else right
                for ((id, node) in selected.toMap()) {
                    preferred[id]?.let { selected[id] = node.withParent(it.parent) }
                }
                fun restoreParent(id: UUID, path: Set<UUID>) {
                    check(id !in path) { "GROUP_CYCLE" }
                    val node = selected.getValue(id)
                    val parent = node.parent ?: return
                    if (selected[parent]?.value !is Group) {
                        val ancestorNode = preferred[parent] ?: alternate[parent] ?: error("ORPHAN")
                        check(ancestorNode.value is Group) { "ORPHAN" }
                        selected[parent] = ancestorNode
                        deleted.remove(parent)
                    }
                    restoreParent(parent, path + id)
                }
                try {
                    selected.keys.toList().forEach { restoreParent(it, emptySet()) }
                    buildTree()
                } catch (_: IllegalStateException) {
                    issues += MergeIssue(null, "INVALID_GROUP_TREE", true)
                    local.content.group
                }
            }
        }
        val lm = local.content.meta; val rm = remote.content.meta
        val localSettings = if (importing) null else metaKey(local)
        val remoteSettings = if (importing) null else metaKey(remote)
        val baseSettings = if (importing) null else base?.let(::metaKey)
        var meta = lm
        if (!importing && localSettings != remoteSettings) {
            if (base != null && localSettings == baseSettings) meta = rm
            else if (base == null || remoteSettings != baseSettings) {
                if (choice(null, "DATABASE_SETTINGS") != null) meta = if (choice(null, "DATABASE_SETTINGS") == true) rm else lm
                else issues += MergeIssue(null, "DATABASE_SETTINGS", true)
            }
        }
        if (!importing && localSettings == remoteSettings) meta = meta.copy(
            settingsChanged = MergeValues.latest(lm.settingsChanged, rm.settingsChanged),
            nameChanged = MergeValues.latest(lm.nameChanged, rm.nameChanged),
            descriptionChanged = MergeValues.latest(lm.descriptionChanged, rm.descriptionChanged),
            defaultUserChanged = MergeValues.latest(lm.defaultUserChanged, rm.defaultUserChanged),
            recycleBinChanged = MergeValues.latest(lm.recycleBinChanged, rm.recycleBinChanged),
            entryTemplatesGroupChanged = MergeValues.latest(lm.entryTemplatesGroupChanged, rm.entryTemplatesGroupChanged))
        for (id in lm.customIcons.keys.intersect(rm.customIcons.keys)) {
            val a = lm.customIcons.getValue(id); val b = rm.customIcons.getValue(id)
            if ((a.name.orEmpty() != b.name.orEmpty() || a.lastModified?.seconds() != b.lastModified?.seconds() || !a.data.contentEquals(b.data)) &&
                choice(id, "CUSTOM_ICON") == null) issues += MergeIssue(id, "CUSTOM_ICON", true)
        }
        for (key in lm.customData.keys.intersect(rm.customData.keys)) {
            if (!DatabaseMetadataMerge.requiresChoice(key)) continue
            if (lm.customData[key]?.value != rm.customData[key]?.value && choice(null, "CUSTOM_METADATA") == null) issues += MergeIssue(null, "CUSTOM_METADATA", true)
        }
        meta = meta.copy(customIcons = (rm.customIcons + lm.customIcons).mapValues { (id, v) -> if (choice(id, "CUSTOM_ICON") == true) rm.customIcons[id] ?: v else v },
            customData = DatabaseMetadataMerge.merge(lm.customData, rm.customData, choice(null, "CUSTOM_METADATA") == true))
        var template = Kdbx.mergeTemplate(local, remote)
        if (!importing && security(local) != security(remote)) {
            if (base != null && security(remote) == security(base)) Unit
            else if (choice(null, "ENCRYPTION_SETTINGS") != null) {
                if (choice(null, "ENCRYPTION_SETTINGS") == true) template = when (remote) {
                    is KeePassDatabase.Ver3x -> (template as? KeePassDatabase.Ver3x)?.copy(header = remote.header) ?: template
                    is KeePassDatabase.Ver4x -> (template as KeePassDatabase.Ver4x).copy(header = remote.header)
                }
            } else issues += MergeIssue(null, "ENCRYPTION_SETTINGS", true)
        }
        if (template is KeePassDatabase.Ver4x && remote is KeePassDatabase.Ver4x) {
            val mine = template.header.publicCustomData; val theirs = remote.header.publicCustomData
            if (mine.keys.intersect(theirs.keys).any { mine[it] != theirs[it] } && choice(null, "HEADER_METADATA") == null) issues += MergeIssue(null, "HEADER_METADATA", true)
            template = template.copy(header = template.header.copy(publicCustomData = if (choice(null, "HEADER_METADATA") == true) mine + theirs else theirs + mine))
        }
        val combined = template.modifyContent { copy(group = tree, meta = meta, deletedObjects = deleted.values.toList()) }
            .modifyBinaries { it + remote.binaries }
        return MergeResult(combined, issues, if (issues.any { it.requiresChoice }) partial(local, remote, left, selected, deleted, issues) else combined)
    }
}
