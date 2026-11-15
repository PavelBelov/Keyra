package app.keyra

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.header.VariantItem
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.*
import app.keyra.core.Kdbx
import app.keyra.core.MergeIssue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import okio.ByteString.Companion.toByteString

@RunWith(AndroidJUnit4::class)
class ConflictPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun secret(value: String) = EntryValue.Encrypted(EncryptedValue.fromString(value))
    private fun entry() = Entry(UUID.randomUUID(), fields = EntryFields(mapOf(
        "Title" to EntryValue.Plain("Same title"), "B" to EntryValue.Plain("original"),
        "Password" to secret("same protected value"), "Empty" to EntryValue.Plain("")
    )), times = TimeData.create(Instant.parse("2026-01-01T00:00:00Z")))
    private fun review(local: Entry, remote: Entry?): ConflictReview {
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        return ConflictReview(Kdbx.putEntry(db, db.content.group.uuid, local),
            remote?.let { Kdbx.putEntry(db, db.content.group.uuid, it) } ?: db,
            null, false, emptyList(), 0, "", "", null, null, null)
    }

    @Test fun matchingFieldsAndMetadataAreHiddenIncludingSeparatelyProtectedValues() {
        val original = entry()
        val left = original.copy(fields = original.fields + ("B" to EntryValue.Plain("left")),
            customData = mapOf("B" to CustomDataValue("same metadata")))
        val right = original.copy(fields = original.fields + mapOf("B" to EntryValue.Plain("right"),
            "Password" to secret("same protected value")), customData = left.customData)
        val review = review(left, right)
        val issue = MergeIssue(original.uuid, "PARALLEL_ENTRY", true)
        assertEquals(listOf("B"), review.details(context, issue, false).map { it.first })
        assertEquals(listOf("B"), review.details(context, issue, true).map { it.first })
        assertEquals("left", review.details(context, issue, false).single().second.content)
        assertEquals("right", review.details(context, issue, true).single().second.content)
    }

    @Test fun differentEmptyAndMissingValuesRemainDistinguishable() {
        val original = entry()
        val empty = original.copy(fields = original.fields + ("B" to EntryValue.Plain("")))
        val changed = original.copy(fields = original.fields + ("B" to EntryValue.Plain("changed")))
        val issue = MergeIssue(original.uuid, "ENTRY_FIELD", true, "B")
        val different = review(empty, changed)
        assertEquals("", different.details(context, issue, false).single().second.content)
        assertEquals("changed", different.details(context, issue, true).single().second.content)
        val absent = review(original.copy(fields = EntryFields(original.fields - "B")), empty)
        assertEquals(context.getString(R.string.merge_field_absent), absent.details(context, issue, false).single().second.content)
        assertEquals("", absent.details(context, issue, true).single().second.content)
    }

    @Test fun matchingEntryFieldDoesNotHideDifferentPropertyWithTheSameLabel() {
        val label = L10n.text(context, R.string.ui_tags_2)
        val original = entry().let { it.copy(fields = it.fields + (label to EntryValue.Plain("same field value"))) }
        val review = review(original.copy(tags = listOf("left-tag")), original.copy(tags = listOf("right-tag")))
        val issue = MergeIssue(original.uuid, "ENTRY_PROPERTIES", true)
        assertEquals(listOf(label), review.details(context, issue, false).map { it.first })
        assertEquals("left-tag", review.details(context, issue, false).single().second.content)
        assertEquals("right-tag", review.details(context, issue, true).single().second.content)
    }

    @Test fun previewsWithoutTwoEntryVersionsHidePlainProtectedAndCustomEmptyValues() {
        val original = entry().let { it.copy(fields = it.fields + ("Password" to secret("")),
            customData = mapOf("Empty metadata" to CustomDataValue(""))) }
        val review = review(original, null)
        val issue = MergeIssue(original.uuid, "DELETE_EDIT", true)
        val fields = review.details(context, issue, false)
        assertTrue(fields.isNotEmpty())
        assertTrue(fields.all { it.second.content.isNotEmpty() })
        assertFalse(fields.any { it.first in setOf("Password", "Empty", "Empty metadata") })
        assertTrue(review.details(context, issue, true).isEmpty())
    }

    @Test fun databaseCustomMetadataShowsOnlyDifferentValuesIncludingMissingAndEmpty() {
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        val issue = MergeIssue(null, "CUSTOM_METADATA", true)
        val left = mapOf("_LAST_MODIFIED" to CustomDataValue("old local"), "KPXC_RANDOM_SLUG" to CustomDataValue("local-random"), "Same" to CustomDataValue("same"), "Empty" to CustomDataValue(""),
            "Changed" to CustomDataValue("left"), "Only local" to CustomDataValue(""),
            "Time only" to CustomDataValue("same", Instant.parse("2026-01-01T00:00:00Z")))
        val right = mapOf("_LAST_MODIFIED" to CustomDataValue("old remote"), "KPXC_RANDOM_SLUG" to CustomDataValue("remote-random"), "Same" to CustomDataValue("same"), "Empty" to CustomDataValue(""),
            "Changed" to CustomDataValue("right"), "Only remote" to CustomDataValue("remote"),
            "Time only" to CustomDataValue("same", Instant.parse("2026-01-02T00:00:00Z")))
        val review = ConflictReview(db.modifyContent { copy(meta = meta.copy(customData = left)) },
            db.modifyContent { copy(meta = meta.copy(customData = right)) },
            null, false, listOf(issue), 0, "", "", null, null, null)
        repeat(2) {
            for (external in listOf(false, true)) {
                val fields = review.details(context, issue, external).toMap()
                assertEquals(setOf("Changed", "Only local", "Only remote"), fields.keys)
                assertEquals(if (external) "right" else "left", fields.getValue("Changed").content)
                assertTrue(fields.getValue("Changed") is EntryValue.Encrypted)
                assertEquals(if (external) L10n.text(context, R.string.merge_field_absent) else "", fields.getValue("Only local").content)
                assertEquals(if (external) "remote" else L10n.text(context, R.string.merge_field_absent), fields.getValue("Only remote").content)
            }
            review.prepare(context) // The precomputed form must use the same filtering.
        }
    }

    @Test fun allConflictTypesHideMatchingValuesBeforeAndAfterPreparation() {
        val folder = Group(UUID.randomUUID(), "Folder", notes = "", customData = mapOf("Empty metadata" to CustomDataValue("")))
        val entry = entry()
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview")).modifyContent {
            copy(group = group.copy(groups = listOf(folder), entries = listOf(entry)))
        }
        val issues = listOf("PARALLEL_ENTRY", "ENTRY_FIELD", "ENTRY_PROPERTIES", "DELETE_EDIT")
            .map { MergeIssue(entry.uuid, it, true, if (it == "ENTRY_FIELD") "B" else null) } +
            MergeIssue(folder.uuid, "GROUP_OR_TYPE_CONFLICT", true) +
            listOf("DATABASE_SETTINGS", "CUSTOM_METADATA", "CUSTOM_ICON", "HEADER_METADATA", "ENCRYPTION_SETTINGS", "INVALID_GROUP_TREE")
                .map { MergeIssue(null, it, true) }
        val review = ConflictReview(db, db, null, false, issues, 0, "", "", null, null, null)
        repeat(2) {
            issues.forEach { issue -> listOf(false, true).forEach { external ->
                assertTrue(issue.reason, review.details(context, issue, external).isEmpty())
            } }
            review.prepare(context)
        }
    }

    private fun checkPrepared(left: KeePassDatabase, right: KeePassDatabase, issue: MergeIssue,
                              check: (ConflictReview) -> Unit) {
        val review = ConflictReview(left, right, null, false, listOf(issue), 0, "", "", null, null, null)
        check(review)
        review.prepare(context)
        check(review)
    }

    @Test fun foldersShowOnlyDifferentNotesAndPreserveMissingCustomFields() {
        val folder = Group(UUID.randomUUID(), "Same folder", notes = "left", tags = listOf("same"),
            customData = mapOf("Only local" to CustomDataValue("")))
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        val left = db.modifyContent { copy(group = group.copy(groups = listOf(folder))) }
        val right = db.modifyContent { copy(group = group.copy(groups = listOf(folder.copy(notes = "", customData = emptyMap())))) }
        val issue = MergeIssue(folder.uuid, "GROUP_OR_TYPE_CONFLICT", true)
        checkPrepared(left, right, issue) { review ->
            listOf(false, true).forEach { external ->
                val fields = review.details(context, issue, external).toMap()
                assertEquals(setOf(L10n.text(context, R.string.field_notes), "Only local"), fields.keys)
                assertEquals(if (external) "" else "left", fields.getValue(L10n.text(context, R.string.field_notes)).content)
                assertEquals(if (external) L10n.text(context, R.string.merge_field_absent) else "", fields.getValue("Only local").content)
            }
        }
        val missing = db.modifyContent { copy(group = group.copy(groups = emptyList())) }
        val deleted = MergeIssue(folder.uuid, "DELETE_EDIT", true)
        checkPrepared(left, missing, deleted) { review ->
            assertTrue(review.details(context, deleted, false).all { it.second.content.isNotEmpty() })
            assertTrue(review.details(context, deleted, true).isEmpty())
        }
    }

    @Test fun databaseSettingsHideMatchingDefaultsAndShowDifferentEmptyDescription() {
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        val issue = MergeIssue(null, "DATABASE_SETTINGS", true)
        checkPrepared(db.modifyContent { copy(meta = meta.copy(description = "local")) },
            db.modifyContent { copy(meta = meta.copy(description = "")) }, issue) { review ->
            listOf(false, true).forEach { external ->
                val fields = review.details(context, issue, external)
                assertEquals(listOf(L10n.text(context, R.string.merge_description)), fields.map { it.first })
                assertEquals(if (external) "" else "local", fields.single().second.content)
            }
        }
    }

    @Test fun headerMetadataAndEncryptionSettingsAreComparedPerField() {
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview")) as KeePassDatabase.Ver4x
        val left = db.copy(header = db.header.copy(publicCustomData = mapOf(
            "Same" to VariantItem.StringUtf8("same"), "Changed" to VariantItem.StringUtf8("left")),
            kdfParameters = KdfParameters.Aes(2UL, db.header.masterSeed)))
        val right = db.copy(header = db.header.copy(publicCustomData = mapOf(
            "Same" to VariantItem.StringUtf8("same"), "Changed" to VariantItem.StringUtf8("right")),
            kdfParameters = KdfParameters.Aes(3UL, db.header.masterSeed)))
        val header = MergeIssue(null, "HEADER_METADATA", true)
        checkPrepared(left, right, header) { review ->
            listOf(false, true).forEach { external ->
                assertEquals(listOf("Changed"), review.details(context, header, external).map { it.first })
            }
        }
        val encryption = MergeIssue(null, "ENCRYPTION_SETTINGS", true)
        checkPrepared(left, right, encryption) { review ->
            listOf(false, true).forEach { external ->
                val fields = review.details(context, encryption, external)
                assertEquals(listOf(L10n.text(context, R.string.merge_kdf_rounds)), fields.map { it.first })
                assertEquals(if (external) "3" else "2", fields.single().second.content)
            }
        }
    }

    @Test fun structurePreviewOmitsUnchangedPathsAndIconsOmitMatchingImages() {
        val original = entry()
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        val left = Kdbx.putEntry(db, db.content.group.uuid, original)
        val right = Kdbx.putEntry(db, db.content.group.uuid, original.copy(fields = original.fields + ("Title" to EntryValue.Plain("New name"))))
        val tree = MergeIssue(null, "INVALID_GROUP_TREE", true)
        checkPrepared(left, right, tree) { review ->
            listOf(false, true).forEach { external ->
                assertEquals(listOf(original.uuid.toString()), review.details(context, tree, external).map { it.first })
            }
        }
        val id = UUID.randomUUID()
        val image = byteArrayOf(1, 2, 3)
        val icon = MergeIssue(id, "CUSTOM_ICON", true)
        val iconsLeft = db.modifyContent { copy(meta = meta.copy(customIcons = mapOf(id to CustomIcon(image, "left", null)))) }
        val iconsRight = db.modifyContent { copy(meta = meta.copy(customIcons = mapOf(id to CustomIcon(image.copyOf(), "right", null)))) }
        checkPrepared(iconsLeft, iconsRight, icon) { review ->
            assertFalse(review.iconImageDiffers(id))
            listOf(false, true).forEach { external ->
                assertEquals(listOf(L10n.text(context, R.string.field_title)), review.details(context, icon, external).map { it.first })
            }
        }
        val changedImage = iconsLeft.modifyContent { copy(meta = meta.copy(customIcons = mapOf(id to CustomIcon(byteArrayOf(4), "left", null)))) }
        checkPrepared(iconsLeft, changedImage, icon) { review ->
            assertTrue(review.iconImageDiffers(id))
            assertTrue(review.details(context, icon, false).isEmpty())
            assertTrue(review.details(context, icon, true).isEmpty())
        }
    }

    @Test fun realDifferencesInAttachmentsAndOtherwiseHiddenPropertiesStayVisible() {
        val original = entry().copy(binaries = listOf(BinaryReference(byteArrayOf(1).toByteString(), "same name")))
        val other = original.copy(binaries = listOf(BinaryReference(byteArrayOf(2).toByteString(), "same name")), qualityCheck = false)
        val db = Kdbx.create(Kdbx.credentials("synthetic-preview"))
        val issue = MergeIssue(original.uuid, "ENTRY_PROPERTIES", true)
        checkPrepared(Kdbx.putEntry(db, db.content.group.uuid, original), Kdbx.putEntry(db, db.content.group.uuid, other), issue) { review ->
            for (external in listOf(false, true)) {
                assertEquals(setOf(L10n.text(context, R.string.merge_attachments), L10n.text(context, R.string.merge_quality_check)),
                    review.details(context, issue, external).map { it.first }.toSet())
            }
        }
        val folder = Group(UUID.randomUUID(), "same folder")
        val a = db.modifyContent { copy(group = group.copy(groups = listOf(folder))) }
        val b = db.modifyContent { copy(group = group.copy(groups = listOf(folder.copy(enableSearching = app.keemobile.kotpass.constants.GroupOverride.Disabled)))) }
        val folderIssue = MergeIssue(folder.uuid, "GROUP_OR_TYPE_CONFLICT", true)
        checkPrepared(a, b, folderIssue) { review ->
            assertEquals(listOf(L10n.text(context, R.string.merge_search_enabled)), review.details(context, folderIssue, false).map { it.first })
        }
    }
}
