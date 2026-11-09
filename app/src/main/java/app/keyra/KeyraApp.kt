package app.keyra

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class KeyraApp : Application() {
    lateinit var repository: VaultRepository; private set
    private lateinit var fileSyncMonitor: FileSyncMonitor
    override fun onCreate() {
        super.onCreate()
        ApplicationTheme.applySaved(this)
        ApplicationLanguage.applySaved(this)
        repository = VaultRepository(this)
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { repository.lock() }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        SyncWorker.schedule(this)
        fileSyncMonitor = FileSyncMonitor(this, repository).also { it.start() }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { ApplicationLanguage.applySaved(context) }
        }, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ApplicationLanguage.applySaved(this)
    }
}
