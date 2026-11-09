package app.keyra

import android.content.Context
import androidx.work.*
import app.keyra.core.SyncStatus
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as KeyraApp).repository
        if (!repository.store.preferences.getBoolean("syncEnabled", true)) return Result.success()
        if (repository.providers.connection() == null) return Result.success()
        val result = repository.sync()
        if (!repository.store.preferences.getBoolean("syncEnabled", true)) return Result.success()
        return if (result == SyncStatus.OFFLINE || (result == SyncStatus.PENDING && repository.secrets.has("connection"))) Result.retry() else Result.success()
    }
    companion object {
        private fun constraints(): Constraints = Constraints.Builder()
            // Local documents and private-LAN WebDAV must work without validated Internet access.
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()
        fun schedule(context: Context) {
            if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("syncEnabled", true)) {
                WorkManager.getInstance(context).cancelUniqueWork("vault-sync")
                WorkManager.getInstance(context).cancelUniqueWork("vault-edit-sync")
                return
            }
            WorkManager.getInstance(context).cancelUniqueWork("vault-sync")
        }
        fun afterEdit(context: Context) {
            if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("syncEnabled", true)) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("vault-edit-sync", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
