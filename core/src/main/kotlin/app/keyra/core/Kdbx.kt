package app.keyra.core

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.*
import java.io.File
import java.time.Instant
import java.util.UUID

object Kdbx {
    fun mergeTemplate(local: KeePassDatabase, remote: KeePassDatabase): KeePassDatabase = when {
        local is KeePassDatabase.Ver3x && remote is KeePassDatabase.Ver4x -> {
            val upgraded = KeePassDatabase.Ver4x.create(local.content.group.name, local.content.meta, local.credentials)
            upgraded.copy(content = local.content, header = upgraded.header.copy(
                cipherId = local.header.cipherId, compression = local.header.compression,
                kdfParameters = KdfParameters.Aes(local.header.transformRounds, local.header.transformSeed),
                publicCustomData = remote.header.publicCustomData))
        }
        local is KeePassDatabase.Ver4x && remote is KeePassDatabase.Ver4x && remote.header.version.minor > local.header.version.minor -> local.copy(header = local.header.copy(version = remote.header.version))
        else -> local
    }
    fun credentials(password: String, keyfile: ByteArray? = null): Credentials {
        require(keyfile == null || (!keyfile.hasDoctype() && keyfile.size <= 1024 * 1024)) { "UNSUPPORTED_KEYFILE" }
        if (password.isEmpty() && keyfile != null) return Credentials.from(keyfile.copyOf())
        val value = EncryptedValue.fromString(password)
        return if (keyfile == null) Credentials.from(value) else Credentials.from(value, keyfile.copyOf())
    }
    /** Export the effective key, preserving access regardless of the source file format. */
    fun exportKeyfile(keyfile: ByteArray): ByteArray {
        val key = requireNotNull(credentials("", keyfile).key).getBinary()
        return try {
            require(key.size == 32) { "UNSUPPORTED_KEYFILE" }
            Credentials.createKeyfile(key).toByteArray(Charsets.UTF_8)
        } finally { key.fill(0) }
    }
    fun create(credentials: Credentials, rootName: String = "Passwords", recycleBinName: String = "Recycle Bin"): KeePassDatabase {
        val bin = Group.createRecycleBin(recycleBinName)
        return KeePassDatabase.Ver4x.create(rootName, Meta(generator = "Keyra", name = "Keyra", recycleBinEnabled = true, recycleBinUuid = bin.uuid, recycleBinChanged = Instant.now()), credentials)
            .modifyContent { copy(group = group.copy(groups = listOf(bin))) }
    }
    fun read(file: File, credentials: Credentials): KeePassDatabase = file.inputStream().use { KeePassDatabase.decode(it, credentials, contentParser = StrictXml) }.also {
        require(it.header.version.major.toInt() in 3..4 && it.header.version.minor <= 1) { "UNSUPPORTED_VERSION" }
        val ids = hashSetOf<UUID>()
        it.traverse { element -> require(ids.add(element.uuid)) { "DUPLICATE_UUID" } }
    }
    fun write(db: KeePassDatabase, target: File) = atomicWrite(target) { file -> file.outputStream().use { db.encode(it) } }
    fun group(db: KeePassDatabase, id: UUID): Group? = db.getGroupBy { uuid == id }
    fun entries(db: KeePassDatabase): List<Entry> = buildList { db.traverse { if (it is Entry) add(it) } }
    /** Reuse untouched branches instead of copying the whole group tree on every edit. */
    fun replaceGroup(root: Group, id: UUID, update: (Group) -> Group): Group {
        if (root.uuid == id) return update(root)
        var changed = false
        val groups = root.groups.map { group ->
            replaceGroup(group, id, update).also { if (it !== group) changed = true }
        }
        return if (changed) root.copy(groups = groups) else root
    }
    fun putEntry(db: KeePassDatabase, parent: UUID, entry: Entry): KeePassDatabase = db.modifyContent {
        copy(group = replaceGroup(group, parent) { g -> g.copy(entries = g.entries.filterNot { it.uuid == entry.uuid } + entry) })
    }
    fun removeEntry(db: KeePassDatabase, id: UUID): KeePassDatabase = db.modifyContent {
        fun strip(g: Group): Group = g.copy(entries = g.entries.filterNot { it.uuid == id }, groups = g.groups.map(::strip))
        copy(group = strip(group))
    }
    fun removeGroup(db: KeePassDatabase, id: UUID): KeePassDatabase = db.modifyContent {
        require(id != group.uuid) { "ROOT_GROUP" }
        fun strip(g: Group): Group = g.copy(groups = g.groups.filterNot { it.uuid == id }.map(::strip))
        copy(group = strip(group))
    }
    fun edit(entry: Entry, fields: Map<String, EntryValue>, tags: List<String> = entry.tags): Entry = entry.copy(
        fields = EntryFields(fields), tags = tags.toList(), history = entry.history + entry.copy(history = emptyList()),
        times = (entry.times ?: TimeData.create()).copy(lastModificationTime = Instant.now())
    )
}
