package app.keyra.core

import app.keemobile.kotpass.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Value equality excludes bookkeeping dates; absence remains different from an empty value. */
internal object MergeValues {
    fun latest(a: Instant?, b: Instant?): Instant? = listOfNotNull(a, b).maxOrNull()
    fun custom(a: CustomDataValue?, b: CustomDataValue?): CustomDataValue? = when {
        a == null -> b
        b == null -> a
        a.value == b.value -> a.copy(lastModified = latest(a.lastModified, b.lastModified))
        else -> a
    }
    /** Prefer one side for different values, but retain the latest stamp for equal values. */
    fun customData(local: Map<String, CustomDataValue>, remote: Map<String, CustomDataValue>, preferRemote: Boolean = false) =
        (if (preferRemote) local + remote else remote + local).mapValues { (key, value) ->
            if (local[key]?.value == remote[key]?.value) custom(local[key], remote[key]) ?: value else value
        }
    fun groupKey(group: Group): Group = group.copy(
        groups = emptyList(), entries = emptyList(), expanded = true, lastTopVisibleEntry = null,
        previousParentGroup = null, defaultAutoTypeSequence = group.defaultAutoTypeSequence.orEmpty(),
        customData = group.customData.mapValues { it.value.copy(lastModified = null) },
        times = group.times?.let { t -> t.copy(
            creationTime = t.creationTime?.truncatedTo(ChronoUnit.SECONDS),
            lastModificationTime = t.lastModificationTime?.truncatedTo(ChronoUnit.SECONDS),
            expiryTime = t.expiryTime?.takeIf { t.expires }?.truncatedTo(ChronoUnit.SECONDS),
            lastAccessTime = null, locationChanged = null, usageCount = 0) })
    fun group(a: Group, b: Group): Group = a.copy(
        customData = customData(a.customData, b.customData),
        times = a.times?.let { t -> t.copy(lastAccessTime = latest(t.lastAccessTime, b.times?.lastAccessTime),
            locationChanged = latest(t.locationChanged, b.times?.locationChanged),
            usageCount = maxOf(t.usageCount, b.times?.usageCount ?: 0)) })
}
