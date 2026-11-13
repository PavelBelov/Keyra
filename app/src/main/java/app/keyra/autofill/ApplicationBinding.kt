package app.keyra.autofill

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import java.net.URI

internal object ApplicationBinding {
    const val PACKAGE = "AndroidApp"
    const val SIGNATURE = "AndroidApp Signature"

    private fun explicitPackage(entry: Entry) =
        entry.fields[PACKAGE]?.content

    private fun urlPackage(entry: Entry): String? = runCatching {
        val uri = URI(entry.fields.url?.content.orEmpty())
        uri.rawAuthority?.takeIf {
            uri.scheme.equals("androidapp", true) &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.rawQuery == null && uri.rawFragment == null &&
                it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
        }
    }.getOrNull()

    fun matches(entry: Entry, packageName: String) =
        explicitPackage(entry) == packageName || urlPackage(entry) == packageName

    fun trusted(entry: Entry, packageName: String, certificate: String): Boolean {
        val boundPackage = explicitPackage(entry) ?: urlPackage(entry)
        val signature = entry.fields[SIGNATURE]?.content
        return boundPackage == packageName && !certificate.isBlank() && signature == certificate
    }

    fun fields(entry: Entry, packageName: String, certificate: String) =
        entry.fields + mapOf(
            PACKAGE to EntryValue.Plain(packageName), SIGNATURE to EntryValue.Plain(certificate))
}
