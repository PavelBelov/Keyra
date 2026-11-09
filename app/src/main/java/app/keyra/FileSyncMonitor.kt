package app.keyra

import android.net.Uri

import androidx.core.net.toUri

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** Runs only while the application process is alive; no five-minute background alarm. */
class FileSyncMonitor(private val context: Context, private val repository: VaultRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configuration = MutableStateFlow(0L)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sourceRevision" || key == "sourceKind" || key == "syncEnabled") {
            configuration.value = configuration.value + 1
        }
    }

    fun start() {
        repository.store.preferences.registerOnSharedPreferenceChangeListener(listener)
        scope.launch {
            configuration.collectLatest {
                if (!repository.store.preferences.getBoolean("syncEnabled", true)) return@collectLatest
                val connection = withContext(Dispatchers.IO) { runCatching { repository.providers.connection() }.getOrNull() }
                when (connection?.kind) {
                    "saf" -> watch(connection.location.toUri())
                    "webdav" -> {
                        delay(1_000) // Coalesce configuration edits before checking the source.
                        while (currentCoroutineContext().isActive) {
                            repository.sync()
                            delay(5 * 60 * 1_000L)
                        }
                    }
                }
            }
        }
    }

    private suspend fun watch(uri: Uri) {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { changes.trySend(Unit) }
            override fun onChange(selfChange: Boolean, changedUri: Uri?) { changes.trySend(Unit) }
        }
        val notificationUri = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.query(uri, null, null, null, null)?.use { it.notificationUri } }.getOrNull()
        }
        var registered = false
        try {
            for (target in listOfNotNull(uri, notificationUri).distinct()) {
                if (runCatching { context.contentResolver.registerContentObserver(target, true, observer) }.isSuccess) registered = true
            }
            coroutineScope {
                launch { repository.sync() } // Check immediately on process start or source change.
                launch {
                    for (event in changes) {
                        // Wait until a burst of writes has settled before reading the database.
                        do { delay(1_000) } while (changes.tryReceive().isSuccess)
                        repository.sync()
                    }
                }
                launch {
                    while (isActive) {
                        delay(5 * 60 * 1_000L)
                        repository.sync()
                    }
                }
                awaitCancellation()
            }
        } finally {
            if (registered) runCatching { context.contentResolver.unregisterContentObserver(observer) }
            changes.close()
        }
    }
}
