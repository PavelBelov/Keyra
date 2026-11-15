package app.keyra

import androidx.core.content.edit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.*
import app.keemobile.kotpass.database.modifiers.*
import app.keemobile.kotpass.models.*
import app.keyra.core.*
import app.keyra.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.time.Instant
import java.util.UUID

class VaultRepository(private val context: Context) {
    val store = LocalStore(context)
    val secrets = SecureSecrets(context)
    val providers = Providers(context, secrets)
    private val gate = Mutex()
    private val configurationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webdavEditRevision = java.util.concurrent.atomic.AtomicLong()
    private val mutableDb = MutableStateFlow<KeePassDatabase?>(null)
    val database = mutableDb.asStateFlow()
    private val mutableStatus = MutableStateFlow(if (store.preferences.getBoolean("conflict", false)) SyncStatus.CONFLICT else SyncStatus.PENDING)
    val status = mutableStatus.asStateFlow()
    private val mutableSyncing = MutableStateFlow(false)
    val syncing = mutableSyncing.asStateFlow()
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    @Volatile private var generation = 0L
    @Volatile private var preparedConflictReview: ConflictReview? = null
    private var unlockMaterial: EncryptedValue? = null
    private val importConflict = File(store.directory, "import-conflict.kdbx")
    private val lockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockAction = Runnable { lock() }
    fun scheduleLock() { lockHandler.removeCallbacks(lockAction); lockHandler.postDelayed(lockAction, store.preferences.getInt("lockSeconds", 60) * 1000L) }
    fun cancelScheduledLock() { lockHandler.removeCallbacks(lockAction) }

