package app.keyra.storage

import android.net.Uri

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

/** Display only: access to documents continues to use their original SAF URI. */
@Suppress("DEPRECATION")
fun Context.documentDisplayPath(uri: Uri): String {
    val home = Environment.getExternalStorageDirectory()
    fun normalized(file: File) = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile.normalize() }
    fun display(path: String): String {
        val file = normalized(File(path)).path
        val root = normalized(home).path.trimEnd('/')
        return when {
            file == root -> "~/"
            file.startsWith("$root/") -> "~/" + file.removePrefix("$root/")
            else -> file
        }
    }
    fun column(name: String): String? = runCatching {
        contentResolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    if (uri.scheme == "file") uri.path?.let { return display(it) }
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (uri.authority == "com.android.externalstorage.documents" && documentId != null) {
        val volumeId = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "")
        if (':' in documentId && !relative.startsWith('/') && relative.split('/').none { it == ".." }) {
            val directory = if (volumeId.equals("primary", ignoreCase = true)) home else {
                getSystemService(StorageManager::class.java).storageVolumes
                    .firstOrNull { it.uuid?.equals(volumeId, ignoreCase = true) == true }?.directory
            }
            directory?.let { return display(File(it, relative).path) }
        }
    }
    if (uri.authority == "com.android.providers.downloads.documents" && documentId?.startsWith("raw:/") == true) {
        return display(documentId.removePrefix("raw:"))
    }
    // Some local providers expose an absolute path; cloud providers commonly do not.
    column(MediaStore.MediaColumns.DATA)?.takeIf { it.startsWith('/') }?.let { return display(it) }
    val name = column(OpenableColumns.DISPLAY_NAME)
    return if (name.isNullOrBlank()) uri.toString() else "$name\n$uri"
}
