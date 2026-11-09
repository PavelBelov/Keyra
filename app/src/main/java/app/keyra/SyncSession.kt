package app.keyra

import app.keyra.core.*
import java.io.Closeable
import java.io.File

/** Encrypted working files isolated from edits to the active vault. */
internal class SyncSession(
    cache: File,
    private val store: LocalStore,
    private val beforeUpload: (File, String) -> Unit
) : SyncWorkspace, Closeable {
    private val directory = java.nio.file.Files.createTempDirectory(cache.toPath(), "sync-").toFile()
    override val local = File(directory, "local.kdbx")
    override val baseline = File(directory, "baseline.kdbx")
    override val remote = File(directory, "remote.kdbx")
    override val candidate = File(directory, "candidate.kdbx")
    override val transaction = File(directory, "transaction.kdbx")
    private var pending = store.pendingWrite()
    private var completedHash: String? = null

    init {
        try {
            listOf(store.local to local, store.baseline to baseline, store.transaction to transaction)
                .forEach { (source, target) -> if (source.exists()) atomicCopy(source, target) }
        } catch (error: Exception) { close(); throw error }
    }
    override fun log(event: String) = store.log(event)
    override fun pendingWrite() = pending
    override fun beginWrite(hash: String) {
        beforeUpload(transaction, hash)
        pending = hash
    }
    override fun complete(hash: String) { completedHash = hash; pending = null }
    fun apply(replaceLocal: Boolean) {
        listOf(baseline to store.baseline, remote to store.remote, transaction to store.transaction)
            .forEach { (source, target) -> if (source.exists()) atomicCopy(source, target) }
        if (replaceLocal) atomicCopy(local, store.local)
        completedHash?.let(store::complete)
    }
    override fun close() { directory.deleteRecursively() }
}