    fun lock() {
        generation++; preparedConflictReview = null; mutableDb.value = null; unlockMaterial = null
        message.value = ""
    }
    fun quickMaterial(): ByteArray = unlockMaterial?.getBinary() ?: error("LOCKED")
    private fun material(password: String, keyfile: ByteArray?): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            val bytes = password.toByteArray()
            data.writeInt(bytes.size); data.write(bytes); bytes.fill(0)
            data.writeInt(keyfile?.size ?: -1); keyfile?.let(data::write)
        }; output.toByteArray()
    }
    private fun remember(bytes: ByteArray) { unlockMaterial = EncryptedValue.fromBinary(bytes.copyOf()) }
    // Keep the keyfile and local database in step, including a failed write.
    private fun writeWithKeyfile(keyfile: ByteArray?, write: () -> Unit) {
        val hadKey = secrets.has("vault-keyfile")
        val previous = secrets.getVaultKeyfile()
        try {
            secrets.putVaultKeyfile(keyfile)
            try { write() } catch (error: Exception) {
                if (hadKey) secrets.putVaultKeyfile(previous) else secrets.remove("vault-keyfile")
                throw error
            }
        } finally { previous?.fill(0) }
    }
    suspend fun quickUnlock(bytes: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val n = data.readInt(); require(n in 0..65536)
                val password = ByteArray(n).also(data::readFully)
                val k = data.readInt(); require(k in -1..1048576)
                val key = if (k < 0) null else ByteArray(k).also(data::readFully)
                try { unlock(password.toString(Charsets.UTF_8), key) } finally { password.fill(0); key?.fill(0) }
            }
        } finally { bytes.fill(0) }
    }
    suspend fun unlock(password: String, keyfile: ByteArray?) = operation {
        val token = generation
        val selectedKey = keyfile?.copyOf() ?: secrets.getVaultKeyfile()
        try {
            val db = Kdbx.read(store.local, Kdbx.credentials(password, selectedKey))
            if (token == generation) {
                secrets.putVaultKeyfile(selectedKey)
                val bytes = material(password, selectedKey); remember(bytes); bytes.fill(0)
                mutableDb.value = db
                if (store.preferences.getBoolean("conflict", false)) tryResolveSavedConflict()
            }
        } finally { selectedKey?.fill(0) }
    }
    suspend fun create(password: String, keyfile: ByteArray?) = operation {
        val token = generation
        check(!store.local.exists()) { "ALREADY_EXISTS" }
        val db = Kdbx.create(Kdbx.credentials(password, keyfile), L10n.text(context, R.string.ui_passwords_299), L10n.text(context, R.string.ui_recycle_bin_298))
        writeWithKeyfile(keyfile) { Kdbx.write(db, store.local) }
        secrets.remove("quick")
        if (token == generation) { val bytes = material(password, keyfile); remember(bytes); bytes.fill(0); mutableDb.value = db }
        store.log("DATABASE_CREATED")
    }
    suspend fun openExternal(file: File, password: String, keyfile: ByteArray?, connection: Connection?) = operation {
        val token = generation
        check(!store.local.exists()) { "ALREADY_EXISTS" }
        val db = Kdbx.read(file, Kdbx.credentials(password, keyfile))
        writeWithKeyfile(keyfile) { atomicCopy(file, store.local) }
        secrets.remove("quick")
        if (connection != null) { providers.save(connection); atomicCopy(file, store.baseline); store.complete(file.sha256()) }
        if (token == generation) { val bytes = material(password, keyfile); remember(bytes); bytes.fill(0); mutableDb.value = db }
        store.log("DATABASE_OPENED")
    }
    suspend fun download(connection: Connection, target: File) = withContext(Dispatchers.IO) { providers.create(connection).download(target) }
    suspend fun attach(connection: Connection) = withContext(Dispatchers.IO) {
        syncGate.withLock {
            val file = File.createTempFile("attach-", ".kdbx", context.cacheDir)
            try {
                val token = generation
                val db = gate.withLock {
                    val current = mutableDb.value ?: error("LOCKED")
                    current
                }
                val provider = providers.create(connection)
                val info = provider.download(file)
                require(info.writable != false) { "READ_ONLY_SOURCE" }
                val remote = Kdbx.read(file, db.credentials)
                require(remote.content.group.uuid == db.content.group.uuid) { "DIFFERENT_DATABASE" }
                operation {
                    check(generation == token && mutableDb.value?.content?.group?.uuid == db.content.group.uuid) { "LOCKED" }
                    check(!store.preferences.getBoolean("conflict", false)) { "RESOLVE_CONFLICT_FIRST" }
                    providers.save(connection); store.resetSourceState()
                    check(store.preferences.commitChanges { putBoolean("syncEnabled", true) })
                    SyncWorker.schedule(context)
                    mutableStatus.value = SyncStatus.PENDING
                    store.log("SOURCE_ATTACHED")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { operation { throw error } }
            finally { file.delete() }
        }
    }
    suspend fun mutate(transform: (KeePassDatabase) -> KeePassDatabase) = operation {
        check(!store.preferences.getBoolean("conflict", false)) { "RESOLVE_CONFLICT_FIRST" }
        val token = generation
        val db = mutableDb.value ?: error("LOCKED")
        val result = transform(db)
        Kdbx.write(result, store.local)
        store.logChanges(db, result)
        if (token == generation) mutableDb.value = result
        mutableStatus.value = SyncStatus.PENDING
        store.log("LOCAL_CHANGE")
        SyncWorker.afterEdit(context)
    }
    suspend fun changePassword(password: String, keyfile: ByteArray?) = operation {
        check(!store.preferences.getBoolean("conflict", false)) { "RESOLVE_CONFLICT_FIRST" }
        val token = generation
        val db = mutableDb.value ?: error("LOCKED")
        val result = db.modifyCredentials { Kdbx.credentials(password, keyfile) }
        writeWithKeyfile(keyfile) { Kdbx.write(result, store.local) }
        // External old-password copies require explicit reattachment; never overwrite automatically.
        providers.clear(); store.resetSourceState(); secrets.remove("quick")
        if (token == generation) { val bytes = material(password, keyfile); remember(bytes); bytes.fill(0); mutableDb.value = result }
        mutableStatus.value = SyncStatus.PENDING; store.log("MASTER_KEY_CHANGED")
    }
    suspend fun import(file: File, password: String, keyfile: ByteArray?) = operation {
        val token = generation
        val db = mutableDb.value ?: error("LOCKED")
        check(!store.preferences.getBoolean("conflict", false)) { "RESOLVE_CONFLICT_FIRST" }
        val other = Kdbx.read(file, Kdbx.credentials(password, keyfile))
        val result = Merge.combine(db, other, null, importing = true)
        if (result.blocked) {
            val normalized = when (other) {
                is KeePassDatabase.Ver3x -> other.copy(credentials = db.credentials)
                is KeePassDatabase.Ver4x -> other.copy(credentials = db.credentials)
            }
            Kdbx.write(normalized, importConflict)
            applyPartial(db, result.partialDatabase, true, result.issues, token)
            prepareConflictReview(db, normalized, null, true, result.issues, token)
        } else {
            val merged = DatabaseMetadataMerge.completed(result.database)
            Kdbx.write(merged, store.local)
            store.logChanges(db, merged)
            if (generation == token) mutableDb.value = merged
            mutableStatus.value = if (result.issues.isEmpty()) SyncStatus.PENDING else SyncStatus.CONFLICT
            SyncWorker.afterEdit(context)
        }
        store.log(if (result.blocked || result.issues.isNotEmpty()) "IMPORT_CONFLICT" else "IMPORT_COMPLETE")
    }
    private fun setConflict(importing: Boolean, issues: List<MergeIssue>,
                            partial: Boolean = store.preferences.getBoolean("partialMerge", false),
                            appliedHash: String? = store.preferences.getString("partialAppliedHash", null)) {
        preparedConflictReview = null
        check(store.preferences.commitChanges {
            putBoolean("conflict", true)
            putBoolean("importConflict", importing)
            putBoolean("partialMerge", partial)
            if (partial) putString("partialAppliedHash", appliedHash) else remove("partialAppliedHash")
            putString("issues", issues.joinToString("\n") { "${it.objectId ?: "DATABASE"}: ${it.reason}" })
        })
        mutableStatus.value = SyncStatus.CONFLICT
    }
    private fun currentConflictReview() = preparedConflictReview?.takeIf {
        it.generation == generation && it.active === mutableDb.value
    }

    /** Prepared forms only read memory; an early click can await the ongoing preparation. */
    suspend fun conflictReview(): ConflictReview? {
        if (!store.preferences.getBoolean("conflict", false)) return null
        currentConflictReview()?.let { return it }
        // A click can arrive while the sync is finishing preparation. Wait for that work,
        // without starting another read/merge or holding up a form already prepared.
        return gate.withLock {
            if (!store.preferences.getBoolean("conflict", false)) null
            else requireNotNull(currentConflictReview()) { "CONFLICT_REVIEW_CHANGED" }
        }
    }

    /** Called with gate held, after the encrypted snapshots and partial database are committed. */
    private fun prepareConflictReview(local: KeePassDatabase, remote: KeePassDatabase, base: KeePassDatabase?,
                                      importing: Boolean, issues: List<MergeIssue>, token: Long) {
        val active = mutableDb.value ?: return
        val review = ConflictReview(local, remote, base, importing, issues.distinctBy { it.key }, token,
            store.local.sha256(), conflictRemoteFile(importing).sha256(), conflictBaseFile(importing)?.sha256(),
            store.preferences.getString("sourceRevision", null), store.pendingWrite(), active,
            conflictOriginalFile()?.sha256())
        review.prepare(context)
        if (generation == token && mutableDb.value === active) {
            preparedConflictReview = review
            // lock() may race the publication without acquiring gate.
            if (generation != token || mutableDb.value !== active) preparedConflictReview = null
        }
    }

    internal suspend fun prepareConflictResolution(review: ConflictReview, choices: Map<MergeConflictKey, Boolean>) =
        withContext(Dispatchers.IO) {
            val selected = choices.toMap()
            PreparedConflictResolution(review, selected,
                Merge.combine(review.local, review.remote, review.base, review.importing, resolutions = selected))
        }

    suspend fun resolve(review: ConflictReview, choices: Map<MergeConflictKey, Boolean>) = operation {
        applyResolution(review, choices) {
            Merge.combine(review.local, review.remote, review.base, review.importing, resolutions = choices)
        }
    }

    internal suspend fun resolve(prepared: PreparedConflictResolution) = operation {
        applyResolution(prepared.review, prepared.choices) { prepared.result }
    }

    /** A prepared result never bypasses checks of the original encrypted snapshots. */
    private fun applyResolution(review: ConflictReview, choices: Map<MergeConflictKey, Boolean>, merge: () -> MergeResult) {
        val file = conflictRemoteFile(review.importing)
        check(generation == review.generation && mutableDb.value === review.active &&
            store.preferences.getBoolean("conflict", false) &&
            store.preferences.getBoolean("importConflict", false) == review.importing &&
            store.preferences.getString("sourceRevision", null) == review.sourceRevision &&
            store.pendingWrite() == review.pendingWrite &&
            conflictOriginalFile()?.sha256() == review.originalHash &&
            store.local.sha256() == review.localHash && file.sha256() == review.remoteHash &&
            conflictBaseFile(review.importing)?.sha256() == review.baseHash) { "CONFLICT_REVIEW_CHANGED" }
        check(review.issues.all { choices.containsKey(it.key) }) { "CONFLICT_CHOICES_REQUIRED" }
        val resolved = merge()
        check(!resolved.blocked) { "UNRESOLVABLE_TREE_EXPORT_REQUIRED" }
        applyMerged(review.active, resolved.database, review.importing, review.generation)
    }
    /** Called with gate held. Uncertain uploads still require explicit reconciliation. */
    private fun tryResolveSavedConflict(): Boolean {
        if (currentConflictReview() != null) return false
        val active = mutableDb.value ?: return false
        val token = generation
        val (local, remote, base, importing) = readConflictSources(active)
        val result = Merge.combine(local, remote, base, importing)
        check(token == generation && mutableDb.value === active) { "CONFLICT_REVIEW_CHANGED" }
        if (store.pendingWrite() != null) {
            prepareConflictReview(local, remote, base, importing, result.issues, token)
            return false
        }
        if (result.blocked) {
            applyPartial(active, result.partialDatabase, importing, result.issues, token)
            prepareConflictReview(local, remote, base, importing, result.issues, token)
            return false
        }
        applyMerged(active, result.database, importing, token)
        return true
    }
    private fun conflictRemoteFile(importing: Boolean) = if (importing) importConflict else store.remote
    private fun conflictBaseFile(importing: Boolean) = store.baseline.takeIf { !importing && it.exists() }
    private fun conflictOriginalFile() = store.conflictLocal.takeIf { store.preferences.getBoolean("partialMerge", false) }

    private data class ConflictSources(val local: KeePassDatabase, val remote: KeePassDatabase,
                                       val base: KeePassDatabase?, val importing: Boolean)

    /** Called with gate held; always reads the frozen original when a partial merge is active. */
    private fun readConflictSources(active: KeePassDatabase): ConflictSources {
        val importing = store.preferences.getBoolean("importConflict", false)
        val local = conflictOriginalFile()?.let { Kdbx.read(it, active.credentials) } ?: active
        val remote = Kdbx.read(conflictRemoteFile(importing), local.credentials)
        val base = conflictBaseFile(importing)?.let { Kdbx.read(it, local.credentials) }
        return ConflictSources(local, remote, base, importing)
    }

    /** Save the original left side and conflict intent before exposing the partial snapshot. */
    private fun applyPartial(before: KeePassDatabase, after: KeePassDatabase, importing: Boolean,
                             issues: List<MergeIssue>, token: Long, prepared: File? = null) {
        check(token == generation && mutableDb.value === before) { "CONFLICT_REVIEW_CHANGED" }
        val alreadyPartial = store.preferences.getBoolean("partialMerge", false)
        val localHash = store.local.sha256()
        if (alreadyPartial && store.preferences.getString("partialAppliedHash", null) == localHash) {
            setConflict(importing, issues)
            return
        }
        // Recover an interrupted application only while local still equals the saved original.
        if (alreadyPartial) check(localHash == store.conflictLocal.sha256()) { "CONFLICT_REVIEW_CHANGED" }
        val candidate = prepared ?: File.createTempFile("partial-", ".kdbx", context.cacheDir)
        try {
            if (prepared == null) Kdbx.write(after, candidate)
            if (!alreadyPartial) atomicCopy(store.local, store.conflictLocal)
            setConflict(importing, issues, partial = true, appliedHash = candidate.sha256())
            atomicCopy(candidate, store.local)
            store.logChanges(before, after)
            if (token == generation) mutableDb.value = after
        } finally { if (prepared == null) candidate.delete() }
    }

    private fun applyMerged(before: KeePassDatabase, after: KeePassDatabase, importing: Boolean, token: Long) {
        preparedConflictReview = null
        val merged = DatabaseMetadataMerge.completed(after)
        Kdbx.write(merged, store.local)
        store.logChanges(before, merged)
        if (!importing) atomicCopy(store.remote, store.baseline)
        check(store.preferences.commitChanges { putBoolean("conflict", false).remove("issues").remove("pendingWrite").remove("importConflict").remove("partialMerge").remove("partialAppliedHash") })
        store.conflictLocal.delete()
        if (token == generation) mutableDb.value = merged
        mutableStatus.value = SyncStatus.PENDING; store.log("CONFLICT_RESOLVED")
        SyncWorker.afterEdit(context)
    }
    suspend fun exportTo(output: OutputStream) = withContext(Dispatchers.IO) {
        gate.withLock {
            output.use { out -> store.local.inputStream().use { it.copyTo(out) } }
            store.log("EXPORT_COMPLETE")
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        webdavEditRevision.incrementAndGet()
        gate.withLock {
            check(store.preferences.commitChanges { putBoolean("syncEnabled", enabled) })
            SyncWorker.schedule(context)
        }
    }

    fun saveWebDavSettings(connection: Connection): Job {
        val edit = webdavEditRevision.incrementAndGet()
        val token = generation
        return configurationScope.launch {
            try {
                gate.withLock {
                    if (edit != webdavEditRevision.get() || generation != token || mutableDb.value == null) return@withLock
                    if (providers.connection() == connection && store.preferences.getBoolean("syncEnabled", true)) return@withLock
                    check(!store.preferences.getBoolean("conflict", false)) { "RESOLVE_CONFLICT_FIRST" }
                    providers.save(connection)
                    store.resetSourceState()
                    check(store.preferences.commitChanges { putBoolean("syncEnabled", true) })
                    SyncWorker.schedule(context)
                }
            } catch (_: Exception) { message.value = L10n.text(context, R.string.ui_could_not_save_webdav_settings_195) }
        }
    }

    private val syncGate = Mutex()
    private class SyncSuperseded : Exception()

    suspend fun sync(approve: Boolean = false): SyncStatus = withContext(Dispatchers.IO) {
        syncGate.withLock sync@ {
            if (!store.preferences.getBoolean("syncEnabled", true) || !secrets.has("connection")) return@sync mutableStatus.value
            mutableSyncing.value = true
            var session: SyncSession? = null
            var token = generation
            var revision: String? = null
            var failureCode: String? = null
            try {
                val connectionRevision = store.preferences.getString("sourceRevision", null)
                val connection = try { providers.connection() } catch (_: Exception) {
                    mutableStatus.value = SyncStatus.AUTH_REQUIRED; return@sync mutableStatus.value
                } ?: return@sync mutableStatus.value
                var originalHash = ""
                var open: KeePassDatabase? = null
                var previousModified = -1L
                var previousUri: String? = null
                var previousHash: String? = null
                var webdavUri: String? = null
                var webdavModified = -1L
                var webdavVersion: String? = null
                gate.withLock snapshot@ {
                    if (store.preferences.getBoolean("conflict", false) && !tryResolveSavedConflict()) {
                        mutableStatus.value = SyncStatus.CONFLICT; return@snapshot
                    }
                    if (store.preferences.getString("sourceRevision", null) != connectionRevision) throw SyncSuperseded()
                    token = generation
                    revision = store.preferences.getString("sourceRevision", null)
                    originalHash = store.local.sha256()
                    open = mutableDb.value
                    previousModified = store.preferences.getLong("fileSyncModified", -1)
                    previousUri = store.preferences.getString("fileSyncUri", null)
                    previousHash = store.preferences.getString("lastHash", null)
                    webdavUri = store.preferences.getString("webdavSyncUri", null)
                    webdavModified = store.preferences.getLong("webdavSyncModified", -1)
                    webdavVersion = store.preferences.getString("webdavSyncVersion", null)
                    session = SyncSession(context.cacheDir, store) { transaction, hash ->
                        // The synchronous engine checkpoints only local state under this short lock.
                        runBlocking {
                            gate.withLock {
                                if (generation != token || store.preferences.getString("sourceRevision", null) != revision ||
                                    !store.preferences.getBoolean("syncEnabled", true) || store.preferences.getBoolean("conflict", false) ||
                                    store.local.sha256() != originalHash) throw SyncSuperseded()
                                atomicCopy(transaction, store.transaction)
                                store.beginWrite(hash)
                            }
                        }
                    }
                }
                val work = session ?: return@sync mutableStatus.value
                mutableStatus.value = SyncStatus.RUNNING
                val provider = providers.create(connection)
                val modified = (provider as? SafStorage)?.let { runCatching { it.lastModified() }.getOrNull() }
                val remoteMetadata = (provider as? WebDavStorage)?.metadata()
                val unchanged = modified != null && previousUri == connection.location && previousModified == modified &&
                    work.pendingWrite() == null && work.baseline.exists() && originalHash == previousHash
                val webdavUnchanged = remoteMetadata != null && webdavUri == connection.location &&
                    (remoteMetadata.modifiedAt != null || remoteMetadata.version != null) &&
                    (remoteMetadata.modifiedAt ?: -1) == webdavModified && remoteMetadata.version == webdavVersion &&
                    work.pendingWrite() == null && work.baseline.exists() && originalHash == previousHash
                val skipRead = webdavUnchanged || (unchanged && runCatching { (provider as SafStorage).lastModified() == modified }.getOrDefault(false))
                val outcome = if (skipRead) {
                    store.log("FILE_UNCHANGED"); work.complete(originalHash); SyncOutcome(SyncStatus.SYNCED)
                } else SyncEngine(work).run(provider, open, approve) {
                    generation == token && mutableDb.value != null
                }
                val verifiedModified = if (provider is SafStorage && outcome.status == SyncStatus.SYNCED) {
                    val verified = if (skipRead) modified else provider.verifiedModified
                    verified?.takeIf { runCatching { provider.lastModified() }.getOrNull() == it }
                } else null
                val verifiedWebdav = if (provider is WebDavStorage && outcome.status == SyncStatus.SYNCED) {
                    val verified = if (skipRead) remoteMetadata else provider.verifiedMetadata
                    val after = provider.metadata()
                    verified?.takeIf { it.modifiedAt == after.modifiedAt && it.version == after.version }
                } else null
                gate.withLock {
                    if (generation != token || store.preferences.getString("sourceRevision", null) != revision ||
                        !store.preferences.getBoolean("syncEnabled", true) || store.preferences.getBoolean("conflict", false) ||
                        store.local.sha256() != originalHash) throw SyncSuperseded()
                    val synchronizedDatabase = outcome.database
                    val unresolved = outcome.issues.any { it.requiresChoice }
                    // Copy remote/baseline first; applyPartial persists the conflict's original
                    // local side and intent before replacing the active encrypted database.
                    work.apply(replaceLocal = synchronizedDatabase != null && !unresolved)
                    if (unresolved && synchronizedDatabase != null) {
                        applyPartial(requireNotNull(mutableDb.value), synchronizedDatabase, false, outcome.issues, token, work.local)
                    } else {
                        if (synchronizedDatabase != null) {
                            mutableDb.value?.let { store.logChanges(it, synchronizedDatabase) }
                            mutableDb.value = synchronizedDatabase
                        }
                        if (unresolved) setConflict(false, outcome.issues)
                        else if (outcome.status == SyncStatus.CONFLICT && store.pendingWrite() != null) setConflict(false, emptyList(), partial = false)
                    }
                    if (store.preferences.getBoolean("conflict", false)) {
                        val active = requireNotNull(mutableDb.value)
                        val (local, remote, base, importing) = readConflictSources(active)
                        val issues = if (store.pendingWrite() != null) Merge.combine(local, remote, base).issues else outcome.issues
                        prepareConflictReview(local, remote, base, importing, issues, token)
                    }
                    if (provider is SafStorage && outcome.status == SyncStatus.SYNCED) {
                        check(store.preferences.commitChanges {
                            remove("fileSyncUri"); remove("fileSyncModified")
                            if (verifiedModified != null) putString("fileSyncUri", connection.location).putLong("fileSyncModified", verifiedModified)
                        })
                    }
                    if (provider is WebDavStorage && outcome.status == SyncStatus.SYNCED) {
                        check(store.preferences.commitChanges {
                            remove("webdavSyncUri"); remove("webdavSyncModified"); remove("webdavSyncVersion")
                            if (verifiedWebdav != null) {
                                putString("webdavSyncUri", connection.location)
                                putLong("webdavSyncModified", verifiedWebdav.modifiedAt ?: -1)
                                putString("webdavSyncVersion", verifiedWebdav.version)
                            }
                        })
                    }
                    mutableStatus.value = outcome.status
                }
            } catch (cancelled: CancellationException) {
                mutableStatus.value = SyncStatus.PENDING
                throw cancelled
            } catch (_: SyncSuperseded) {
                mutableStatus.value = SyncStatus.PENDING
                SyncWorker.afterEdit(context)
            } catch (error: StorageException) {
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                val certificateApproval = causes.any { it is WebDavCertificateApprovalRequired }
                val tlsFailure = causes.any { it is javax.net.ssl.SSLException }
                failureCode = when {
                    certificateApproval -> "CERTIFICATE"
                    causes.any { it is java.security.cert.CertificateExpiredException } -> "CERTIFICATE_EXPIRED"
                    causes.any { it is java.security.cert.CertificateNotYetValidException } -> "CERTIFICATE_NOT_YET_VALID"
                    causes.any { it is javax.net.ssl.SSLPeerUnverifiedException } -> "CERTIFICATE_HOSTNAME"
                    tlsFailure -> "TLS"
                    else -> error.kind.name
                }
                mutableStatus.value = if (certificateApproval) SyncStatus.AUTH_REQUIRED else if (tlsFailure) SyncStatus.ERROR else when (error.kind) {
                    StorageError.NETWORK, StorageError.TEMPORARY, StorageError.NOT_FOUND, StorageError.PERMISSION -> SyncStatus.OFFLINE
                    StorageError.AUTH -> SyncStatus.AUTH_REQUIRED
                    StorageError.CHANGED -> SyncStatus.PENDING
                    StorageError.UNKNOWN_WRITE -> SyncStatus.CONFLICT
                    else -> SyncStatus.ERROR
                }
                // Keep the active local vault and any upload transaction intact for reconciliation.
                store.log("STORAGE_${error.kind.name}")
            } catch (error: Exception) {
                if (error is IllegalArgumentException) failureCode = "CONFIGURATION"
                mutableStatus.value = SyncStatus.ERROR
                store.log("SYNC_ERROR")
            } finally {
                runCatching { session?.close() }
                try {
                    store.log("SYNC_RESULT ${mutableStatus.value.name}" + failureCode?.let { " $it" }.orEmpty())
                    store.preferences.edit { putLong("lastSyncAttempt", System.currentTimeMillis()).putString("lastSyncResult", mutableStatus.value.name).putString("lastSyncDetailCode", failureCode).remove("lastSyncDetail") }
                } finally { mutableSyncing.value = false }
            }
            notifyStatus(mutableStatus.value)
            mutableStatus.value
        }
    }
    private suspend fun operation(block: () -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            busy.value = true; message.value = ""
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                store.log("OPERATION_FAILED ${e.javaClass.simpleName.uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(64)}")
                message.value = when (e.message) {
                    "CONFLICT_REVIEW_CHANGED" -> L10n.text(context, R.string.merge_review_changed)
                    "CONFLICT_CHOICES_REQUIRED" -> L10n.text(context, R.string.merge_choose_all)
                    "DIFFERENT_DATABASE" -> L10n.text(context, R.string.ui_this_file_is_a_different_database_use_import_to_merge_its_data_205)
                    "RESOLVE_CONFLICT_FIRST" -> L10n.text(context, R.string.ui_resolve_the_sync_conflict_first_206)
                    "READ_ONLY_SOURCE" -> L10n.text(context, R.string.ui_the_source_is_read_only_open_a_separate_copy_or_import_its_data_207)
                    "ENTRY_CHANGED_DURING_EDIT" -> L10n.text(context, R.string.ui_this_entry_changed_while_you_were_editing_it_your_draft_is_still__208)
                    "ENTRY_NO_LONGER_EXISTS" -> L10n.text(context, R.string.ui_this_entry_was_deleted_while_you_were_editing_it_your_draft_is_st_209)
                    "PARENT_GROUP_MISSING" -> L10n.text(context, R.string.ui_the_folder_for_the_new_entry_no_longer_exists_210)
                    "UNRESOLVABLE_TREE_EXPORT_REQUIRED" -> L10n.text(context, R.string.ui_the_database_structure_could_not_be_reconciled_export_both_versio_211)
                    else -> L10n.text(context, R.string.ui_operation_failed_check_the_password_key_file_access_permissions_a_212)
                }
            } finally { busy.value = false }
        }
    }
    private fun notifyStatus(status: SyncStatus) {
        if (status == SyncStatus.SYNCED) { store.preferences.edit { remove("notifiedStatus") }; return }
        if (status !in setOf(SyncStatus.ERROR, SyncStatus.CONFLICT, SyncStatus.AUTH_REQUIRED)) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (store.preferences.getString("notifiedStatus", null) == status.name) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sync", L10n.text(context, R.string.ui_sync_108), NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(context).notify(1, NotificationCompat.Builder(context, "sync").setSmallIcon(R.drawable.ic_keyra)
                .setContentTitle(L10n.text(context, R.string.app_name)).setContentText(L10n.text(context, R.string.ui_sync_needs_attention_open_the_app_213))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).setContentIntent(pending).setAutoCancel(true).build())
            store.preferences.edit { putString("notifiedStatus", status.name) }
        }
    }
}
