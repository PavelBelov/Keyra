package app.keyra.core

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultSearchTest {
    @Test fun searchesNativeTagsIgnoringCaseAndStopsMatchingRemovedTags() {
        val entry = Entry(UUID.randomUUID(), fields = EntryFields.of(
            "Title" to EntryValue.Plain("Example"),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("secret"))
        ), tags = listOf("Работа", "Bank"))
        val initial = Kdbx.create(Kdbx.credentials("test"))
        val db = Kdbx.putEntry(initial, initial.content.group.uuid, entry)
        assertEquals(entry.uuid, db.searchUnprotected("РАБО").single().second.single().uuid)
        assertEquals(entry.uuid, db.searchUnprotected("bank").single().second.single().uuid)
        assertTrue(db.searchUnprotected("secret").isEmpty())
        val updated = Kdbx.putEntry(db, db.content.group.uuid, Kdbx.edit(entry, entry.fields, emptyList()))
        assertTrue(updated.searchUnprotected("bank").isEmpty())
    }

    @Test fun editedTagsSurviveKdbxRoundTripAndOriginalTagsRemainInHistory() {
        val entry = Entry(UUID.randomUUID(), fields = EntryFields.of("Title" to EntryValue.Plain("Example")), tags = listOf("Old"))
        val edited = Kdbx.edit(entry, entry.fields, listOf("Work", "Bank"))
        val db = Kdbx.create(Kdbx.credentials("test"))
        val file = java.io.File.createTempFile("keyra-tags-", ".kdbx")
        try {
            Kdbx.write(Kdbx.putEntry(db, db.content.group.uuid, edited), file)
            val restored = Kdbx.entries(Kdbx.read(file, Kdbx.credentials("test"))).single { it.uuid == entry.uuid }
            assertEquals(listOf("Work", "Bank"), restored.tags)
            assertEquals(listOf("Old"), restored.history.last().tags)
        } finally { file.delete() }
    }

    @Test fun searchesAdditionalPlainFieldsAcrossNestedGroupsIgnoringCase() {
        val entry = Entry(UUID.randomUUID(), fields = EntryFields.of(
            "Title" to EntryValue.Plain("Example"), "Account reference" to EntryValue.Plain("Customer-ABC-42")
        ))
        val child = Group(UUID.randomUUID(), "Child", entries = listOf(entry))
        val db = Kdbx.create(Kdbx.credentials("test")).modifyContent {
            copy(group = group.copy(groups = group.groups + Group(UUID.randomUUID(), "Parent", groups = listOf(child))))
        }
        val matches = db.searchUnprotected("abc-42")
        assertEquals(child.uuid, matches.single().first.uuid)
        assertEquals(entry.uuid, matches.single().second.single().uuid)
    }

    @Test fun protectionDependsOnValueTypeRatherThanFieldName() {
        val entry = Entry(UUID.randomUUID(), fields = EntryFields.of(
            "Title" to EntryValue.Encrypted(EncryptedValue.fromString("hidden-title")),
            "Custom" to EntryValue.Encrypted(EncryptedValue.fromString("hidden-custom")),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString("hidden-password")),
            "Notes" to EntryValue.Plain("visible-notes")
        ))
        val db = Kdbx.create(Kdbx.credentials("test"))
        val protected = Kdbx.putEntry(db, db.content.group.uuid, entry)
        assertTrue(protected.searchUnprotected("hidden").isEmpty())
        assertEquals(entry.uuid, protected.searchUnprotected("visible-notes").single().second.single().uuid)
        val legacyPlain = entry.copy(fields = entry.fields + ("Password" to EntryValue.Plain("legacy-plain")))
        val legacy = Kdbx.putEntry(db, db.content.group.uuid, legacyPlain)
        assertEquals(entry.uuid, legacy.searchUnprotected("LEGACY-PLAIN").single().second.single().uuid)
    }
}
