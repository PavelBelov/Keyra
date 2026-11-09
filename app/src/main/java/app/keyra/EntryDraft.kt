package app.keyra

import android.content.Context
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keyra.core.PasswordGenerator
import app.keyra.core.PasswordOptions
import java.util.UUID

val standardEntryFields = listOf("Title", "UserName", "Password", "URL", "Notes")

fun orderedFields(fields: Map<String, EntryValue>, includeTitle: Boolean = false): List<Pair<String, EntryValue>> =
    (standardEntryFields.filter { includeTitle || it != "Title" } + fields.keys.filterNot { it in standardEntryFields })
        .mapNotNull { key -> fields[key]?.let { key to it } }

fun fieldLabel(context: Context, key: String): String = when (key) {
    "Title" -> context.getString(R.string.field_title)
    "UserName" -> context.getString(R.string.field_login)
    "Password" -> context.getString(R.string.field_password)
    "URL" -> context.getString(R.string.field_url)
    "Notes" -> context.getString(R.string.field_notes)
    else -> key
}

fun sameEntryFields(left: Map<String, EntryValue>, right: Map<String, EntryValue>): Boolean =
    left.keys == right.keys && left.all { (key, value) ->
        val other = right[key]!!
        value.content == other.content && (value is EntryValue.Encrypted) == (other is EntryValue.Encrypted)
    }

/** Transient draft: protected values remain wrapped; nothing is persisted through SavedState. */
class EntryDraft(val original: Entry?, val parent: UUID) {
    val id: UUID = original?.uuid ?: UUID.randomUUID()
    val values = linkedMapOf<String, EntryValue>().apply {
        putAll(EntryFields.createDefault())
        original?.fields?.let { putAll(it) }
    }
    var modified = false; private set
    var tags: List<String> = original?.tags?.toList().orEmpty()
        private set
    var pendingTagText = ""
        private set
    fun updateTagInput(text: String) {
        if (pendingTagText != text) { pendingTagText = text; modified = true }
    }
    fun addPendingTags() {
        val added = pendingTagText.split(';', ',', ':').map(String::trim).filter(String::isNotEmpty)
        if (added.isEmpty()) { pendingTagText = ""; return }
        val updated = (tags + added).distinct()
        if (updated != tags) { tags = updated; modified = true }
        pendingTagText = ""
    }
    fun removeTag(tag: String) {
        if (tag in tags) { tags = tags - tag; modified = true }
    }

    fun update(key: String, text: String) {
        val old = values[key]
        val secret = key == "Password" || old is EntryValue.Encrypted
        if (old != null && old.content == text) return
        values[key] = if (secret) EntryValue.Encrypted(EncryptedValue.fromString(text)) else EntryValue.Plain(text)
        modified = true
    }

    fun add(key: String, secret: Boolean) {
        require(key.isNotBlank() && key !in values)
        values[key] = if (secret) EntryValue.Encrypted(EncryptedValue.fromString("")) else EntryValue.Plain("")
        modified = true
    }

    fun protect(key: String, enabled: Boolean) {
        val old = values[key] ?: return
        val secret = enabled || key == "Password"
        if ((old is EntryValue.Encrypted) == secret) return
        values[key] = if (secret) EntryValue.Encrypted(EncryptedValue.fromString(old.content)) else EntryValue.Plain(old.content)
        modified = true
    }

    fun remove(key: String) {
        require(key !in standardEntryFields)
        if (values.remove(key) != null) modified = true
    }

    fun snapshot(): EntryFields = EntryFields(values.toMap()).let { fields ->
        if (fields.password is EntryValue.Encrypted) fields
        else fields + ("Password" to EntryValue.Encrypted(EncryptedValue.fromString(fields.password?.content.orEmpty())))
    }

    fun dispose() { values.clear(); tags = emptyList(); pendingTagText = "" }
}

enum class GeneratorError { LENGTH, NO_GROUPS, TOO_SHORT }

class PasswordGeneratorDraft(val field: String, initial: PasswordOptions) {
    var lengthText = initial.length.toString()
    var lowercase = initial.lowercase
    var uppercase = initial.uppercase
    var digits = initial.digits
    var symbols = initial.symbols
    var generated: EntryValue.Encrypted? = null; private set
    var error: GeneratorError? = null; private set

    fun options(): PasswordOptions? = lengthText.toIntOrNull()?.let { PasswordOptions(it, lowercase, uppercase, digits, symbols) }

    fun regenerate() {
        val options = options()
        error = when {
            options == null || options.length !in PasswordGenerator.MIN_LENGTH..PasswordGenerator.MAX_LENGTH -> GeneratorError.LENGTH
            options.groups().isEmpty() -> GeneratorError.NO_GROUPS
            options.length < options.groups().size -> GeneratorError.TOO_SHORT
            else -> null
        }
        generated = if (error == null) EntryValue.Encrypted(EncryptedValue.fromString(PasswordGenerator.generate(options!!))) else null
    }

    fun dispose() { generated = null }
}
