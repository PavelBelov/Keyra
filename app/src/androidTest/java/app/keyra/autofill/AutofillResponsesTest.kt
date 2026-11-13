package app.keyra.autofill

import android.content.Context
import android.view.autofill.AutofillId
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keyra.core.Kdbx
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutofillResponsesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun id(): AutofillId {
        lateinit var result: AutofillId
        instrumentation.runOnMainSync { result = EditText(context).autofillId }
        return result
    }
    private fun target(user: AutofillId? = id(), password: AutofillId? = id(), domain: String? = "example.com") =
        FillTarget(user, password, context.packageName, domain, "synthetic-certificate", System.currentTimeMillis() + 120_000)
    private fun entry(title: String = "Synthetic account", url: String = "https://example.com/login") =
        Entry(UUID.randomUUID(), fields = EntryFields.of("Title" to EntryValue.Plain(title),
            "URL" to EntryValue.Plain(url), "UserName" to EntryValue.Plain("synthetic-user"),
            "Password" to EntryValue.Plain("synthetic-password")))

    @Test fun separateStepsBuildValidResponses() {
        for (target in listOf(target(password = null), target(user = null))) {
            assertNotNull(AutofillResponses.entries(context, target, listOf(entry())))
        }
    }
    @Test fun matchingRequiresExactHostAndNativeBindingRequiresCertificate() {
        val target = target()
        var db = Kdbx.create(Kdbx.credentials("synthetic"))
        val matching = entry(url = "https://EXAMPLE.com/path")
        for (entry in listOf(matching, entry(url = "https://example.com.evil.org"), entry(url = "https://sub.example.com"))) {
            db = Kdbx.putEntry(db, db.content.group.uuid, entry)
        }
        assertEquals(listOf(matching.uuid), AutofillResponses.candidates(db, target).map { it.uuid })
        val bound = entry().let { it.copy(fields = it.fields + mapOf("AndroidApp" to EntryValue.Plain(target.packageName),
            "AndroidApp Signature" to EntryValue.Plain(target.certificate))) }
        db = Kdbx.putEntry(db, db.content.group.uuid, bound)
        assertEquals(listOf(bound.uuid), AutofillResponses.candidates(db, target.copy(domain = null)).map { it.uuid })
        assertTrue(AutofillResponses.candidates(db, target.copy(domain = null, certificate = "changed")).isEmpty())
    }
    @Test fun browserTrustDoesNotSurviveCertificateChange() {
        val prefs = context.getSharedPreferences("test-autofill-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val target = target()
        try {
            assertFalse(AutofillResponses.trustedBrowser(prefs, target))
            prefs.edit().putString("browser.${target.packageName}", target.certificate).commit()
            assertTrue(AutofillResponses.trustedBrowser(prefs, target))
            assertFalse(AutofillResponses.trustedBrowser(prefs, target.copy(certificate = "changed")))
            assertFalse(AutofillResponses.trustedBrowser(prefs, target.copy(domain = null)))
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun androidUrlsAreAppCandidatesAndNeverWebsiteCandidates() {
        val target = target(domain = null)
        val unsigned = entry(url = "androidapp://${target.packageName}")
        val signed = unsigned.copy(uuid = UUID.randomUUID(), fields = unsigned.fields +
            mapOf("AndroidApp Signature" to EntryValue.Plain(target.certificate)))
        val explicit = entry().let { it.copy(fields = it.fields + mapOf(
            "AndroidApp" to EntryValue.Plain(target.packageName),
            "AndroidApp Signature" to EntryValue.Plain(target.certificate))) }
        var db = Kdbx.create(Kdbx.credentials("synthetic"))
        for (entry in listOf(unsigned, signed, explicit)) db = Kdbx.putEntry(db, db.content.group.uuid, entry)
        assertEquals(setOf(signed.uuid, explicit.uuid), AutofillResponses.candidates(db, target).map { it.uuid }.toSet())
        assertEquals(setOf(unsigned.uuid, signed.uuid, explicit.uuid),
            AutofillResponses.candidates(db, target, requireTrusted = false).map { it.uuid }.toSet())
        assertTrue(AutofillResponses.candidates(db, target.copy(certificate = "changed")).isEmpty())
        assertTrue(AutofillResponses.candidates(db, target.copy(domain = target.packageName)).isEmpty())
    }
    @Test fun searchIsAlwaysPresentAndOverflowRemainsBounded() {
        val target = target()
        val empty = requireNotNull(AutofillResponses.entries(context, target, emptyList()))
        assertEquals(1, empty.datasets!!.size)
        assertNotNull(empty.datasets!!.last().authentication)
        val single = requireNotNull(AutofillResponses.entries(context, target, listOf(entry())))
        assertEquals(2, single.datasets!!.size)
        assertNotNull(single.datasets!!.last().authentication)
        val many = requireNotNull(AutofillResponses.entries(context, target, (1..21).map { entry("Account $it") }))
        assertEquals(20, many.datasets!!.size)
        assertNotNull(many.datasets!!.last().authentication)
    }
}
