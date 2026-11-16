package app.keyra

import app.keemobile.kotpass.models.EntryValue
import app.keyra.core.Totp
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TotpImportTest {
    private val uri = "otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example"

    @Test fun createsProtectedFieldAndPreservesOtherDraftValues() {
        val draft = EntryDraft(null, UUID.randomUUID())
        draft.update("Title", "Unsaved title")
        assertTrue(draft.importTotp(uri))
        assertTrue(draft.values[Totp.FIELD] is EntryValue.Encrypted)
        assertEquals(uri, draft.values[Totp.FIELD]!!.content)
        assertEquals("Unsaved title", draft.values["Title"]!!.content)
        assertTrue(draft.modified)
    }

    @Test fun replacesExistingFieldWithoutChangingProtectionOrDuplicatingIt() {
        for (protected in listOf(false, true)) {
            val draft = EntryDraft(null, UUID.randomUUID())
            draft.add(Totp.FIELD, protected)
            draft.update(Totp.FIELD, "old value")
            assertTrue(draft.importTotp(uri))
            assertEquals(protected, draft.values[Totp.FIELD] is EntryValue.Encrypted)
            assertEquals(uri, draft.values[Totp.FIELD]!!.content)
            assertEquals(1, draft.values.keys.count { it == Totp.FIELD })
        }
    }

    @Test fun rejectsOtherQrCodesWithoutModifyingDraft() {
        val draft = EntryDraft(null, UUID.randomUUID())
        val initial = draft.snapshot()
        assertFalse(draft.importTotp("https://example.org"))
        assertEquals(initial, draft.snapshot())
        assertFalse(draft.modified)
        assertTrue(draft.importTotp(uri))
        assertFalse(draft.importTotp("otpauth://totp/Example?secret=bad!"))
        assertEquals(uri, draft.values[Totp.FIELD]!!.content)
    }
}
