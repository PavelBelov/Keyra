package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.CustomDataValue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Database-only policies shared by full merge, partial application, upload and conflict preview. */
object DatabaseMetadataMerge {
    const val REMOTE_RANDOM_SLUG_KEY = "KPXC_RANDOM_SLUG"
    const val LAST_MODIFIED_KEY = "_LAST_MODIFIED"
    // KeePassXC CustomData::lastModified() parses Qt::TextDate, not ISO 8601.
    private val modifiedFormat = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy 'GMT'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC)

    fun requiresChoice(key: String) = key != REMOTE_RANDOM_SLUG_KEY && key != LAST_MODIFIED_KEY

    internal fun preferRemote(data: Map<String, CustomDataValue>, remote: Map<String, CustomDataValue>) =
        (data - REMOTE_RANDOM_SLUG_KEY) + remote.filterKeys { it == REMOTE_RANDOM_SLUG_KEY }

    /** Stamp completed merges only. Preview/partial snapshots must not advance the date. */
    fun completed(db: KeePassDatabase, now: Instant = Instant.now()): KeePassDatabase {
        if (LAST_MODIFIED_KEY !in db.content.meta.customData) return db
        val stamp = CustomDataValue(modifiedFormat.format(now))
        return db.modifyContent { copy(meta = meta.copy(customData = meta.customData + (LAST_MODIFIED_KEY to stamp))) }
    }

    internal fun merge(local: Map<String, CustomDataValue>, remote: Map<String, CustomDataValue>, preferExternal: Boolean) =
        preferRemote(MergeValues.customData(local, remote, preferExternal), remote)

    internal fun applyRemote(local: KeePassDatabase, remote: KeePassDatabase): KeePassDatabase {
        val data = preferRemote(local.content.meta.customData, remote.content.meta.customData)
        return if (data == local.content.meta.customData) local
            else local.modifyContent { copy(meta = meta.copy(customData = data)) }
    }
}
