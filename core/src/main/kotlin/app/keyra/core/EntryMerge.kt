package app.keyra.core

import app.keemobile.kotpass.models.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal object EntryMerge {
    data class Result(val entry: Entry, val parent: UUID?, val issues: List<MergeIssue>)

    fun combine(local: Entry, remote: Entry, base: Entry?, localParent: UUID?, remoteParent: UUID?, baseParent: UUID?,
                choice: (String, String?) -> Boolean?): Result {
        val issues = mutableListOf<MergeIssue>()
        val mergedFields = mergeFields(local, remote, base, choice)
        issues += mergedFields.issues

        var propertiesConflict = false
        fun <T> property(l: T, r: T, b: T?): T = when {
            l == r -> l
            base != null && l == b -> r
            base != null && r == b -> l
            else -> {
                val external = choice("ENTRY_PROPERTIES", null)
                if (external == null) propertiesConflict = true
                if (external == true) r else l
            }
        }
        val parent = property(localParent, remoteParent, baseParent)
        val leftData = local.customData.filterKeys { !EntryFieldClocks.isServiceKey(it) }
        val rightData = remote.customData.filterKeys { !EntryFieldClocks.isServiceKey(it) }
        val baseData = base?.customData?.filterKeys { !EntryFieldClocks.isServiceKey(it) }.orEmpty()
        val customData = buildMap {
            for (name in leftData.keys + rightData.keys + baseData.keys) {
                val l = leftData[name]; val r = rightData[name]
                val value = property(l?.value, r?.value, baseData[name]?.value)
                val selected = if (l?.value == r?.value) MergeValues.custom(l, r)
                    else if (value == r?.value) r else l
                selected?.let { put(name, it) }
            }
        }
        fun Instant.seconds() = truncatedTo(ChronoUnit.SECONDS)
        fun latest(a: Instant?, b: Instant?) = listOfNotNull(a, b).maxOrNull()?.seconds()
        val lt = local.times; val rt = remote.times; val bt = base?.times
        val expires = property(lt?.expires ?: false, rt?.expires ?: false, bt?.expires)
        val times = if (lt == null && rt == null) null else TimeData(
            creationTime = property(lt?.creationTime?.seconds(), rt?.creationTime?.seconds(), bt?.creationTime?.seconds()),
            lastModificationTime = latest(lt?.lastModificationTime, rt?.lastModificationTime),
            lastAccessTime = latest(lt?.lastAccessTime, rt?.lastAccessTime),
            locationChanged = latest(lt?.locationChanged, rt?.locationChanged),
            expiryTime = if (expires) property(lt?.expiryTime?.seconds(), rt?.expiryTime?.seconds(), bt?.expiryTime?.seconds())
                else latest(lt?.expiryTime, rt?.expiryTime),
            expires = expires,
            usageCount = maxOf(lt?.usageCount ?: 0, rt?.usageCount ?: 0)
        )
        val histories = (local.history + remote.history + local.copy(history = emptyList()) + remote.copy(history = emptyList()))
            .distinctBy(Merge::entryKey)
        val entry = local.copy(fields = EntryFields(mergedFields.values),
            tags = property(local.tags, remote.tags, base?.tags),
            icon = property(local.icon, remote.icon, base?.icon),
            customIconUuid = property(local.customIconUuid, remote.customIconUuid, base?.customIconUuid),
            foregroundColor = property(local.foregroundColor.orEmpty(), remote.foregroundColor.orEmpty(), base?.foregroundColor.orEmpty()),
            backgroundColor = property(local.backgroundColor.orEmpty(), remote.backgroundColor.orEmpty(), base?.backgroundColor.orEmpty()),
            overrideUrl = property(local.overrideUrl, remote.overrideUrl, base?.overrideUrl),
            autoType = property(local.autoType, remote.autoType, base?.autoType),
            binaries = property(local.binaries, remote.binaries, base?.binaries),
            qualityCheck = property(local.qualityCheck, remote.qualityCheck, base?.qualityCheck),
            previousParentGroup = if ((local.times?.locationChanged ?: Instant.MIN) >= (remote.times?.locationChanged ?: Instant.MIN))
                local.previousParentGroup else remote.previousParentGroup,
            customData = customData, times = times, history = histories)
        if (propertiesConflict) issues += MergeIssue(local.uuid, "ENTRY_PROPERTIES", true)
        return Result(EntryFieldClocks.capture(entry, mergedFields.dates, merged = true), parent, issues)
    }

    private data class MergedFields(
        val values: Map<String, EntryValue>,
        val dates: Map<String, Instant?>,
        val issues: List<MergeIssue>
    )

    private fun mergeFields(local: Entry, remote: Entry, base: Entry?,
                           choice: (String, String?) -> Boolean?): MergedFields {
        val issues = mutableListOf<MergeIssue>()
        val leftDates = EntryFieldClocks.dates(local, base)
        val rightDates = EntryFieldClocks.dates(remote, base)
        val baseDates = base?.let { EntryFieldClocks.dates(it) }.orEmpty()
        val fields = linkedMapOf<String, EntryValue>()
        val dates = linkedMapOf<String, Instant?>()
        for (name in local.fields.keys + remote.fields.keys + base?.fields?.keys.orEmpty() + leftDates.keys + rightDates.keys) {
            val l = local.fields[name]; val r = remote.fields[name]; val b = base?.fields?.get(name)
            val lt = leftDates[name]; val rt = rightDates[name]
            val baseTime = baseDates[name] ?: base?.times?.lastModificationTime?.truncatedTo(ChronoUnit.SECONDS)
            val sameValue = (l == null && r == null) || (l != null && r != null && l.content == r.content)
            // Returning to the baseline value is still a change when a verified clock proves it.
            val localChanged = base == null || !EntryFieldClocks.same(l, b) || (lt != null && baseTime != null && lt > baseTime)
            val remoteChanged = base == null || !EntryFieldClocks.same(r, b) || (rt != null && baseTime != null && rt > baseTime)
            val takeRemote = when {
                sameValue -> if (r is EntryValue.Encrypted && l !is EntryValue.Encrypted) true else null
                // A client may save an old copy after a merge. Verified per-field clocks
                // also prevent that copy from reverting newer, otherwise unchanged values.
                lt != null && rt != null && lt != rt -> rt > lt
                !localChanged -> true
                !remoteChanged -> false
                else -> choice("ENTRY_FIELD", name).also {
                    if (it == null) issues += MergeIssue(local.uuid, "ENTRY_FIELD", true, name)
                } ?: false
            }
            val selected = if (takeRemote == true) r else l
            if (selected != null) fields[name] = selected
            dates[name] = if (sameValue) {
                if (lt != null && rt != null) maxOf(lt, rt) else null
            } else when (takeRemote) {
                true -> rt
                false -> lt
                null -> if (lt != null && rt != null) maxOf(lt, rt) else null
            }
        }

        return MergedFields(fields, dates, issues)
    }

}
