package app.keyra

import android.content.Context
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.*
import app.keyra.core.*
import java.util.UUID

/** Builds version fields independently of the form and its memory-only cache. */
internal class ConflictPreview(
    private val context: Context,
    review: ConflictReview,
    private val baseObjects: Map<UUID, ConflictReview.Located>
) {
    private val local = review.local
    private val remote = review.remote
    private val localObjects = review.localObjects
    private val remoteObjects = review.remoteObjects
    private data class PreviewField(val key: String, val name: String, val value: EntryValue, val present: Boolean = true)

    fun details(issue: MergeIssue): Pair<List<Pair<String, EntryValue>>, List<Pair<String, EntryValue>>> {
        val left = fields(issue, false)
        val right = fields(issue, true)
        val compare = !issue.isObjectConflict ||
            (localObjects[issue.objectId] != null && remoteObjects[issue.objectId] != null)
        return visible(left, right, compare) to visible(right, left, compare)
    }

    private fun visible(fields: List<PreviewField>, alternate: List<PreviewField>, compare: Boolean): List<Pair<String, EntryValue>> {
        val visible = if (compare) {
            val other = alternate.associateBy { it.key }
            val ownKeys = fields.mapTo(hashSetOf()) { it.key }
            val missing = alternate.filter { it.key !in ownKeys }.map {
                it.copy(value = EntryValue.Plain(L10n.text(context, R.string.merge_field_absent)), present = false)
            }
            (fields + missing).filter { field ->
                val otherField = other[field.key]
                // Compare logical values, never randomized protected buffers or translated labels.
                otherField == null || field.present != otherField.present || field.value.content != otherField.value.content
            }
        } else fields.filter { it.value.content.isNotEmpty() }
        return visible.map { it.name to it.value }
    }

    /** Protected values stay wrapped; stable keys distinguish entry fields from metadata labels. */
    private fun fields(issue: MergeIssue, external: Boolean): List<PreviewField> {
        val db = if (external) remote else local
        val element = (if (external) remoteObjects else localObjects)[issue.objectId]?.value
        val otherEntry = (if (external) localObjects else remoteObjects)[issue.objectId]?.value as? Entry
        val objects = if (external) remoteObjects else localObjects
        val parent = objects[issue.objectId]?.parent
        val otherParent = (if (external) localObjects else remoteObjects)[issue.objectId]?.parent
        val location = info(R.string.merge_location, protectedValue(reference(parent, otherParent, external)))
        if (issue.reason == "ENTRY_FIELD" && element is Entry) {
            val name = requireNotNull(issue.fieldName)
            val stamp = EntryFieldClocks.lastChange(element, name, baseObjects[issue.objectId]?.value as? Entry)
            return listOf(field(name, element.fields[name]),
                info(R.string.merge_field_changed_at, EntryValue.Plain(stamp?.let(::date) ?: text(R.string.merge_field_time_unknown))))
        }
        if (issue.isObjectConflict) return when (element) {
            is Entry -> entryFields(element, otherEntry, issue, location)
            is Group -> groupFields(element, location)
            else -> emptyList()
        }
        if (issue.reason == "CUSTOM_METADATA") return custom(
            db.content.meta.customData.filterKeys(DatabaseMetadataMerge::requiresChoice),
            (if (external) local else remote).content.meta.customData.keys.filter(DatabaseMetadataMerge::requiresChoice).toSet())
        if (issue.reason == "CUSTOM_ICON") {
            val icon = db.content.meta.customIcons[issue.objectId]
            return listOf(info(R.string.field_title, protectedValue(icon?.name)),
                info(R.string.merge_modified, EntryValue.Plain(date(icon?.lastModified))))
        }
        if (issue.reason == "DATABASE_SETTINGS") return databaseSettings(db, external)
        if (issue.reason == "HEADER_METADATA") return (db as? KeePassDatabase.Ver4x)?.header?.publicCustomData.orEmpty()
            .toSortedMap().map { (key, value) -> PreviewField("header:$key", key, protectedValue(value)) }
        if (issue.reason == "ENCRYPTION_SETTINGS") return encryptionSettings(db)
        if (issue.reason == "INVALID_GROUP_TREE") return (if (external) remoteObjects else localObjects).map { (id, located) ->
            PreviewField("path:$id", id.toString(), protectedValue(located.path))
        }
        return emptyList()
    }

    private fun entryFields(element: Entry, otherEntry: Entry?, issue: MergeIssue, location: PreviewField): List<PreviewField> =
        orderedFields(element.fields + otherEntry?.fields.orEmpty(), includeTitle = true)
            .map { (name, _) -> field(name, element.fields[name]) } + listOf(
            info(R.string.ui_tags_2, EntryValue.Plain(element.tags.joinToString(", "))),
            info(R.string.merge_attachments, protectedValue(element.binaries.joinToString("\n") { "${it.name} (${it.hash.hex()})" })),
            info(R.string.merge_history, EntryValue.Plain(element.history.size.toString()))
        ) + times(element.times) + custom(element.customData) + if (issue.reason == "ENTRY_PROPERTIES") listOf(
            location,
            info(R.string.merge_entry_icon, EntryValue.Plain(element.icon.name)),
            info(R.string.merge_custom_icon, EntryValue.Plain(element.customIconUuid?.toString().orEmpty())),
            info(R.string.merge_quality_check, EntryValue.Plain(text(if (element.qualityCheck) R.string.merge_enabled else R.string.merge_disabled))),
            info(R.string.merge_entry_foreground, EntryValue.Plain(element.foregroundColor.orEmpty())),
            info(R.string.merge_entry_background, EntryValue.Plain(element.backgroundColor.orEmpty())),
            info(R.string.merge_entry_override_url, protectedValue(element.overrideUrl)),
            info(R.string.merge_entry_auto_type, protectedValue(element.autoType?.defaultSequence)),
            info(R.string.merge_entry_auto_type_enabled, EntryValue.Plain(element.autoType?.let {
                text(if (it.enabled) R.string.merge_enabled else R.string.merge_disabled)
            } ?: text(R.string.merge_field_absent))),
            info(R.string.merge_auto_type_options, protectedValue(element.autoType?.let { "${it.obfuscation}\n${it.items}" }))
        ) else emptyList()

    private fun groupFields(element: Group, location: PreviewField): List<PreviewField> =
        listOf(
            info(R.string.field_title, EntryValue.Plain(element.name)),
            info(R.string.field_notes, protectedValue(element.notes)),
            info(R.string.ui_tags_2, EntryValue.Plain(element.tags.joinToString(", ")))
        ) + times(element.times) + custom(element.customData) + listOf(
            location,
            info(R.string.merge_entry_icon, EntryValue.Plain(element.icon.name)),
            info(R.string.merge_custom_icon, EntryValue.Plain(element.customIconUuid?.toString().orEmpty())),
            info(R.string.merge_entry_auto_type, protectedValue(element.defaultAutoTypeSequence)),
            info(R.string.merge_entry_auto_type_enabled, EntryValue.Plain(element.enableAutoType.toString())),
            info(R.string.merge_search_enabled, EntryValue.Plain(element.enableSearching.toString())))

    private fun databaseSettings(db: KeePassDatabase, external: Boolean): List<PreviewField> {
        val meta = db.content.meta
        return listOf(
            info(R.string.field_title, protectedValue(meta.name)),
            info(R.string.merge_description, protectedValue(meta.description)),
            info(R.string.merge_default_user, protectedValue(meta.defaultUser)),
            info(R.string.merge_recycle_bin, EntryValue.Plain(text(if (meta.recycleBinEnabled) R.string.merge_enabled else R.string.merge_disabled))),
            info(R.string.merge_recycle_bin_path, protectedValue(reference(meta.recycleBinUuid,
                (if (external) local else remote).content.meta.recycleBinUuid, external))),
            info(R.string.merge_templates, protectedValue(reference(meta.entryTemplatesGroup,
                (if (external) local else remote).content.meta.entryTemplatesGroup, external))),
            info(R.string.merge_history_limit, EntryValue.Plain(meta.historyMaxItems.toString())),
            info(R.string.merge_history_size, EntryValue.Plain(meta.historyMaxSize.toString())),
            info(R.string.merge_history_days, EntryValue.Plain(meta.maintenanceHistoryDays.toString())),
            info(R.string.merge_key_recommendation, EntryValue.Plain(meta.masterKeyChangeRec.toString())),
            info(R.string.merge_key_required, EntryValue.Plain(meta.masterKeyChangeForce.toString())),
            info(R.string.merge_key_changed, EntryValue.Plain(date(meta.masterKeyChanged))),
            info(R.string.merge_color, EntryValue.Plain(meta.color.orEmpty())),
            info(R.string.merge_protected_fields, EntryValue.Plain(meta.memoryProtection.joinToString(", ") { fieldLabel(context, it.name) }))
        )
    }

    private fun encryptionSettings(db: KeePassDatabase): List<PreviewField> {
        fun parameter(id: Int, value: Any?) = info(id, EntryValue.Plain(value.toString()))
        val cipher = parameter(R.string.merge_cipher, db.header.cipherId)
        fun aes(rounds: ULong) = listOf(parameter(R.string.merge_kdf, "AES"), parameter(R.string.merge_kdf_rounds, rounds))
        return listOf(cipher) + when (db) {
            is KeePassDatabase.Ver3x -> aes(db.header.transformRounds)
            is KeePassDatabase.Ver4x -> when (val p = db.header.kdfParameters) {
                is KdfParameters.Aes -> aes(p.rounds)
                is KdfParameters.Argon2 -> listOf(
                    parameter(R.string.merge_kdf, p.variant), parameter(R.string.merge_kdf_memory, p.memory),
                    parameter(R.string.merge_kdf_iterations, p.iterations), parameter(R.string.merge_kdf_parallelism, p.parallelism),
                    parameter(R.string.merge_kdf_version, p.version))
            }
        }
    }

    private fun protectedValue(value: Any?) = EntryValue.Encrypted(EncryptedValue.fromString(value?.toString().orEmpty()))
    private fun text(id: Int) = L10n.text(context, id)
    private fun info(id: Int, value: EntryValue) = PreviewField("info:$id", text(id), value)
    private fun field(name: String, value: EntryValue?) = PreviewField("field:$name", name,
        value ?: EntryValue.Plain(text(R.string.merge_field_absent)), present = value != null)
    private fun date(value: java.time.Instant?): String = value?.let {
        java.text.DateFormat.getDateTimeInstance().format(java.util.Date.from(it))
    }.orEmpty()
    private fun custom(data: Map<String, CustomDataValue>, alternateKeys: Set<String> = emptySet()) =
        (data.keys + alternateKeys).filterNot(EntryFieldClocks::isServiceKey).sorted().map { key ->
            val value = data[key]
            PreviewField("custom:$key", key,
                value?.let { protectedValue(it.value) } ?: EntryValue.Plain(text(R.string.merge_field_absent)),
                present = value != null)
        }
    private fun times(value: TimeData?): List<PreviewField> = listOf(
        info(R.string.merge_created, EntryValue.Plain(date(value?.creationTime))),
        info(R.string.merge_modified, EntryValue.Plain(date(value?.lastModificationTime))),
        info(R.string.merge_expiry, EntryValue.Plain(if (value?.expires == true) date(value.expiryTime) else text(R.string.merge_no_expiry)))
    )

    private fun reference(id: UUID?, other: UUID?, external: Boolean): String {
        val objects = if (external) remoteObjects else localObjects
        val path = objects[id]?.path.orEmpty()
        val otherPath = (if (external) localObjects else remoteObjects)[other]?.path.orEmpty()
        return if (id != other && path == otherPath) "$path\n${id?.toString().orEmpty()}" else path
    }
}
