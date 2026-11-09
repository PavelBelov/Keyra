package app.keyra.autofill

import org.junit.Assert.*
import org.junit.Test

class CredentialFieldsTest {
    private fun field(id: String, scope: Int = 1, type: String = "text", name: String = "", focused: Boolean = false,
                      autocomplete: String = "", editable: Boolean = true, inputType: Int = 0) =
        CredentialField(id, scope, attributes = mapOf("type" to type, "name" to name, "autocomplete" to autocomplete),
            focused = focused, editable = editable, inputType = inputType)

    @Test fun fillsEmailAndPasswordTogether() {
        assertEquals(CredentialTargets("email", "pass"), CredentialFields.select(listOf(
            field("email", type = "email"), field("pass", type = "password", focused = true))))
    }
    @Test fun recognizesLoginMetadataAndAutocompleteTokens() {
        for (name in listOf("login", "user_name", "userEmail", "email-address", "identifier", "\u041b\u043e\u0433\u0438\u043d")) {
            assertEquals(name, CredentialTargets("user", null), CredentialFields.select(listOf(field("user", name = name))))
        }
        assertEquals(CredentialTargets("user", null), CredentialFields.select(listOf(
            field("user", autocomplete = "section-login username"))))
    }
    @Test fun recognizesNativeEmailAndUsernameHints() {
        for (hint in listOf("username", "emailAddress")) {
            assertEquals(CredentialTargets("user", null), CredentialFields.select(listOf(
                CredentialField("user", 1, hints = listOf(hint)))))
        }
        assertEquals(CredentialTargets("user", null), CredentialFields.select(listOf(field("user", inputType = 0x21))))
    }
    @Test fun supportsSeparateLoginAndPasswordSteps() {
        assertEquals(CredentialTargets("user", null), CredentialFields.select(listOf(field("user", name = "login", focused = true))))
        assertEquals(CredentialTargets(null, "pass"), CredentialFields.select(listOf(field("pass", type = "password", focused = true))))
    }
    @Test fun fallsBackToTextBeforePasswordWithinForm() {
        assertEquals(CredentialTargets("user", "pass"), CredentialFields.select(listOf(
            field("search", scope = 2), field("user", type = ""), field("pass", type = "password"))))
    }
    @Test fun focusedFormDoesNotBorrowFieldsFromOtherForms() {
        assertEquals(CredentialTargets(null, "pass"), CredentialFields.select(listOf(
            field("other-user", scope = 2, type = "email"), field("other-pass", scope = 2, type = "password"),
            field("pass", type = "password", focused = true))))
    }
    @Test fun ignoresHiddenReadonlyOtpAndUnrelatedInputs() {
        val ignored = listOf(field("hidden", type = "hidden", name = "login"),
            field("readonly", name = "login", editable = false),
            field("otp", autocomplete = "one-time-code", name = "login"),
            field("new", type = "password", autocomplete = "new-password"),
            field("search", type = "search"), field("comment"))
        assertEquals(CredentialTargets<String>(null, null), CredentialFields.select(ignored))
    }
    @Test fun ignoresFieldsAfterPasswordAndPrefersExplicitUsername() {
        assertEquals(CredentialTargets("email", "pass"), CredentialFields.select(listOf(
            field("email", type = "email"), field("unrelated"), field("pass", type = "password"), field("after"))))
        assertEquals(CredentialTargets(null, "pass"), CredentialFields.select(listOf(
            field("pass", type = "password"), field("after"))))
    }
    @Test fun recognizesRevealedCurrentPasswordAndRejectsHiddenPassword() {
        assertEquals(CredentialTargets(null, "pass"), CredentialFields.select(listOf(
            field("pass", autocomplete = "section-signin current-password"))))
        assertEquals(CredentialTargets<String>(null, null), CredentialFields.select(listOf(
            field("hidden", type = "hidden", autocomplete = "current-password"))))
    }
    @Test fun numericFieldDoesNotBecomeFallbackUsername() {
        assertEquals(CredentialTargets(null, "pass"), CredentialFields.select(listOf(
            field("number", type = "", inputType = 2), field("pass", type = "password"))))
    }
}
