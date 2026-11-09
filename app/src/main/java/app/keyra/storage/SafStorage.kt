package app.keyra.storage

import android.net.Uri

import android.content.Context
import android.provider.DocumentsContract
import app.keyra.core.*
import java.io.File

class SafStorage(private val context: Context, private val uri: Uri) : StorageProvider {
    override val conditionalWrite = false
    var verifiedModified: Long? = null
        private set
    fun lastModified(): Long? = access {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { time -> time > 0 } else null
        }
    }
    override fun metadata(): RemoteFile {
        val temp = File.createTempFile("saf-stat-", ".kdbx", context.cacheDir)
        return try { download(temp) } finally { temp.delete() }
    }
    override fun download(target: File): RemoteFile = access {
        verifiedModified = null
        val before = runCatching { lastModified() }.getOrNull()
        atomicWrite(target) { file -> (context.contentResolver.openInputStream(uri) ?: throw StorageException(StorageError.NOT_FOUND)).use { i -> file.outputStream().use(i::copyTo) } }
        val after = runCatching { lastModified() }.getOrNull()
        if (before != null && after != null && before != after) throw StorageException(StorageError.CHANGED)
        verifiedModified = before?.takeIf { it == after }
        val hash = target.sha256()
        RemoteFile(version = hash, sha256 = hash)
    }
    override fun write(source: File, condition: WriteCondition): RemoteFile = access {
        if (condition != WriteCondition.UserApproved) throw StorageException(StorageError.UNSUPPORTED)
        try {
            (context.contentResolver.openOutputStream(uri, "rwt") ?: throw StorageException(StorageError.PERMISSION)).use { out -> source.inputStream().use { it.copyTo(out) } }
        } catch (e: java.io.IOException) { throw StorageException(StorageError.UNKNOWN_WRITE, e) }
        val result = metadata()
        if (result.sha256 != source.sha256()) throw StorageException(StorageError.CHANGED)
        result
    }
    private inline fun <T> access(block: () -> T): T = try { block() }
    catch (e: StorageException) { throw e }
    catch (e: SecurityException) { throw StorageException(StorageError.PERMISSION, e) }
    catch (e: java.io.FileNotFoundException) { throw StorageException(StorageError.NOT_FOUND, e) }
    catch (e: java.io.IOException) { throw StorageException(StorageError.NETWORK, e) }
}
