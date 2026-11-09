package app.keyra.autofill

import java.util.Locale

/** Metadata only: never retain the values entered into a form. */
internal data class CredentialField<T>(
    val id: T,
    val scope: Int,
    val hints: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val names: List<String> = emptyList(),
    val inputType: Int = 0,
    val focused: Boolean = false,
    val editable: Boolean = true
)

internal data class CredentialTargets<T>(val user: T?, val password: T?)

internal object CredentialFields {
    private enum class Kind { USER, PASSWORD, TEXT, IGNORE }
    private fun <T> kind(field: CredentialField<T>): Kind {
        if (!field.editable) return Kind.IGNORE
        val attributes = field.attributes.mapKeys { it.key.lowercase(Locale.ROOT) }
            .mapValues { it.value.lowercase(Locale.ROOT) }
        val type = attributes["type"].orEmpty()
        if (type in setOf("hidden", "button", "submit", "reset", "checkbox", "radio", "file")) return Kind.IGNORE
        val autocomplete = attributes["autocomplete"].orEmpty().split(Regex("\\s+"))
        val hints = field.hints.map { it.lowercase(Locale.ROOT) }
        if (autocomplete.any { it in setOf("one-time-code", "new-password", "cc-number", "cc-csc") } ||
            hints.any { it.contains("newpassword") || it.contains("otp") || it.contains("creditcard") }) return Kind.IGNORE
        if (type == "password" || "current-password" in autocomplete || hints.any { it.contains("password") } ||
            field.inputType and 0xfff in setOf(0x81, 0x91, 0xe1, 0x12)) return Kind.PASSWORD
        if (type.isNotEmpty() && type !in setOf("text", "email")) return Kind.IGNORE
        if (autocomplete.any { it == "username" || it == "email" } || type == "email" ||
            hints.any { it == "username" || it == "emailaddress" || it == "email" } ||
            field.inputType and 0xfff in setOf(0x21, 0xd1)) return Kind.USER
        val names = field.names + field.attributes.filterKeys {
            it.lowercase(Locale.ROOT) in setOf("name", "id", "placeholder", "aria-label")
        }.values
        val words = names.joinToString(" ").replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase(Locale.ROOT)
        // Unicode escapes preserve recognition of Russian form labels in English-only source files.
        if (Regex("(^|[^\\p{L}\\p{N}])(username|user|login|email|e-mail|userid|identifier|\u043b\u043e\u0433\u0438\u043d|\u043f\u043e\u0447\u0442\u0430|\u044d\u043b\u0435\u043a\u0442\u0440\u043e\u043d\u043d\u0430\u044f \u043f\u043e\u0447\u0442\u0430)([^\\p{L}\\p{N}]|$)").containsMatchIn(words)) return Kind.USER
        return if (field.inputType and 0xf == 1 || (field.inputType == 0 && type in setOf("", "text"))) Kind.TEXT else Kind.IGNORE
    }

    fun <T> select(fields: List<CredentialField<T>>): CredentialTargets<T> {
        val classified = fields.map { it to kind(it) }
        val anchor = classified.firstOrNull { it.first.focused && it.second != Kind.IGNORE }
            ?: classified.firstOrNull { it.second == Kind.PASSWORD }
            ?: classified.firstOrNull { it.second == Kind.USER }
            ?: return CredentialTargets(null, null)
        val form = classified.filter { it.first.scope == anchor.first.scope }
        val password = form.firstOrNull { it.first.focused && it.second == Kind.PASSWORD }
            ?: form.firstOrNull { it.second == Kind.PASSWORD }
        val explicitUser = form.firstOrNull { it.first.focused && it.second == Kind.USER }
            ?: form.firstOrNull { it.second == Kind.USER }
        // A plain text field is a fallback only before a password in the same form.
        val user = explicitUser ?: password?.let { pass ->
            form.takeWhile { it.first.id != pass.first.id }.lastOrNull { it.second == Kind.TEXT }
        }
        return CredentialTargets(user?.first?.id, password?.first?.id)
    }
}
