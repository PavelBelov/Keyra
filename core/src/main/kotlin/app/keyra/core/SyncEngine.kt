package app.keyra.core

import app.keemobile.kotpass.database.*
import java.io.File

enum class SyncStatus { SYNCED, PENDING, RUNNING, OFFLINE, LOCKED, AUTH_REQUIRED, CONFLICT, APPROVAL_REQUIRED, ERROR }
data class SyncOutcome(val status: SyncStatus, val database: KeePassDatabase? = null, val issues: List<MergeIssue> = emptyList())

interface SyncWorkspace {
    val local: File
    val baseline: File
    val remote: File
    val candidate: File
    val transaction: File
    fun log(event: String)
    /** Persist intent before a remote write; an interrupted write must be reconciled, never blindly retried. */
    fun beginWrite(hash: String)
    fun complete(hash: String)
    fun pendingWrite(): String?
}

class SyncEngine(private val files: SyncWorkspace) {
    fun run(provider: StorageProvider, open: KeePassDatabase?, approveUnconditional: Boolean = false, isUnlocked: () -> Boolean = { true }): SyncOutcome {
        files.log("SYNC_START")
        val remote = provider.download(files.remote)
        if (open == null || !isUnlocked()) return SyncOutcome(SyncStatus.LOCKED)
        // Always authenticate remote content before replacing or overwriting any copy.
        val external = try { Kdbx.read(files.remote, open.credentials) } catch (_: Exception) { files.log("REMOTE_UNLOCK_FAILED"); return SyncOutcome(SyncStatus.ERROR) }
        if (!isUnlocked()) return SyncOutcome(SyncStatus.LOCKED)
        if (external.content.group.uuid != open.content.group.uuid) { files.log("REMOTE_DATABASE_MISMATCH"); return SyncOutcome(SyncStatus.ERROR) }
        val localHash = files.local.sha256(); val remoteHash = remote.sha256 ?: files.remote.sha256()
        val interrupted = files.pendingWrite()
        if (interrupted != null) {
            if (interrupted == remoteHash && files.transaction.exists() && files.transaction.sha256() == interrupted) {
                // Preserve edits made locally after the interrupted upload.
                atomicCopy(files.transaction, files.baseline)
                files.complete(interrupted)
            } else { files.log("WRITE_RESULT_UNCERTAIN"); return SyncOutcome(SyncStatus.CONFLICT) }
        }
        if (localHash == remoteHash) {
            atomicCopy(files.remote, files.baseline); files.complete(remoteHash)
            return SyncOutcome(SyncStatus.SYNCED, open)
        }
        val baseHash = files.baseline.takeIf(File::exists)?.sha256()
        val localChanged = baseHash == null || localHash != baseHash
        val remoteChanged = baseHash == null || remoteHash != baseHash
        if (!localChanged && remoteChanged) {
            atomicCopy(files.remote, files.local); atomicCopy(files.remote, files.baseline)
            files.complete(remoteHash)
            return SyncOutcome(SyncStatus.SYNCED, external)
        }
        var merged = open
        var issues = emptyList<MergeIssue>()
        if (localChanged && remoteChanged) {
            val base = files.baseline.takeIf(File::exists)?.let { Kdbx.read(it, open.credentials) }
            val result = Merge.combine(open, external, base)
            issues = result.issues
            files.log("MERGE_ISSUES ${issues.size}")
            Kdbx.write(result.database, files.candidate)
            if (result.blocked) { files.log("MERGE_BLOCKED"); return SyncOutcome(SyncStatus.CONFLICT, issues = issues) }
            if (!isUnlocked()) return SyncOutcome(SyncStatus.LOCKED)
            merged = result.database
            atomicCopy(files.candidate, files.local)
        }
        if (!isUnlocked()) return SyncOutcome(SyncStatus.LOCKED)
        // metadata/download may have established actual provider capabilities.
        if (!provider.conditionalWrite && !approveUnconditional) {
            files.log("WRITE_APPROVAL_REQUIRED")
            return SyncOutcome(SyncStatus.APPROVAL_REQUIRED, merged, issues)
        }
        val now = provider.metadata()
        if (now.version != remote.version) throw StorageException(StorageError.CHANGED)
        if (remote.version == null) {
            val check = File.createTempFile("pre-write-", ".kdbx", files.remote.parentFile)
            try {
                provider.download(check)
                if (check.sha256() != remoteHash) throw StorageException(StorageError.CHANGED)
            } finally { check.delete() }
        }
        val condition = if (provider.conditionalWrite && remote.version != null) WriteCondition.Unchanged(remote.version) else WriteCondition.UserApproved
        atomicCopy(files.local, files.transaction)
        val hash = files.transaction.sha256()
        files.beginWrite(hash)
        provider.write(files.transaction, condition)
        atomicCopy(files.transaction, files.baseline)
        files.complete(hash)
        files.log("SYNC_COMPLETE")
        return SyncOutcome(if (issues.isEmpty()) SyncStatus.SYNCED else SyncStatus.CONFLICT, merged, issues)
    }
}
