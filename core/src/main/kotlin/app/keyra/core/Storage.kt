package app.keyra.core

import java.io.File
import java.io.IOException

/** Only metadata used to verify synchronization and source permissions. */
data class RemoteFile(val version: String?, val sha256: String? = null, val writable: Boolean? = null, val modifiedAt: Long? = null)
sealed interface WriteCondition {
    data class Unchanged(val version: String) : WriteCondition
    /** Explicit user action only; never the default for automatic synchronization. */
    data object UserApproved : WriteCondition
}
enum class StorageError { NETWORK, AUTH, PERMISSION, NOT_FOUND, CHANGED, QUOTA, TEMPORARY, UNKNOWN_WRITE, UNSUPPORTED }
class StorageException(val kind: StorageError, cause: Throwable? = null) : IOException(kind.name, cause)

interface StorageProvider {
    val conditionalWrite: Boolean
    fun metadata(): RemoteFile
    /** Must bind the downloaded bytes to the returned version, or reject the read. */
    fun download(target: File): RemoteFile
    fun write(source: File, condition: WriteCondition): RemoteFile
}
