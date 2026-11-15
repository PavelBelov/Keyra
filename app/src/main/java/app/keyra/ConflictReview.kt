package app.keyra

import android.content.Context
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.*
import app.keyra.core.*
import java.util.UUID

/** Memory-only review. Never save database contents or revealed values in Android saved state. */
class ConflictReview(
    val local: KeePassDatabase,
    val remote: KeePassDatabase,
    val base: KeePassDatabase?,
    val importing: Boolean,
    val issues: List<MergeIssue>,
    val generation: Long,
    val localHash: String,
    val remoteHash: String,
    val baseHash: String?,
    val sourceRevision: String?,
    val pendingWrite: String?,
    val active: KeePassDatabase = local,
    val originalHash: String? = null
) {
    data class Located(val value: DatabaseElement, val parent: UUID?, val path: String)
    private fun index(db: KeePassDatabase?): Map<UUID, Located> = buildMap {
        fun walk(group: Group, parent: UUID?, prefix: String) {
            val path = if (prefix.isEmpty()) group.name else "$prefix\\${group.name}"
            put(group.uuid, Located(group, parent, path))
            group.entries.forEach { entry ->
                put(entry.uuid, Located(entry, group.uuid, "$path\\${entry.fields.title?.content.orEmpty()}"))
            }
            group.groups.forEach { walk(it, group.uuid, path) }
        }
        db?.let { walk(it.content.group, null, "") }
    }
    val localObjects = index(local)
    val remoteObjects = index(remote)
    private val baseObjects = index(base)

    private var preparedLocale: String? = null
    private var preparedSides: Map<Pair<MergeConflictKey, Boolean>, Pair<String, String>> = emptyMap()
    private var preparedDetails: Map<Pair<MergeConflictKey, Boolean>, List<Pair<String, EntryValue>>> = emptyMap()

    /** Prepare presentation data on the repository's IO dispatcher, retaining protected wrappers. */
    fun prepare(context: Context) {
        val sides = linkedMapOf<Pair<MergeConflictKey, Boolean>, Pair<String, String>>()
        val details = linkedMapOf<Pair<MergeConflictKey, Boolean>, List<Pair<String, EntryValue>>>()
        val preview = ConflictPreview(context, this, baseObjects)
        issues.forEach { issue ->
            val versions = preview.details(issue)
            listOf(false, true).forEach { external ->
                val key = issue.key to external
                sides[key] = describeSide(context, issue, external)
                details[key] = if (external) versions.second else versions.first
            }
        }
        preparedSides = sides
        preparedDetails = details
        preparedLocale = locale(context)
    }

    private fun locale(context: Context) = ApplicationLanguage.localizedContext(context).resources.configuration.locales.toLanguageTags()

    fun side(context: Context, issue: MergeIssue, external: Boolean): Pair<String, String> =
        preparedSides[issue.key to external]?.takeIf { preparedLocale == locale(context) }
            ?: describeSide(context, issue, external)

    private fun describeSide(context: Context, issue: MergeIssue, external: Boolean): Pair<String, String> {
        val objects = if (external) remoteObjects else localObjects
        val db = if (external) remote else local
        val current = objects[issue.objectId]
        val previous = baseObjects[issue.objectId]
        if (!issue.isObjectConflict) {
            return reason(context, issue.reason) to L10n.text(context, R.string.merge_differs)
        }
        val rawPath = current?.path ?: previous?.path ?: (if (external) localObjects else remoteObjects)[issue.objectId]?.path
            ?: L10n.text(context, R.string.untitled_entry)
        val path = if (rawPath.endsWith('\\')) rawPath + L10n.text(context, R.string.untitled_entry) else rawPath
        val status = when {
            issue.reason == "ENTRY_FIELD" && current?.value is Entry -> {
                val entry = current.value as Entry
                val old = previous?.value as? Entry
                val value = entry.fields[issue.fieldName]
                L10n.text(context, when {
                    value == null && old?.fields?.containsKey(issue.fieldName) == true -> R.string.merge_field_deleted
                    value == null -> R.string.merge_field_absent
                    base == null -> R.string.merge_differs
                    old?.fields?.containsKey(issue.fieldName) != true -> R.string.merge_added
                    else -> R.string.merge_edited
                })
            }
            current == null -> L10n.text(context, if (db.content.deletedObjects.any { it.id == issue.objectId }) R.string.merge_deleted else R.string.merge_absent)
            base == null -> L10n.text(context, R.string.merge_differs)
            previous == null -> L10n.text(context, R.string.merge_added)
            else -> {
                val changes = mutableListOf<String>()
                if (current.parent != previous.parent || current.path.substringBeforeLast('\\') != previous.path.substringBeforeLast('\\'))
                    changes += L10n.text(context, R.string.merge_moved)
                val a = current.value; val b = previous.value
                fun contentTimes(value: TimeData?) = value?.copy(
                    creationTime = value.creationTime?.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                    expiryTime = value.expiryTime?.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                    lastModificationTime = null, lastAccessTime = null, locationChanged = null, usageCount = 0)
                val changed = when {
                    a is Entry && b is Entry -> !Merge.entriesEquivalent(
                        a.copy(times = contentTimes(a.times), previousParentGroup = null), b.copy(times = contentTimes(b.times), previousParentGroup = null))
                    a is Group && b is Group -> a.copy(groups = emptyList(), entries = emptyList(), times = contentTimes(a.times), previousParentGroup = null) !=
                        b.copy(groups = emptyList(), entries = emptyList(), times = contentTimes(b.times), previousParentGroup = null)
                    else -> true
                }
                if (changed) changes += L10n.text(context, R.string.merge_edited)
                changes.joinToString(", ").ifEmpty { L10n.text(context, R.string.merge_unchanged) }
            }
        }
        return path to status
    }

    fun details(context: Context, issue: MergeIssue, external: Boolean): List<Pair<String, EntryValue>> =
        preparedDetails[issue.key to external]?.takeIf { preparedLocale == locale(context) }
            ?: ConflictPreview(context, this, baseObjects).details(issue).let { if (external) it.second else it.first }

    fun iconImageDiffers(id: UUID): Boolean {
        val left = local.content.meta.customIcons[id]?.data
        val right = remote.content.meta.customIcons[id]?.data
        return if (left == null || right == null) left !== right else !left.contentEquals(right)
    }

    companion object {
        fun reason(context: Context, reason: String): String = L10n.text(context, when (reason) {
            "ENTRY_FIELD" -> R.string.merge_field_conflict
            "ENTRY_PROPERTIES" -> R.string.merge_entry_properties_conflict
            "PARALLEL_ENTRY" -> R.string.merge_parallel_entry
            "DELETE_EDIT" -> R.string.merge_delete_edit
            "GROUP_OR_TYPE_CONFLICT" -> R.string.merge_group_conflict
            "DATABASE_SETTINGS" -> R.string.merge_database_settings
            "CUSTOM_METADATA" -> R.string.merge_custom_metadata
            "CUSTOM_ICON" -> R.string.merge_custom_icon
            "HEADER_METADATA" -> R.string.merge_header_metadata
            "ENCRYPTION_SETTINGS" -> R.string.merge_encryption_settings
            "INVALID_GROUP_TREE" -> R.string.merge_group_tree
            else -> R.string.merge_conflicts_required
        })
    }
}

enum class ConflictMode { LOCAL, REMOTE, MANUAL }
class ConflictDraft(val review: ConflictReview) {
    var mode = ConflictMode.MANUAL
    val choices = linkedMapOf<MergeConflictKey, Boolean>()
    var additionalIssues: List<MergeIssue> = emptyList()
    var error = ""
    val issues get() = (review.issues + additionalIssues).distinctBy { it.key }
    val complete get() = mode != ConflictMode.MANUAL || issues.all { choices.containsKey(it.key) }
    fun resolutions(): Map<MergeConflictKey, Boolean> = when (mode) {
        ConflictMode.LOCAL -> issues.associate { it.key to false }
        ConflictMode.REMOTE -> issues.associate { it.key to true }
        ConflictMode.MANUAL -> choices.toMap()
    }
}

/** Immutable choices and their computed result; the repository revalidates snapshots before applying. */
internal class PreparedConflictResolution(
    val review: ConflictReview,
    val choices: Map<MergeConflictKey, Boolean>,
    val result: MergeResult
)
