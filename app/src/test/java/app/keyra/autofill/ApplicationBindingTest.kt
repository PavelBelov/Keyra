package app.keyra.autofill

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ApplicationBindingTest {
    private val app = "com.twitter.android"
    private fun entry(vararg fields: Pair<String, String>) = Entry(UUID.randomUUID(),
        fields = EntryFields(fields.associate { it.first to EntryValue.Plain(it.second) }))

    @Test fun bindingRequiresTheOriginalCertificate() {
        val entry = entry("AndroidApp" to app, "AndroidApp Signature" to "certificate")
        assertTrue(ApplicationBinding.matches(entry, app))
        assertTrue(ApplicationBinding.trusted(entry, app, "certificate"))
        assertFalse(ApplicationBinding.trusted(entry, app, "changed"))
        assertFalse(ApplicationBinding.trusted(entry, "com.other.app", "certificate"))
    }

    @Test fun legacyFieldsCannotIdentifyOrAuthorizeAnApp() {
        val legacy = entry("Keyra.Package" to app, "Keyra.Signature" to "certificate")
        assertFalse(ApplicationBinding.matches(legacy, app))
        assertFalse(ApplicationBinding.trusted(legacy, app, "certificate"))
        for (field in listOf("AndroidApp", "URL")) {
            val value = if (field == "URL") "androidapp://$app" else app
            val mixed = entry(field to value, "Keyra.Signature" to "certificate")
            assertTrue(ApplicationBinding.matches(mixed, app))
            assertFalse(ApplicationBinding.trusted(mixed, app, "certificate"))
        }
    }

    @Test fun androidUrlCanIdentifyAnAppButCannotGrantTrustWithoutSignature() {
        val entry = entry("URL" to "androidapp://$app")
        assertTrue(ApplicationBinding.matches(entry, app))
        assertFalse(ApplicationBinding.trusted(entry, app, "certificate"))
        val signed = entry.copy(fields = EntryFields(entry.fields + ("AndroidApp Signature" to EntryValue.Plain("certificate"))))
        assertTrue(ApplicationBinding.trusted(signed, app, "certificate"))
        for (url in listOf("https://$app", "androidapp://$app.evil", "androidapp://user@$app",
            "androidapp://$app:443", "androidapp://$app/path", "androidapp://$app?other", "androidapp://$app#other")) {
            assertFalse(url, ApplicationBinding.matches(entry("URL" to url), app))
        }
    }

    @Test fun explicitBindingDoesNotAuthorizeAnotherAppFromUrl() {
        val entry = entry("AndroidApp" to "com.other.app", "AndroidApp Signature" to "certificate", "URL" to "androidapp://$app")
        assertTrue(ApplicationBinding.matches(entry, app))
        assertFalse(ApplicationBinding.trusted(entry, app, "certificate"))
    }

    @Test fun rebindingPreservesOtherFieldsAndCredentials() {
        val entry = entry("Custom" to "value", "AndroidApp" to "com.old.app", "AndroidApp Signature" to "old",
            "URL" to "https://x.com", "Password" to "synthetic", "UserName" to "user")
        val fields = ApplicationBinding.fields(entry, app, "certificate")
        assertEquals(app, fields["AndroidApp"]?.content)
        assertEquals("certificate", fields["AndroidApp Signature"]?.content)
        for (key in listOf("Custom", "URL", "Password", "UserName")) assertEquals(entry.fields[key], fields[key])
    }
}
