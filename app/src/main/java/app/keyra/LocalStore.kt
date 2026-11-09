package app.keyra

import android.content.Context
import app.keyra.core.*
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

class LocalStore(private val context: Context) {
    val directory = File(context.filesDir, "vault").apply { mkdirs() }
    val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val local = File(directory, "database.kdbx")
    val baseline = File(directory, "baseline.kdbx")
    val remote = File(directory, "remote.kdbx")
    val transaction = File(directory, "transaction.kdbx")
    val journal = File(directory, "journal.txt")
    val journalRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun log(event: String) = logEvents(listOf(event))
    /** One atomic journal write per vault mutation, even for a large import. */
    @Synchronized fun logEvents(events: List<String>) {
        events.forEach { require(it.matches(eventPattern)) }
        val visible = events.filter { journalInformation(context, it) != null }
        if (visible.isEmpty()) return
        val now = Instant.now()
        retainJournal(now, visible.map { "$now $it" })
        journalRevision.value += 1
    }
    @Synchronized fun journalEntries(): List<JournalEntry> = parseJournal(context, retainJournal(Instant.now()))
    private fun retainJournal(now: Instant, newEvents: List<String> = emptyList()): String {
        val previous = if (journal.exists()) journal.readText() else ""
        val cutoff = now.minus(30, ChronoUnit.DAYS)
        val retained = (previous.lineSequence() + newEvents.asSequence()).mapNotNull { line ->
            val separator = line.indexOf(' ')
            if (separator <= 0) return@mapNotNull null
            val time = runCatching { Instant.parse(line.substring(0, separator)) }.getOrNull() ?: return@mapNotNull null
            if (time.isBefore(cutoff) || journalInformation(context, line.substring(separator + 1)) == null) null else time to line
        }.sortedByDescending { it.first }.take(1000).toList().asReversed().map { it.second }
        val text = if (retained.isEmpty()) "" else retained.joinToString("\n", postfix = "\n")
        if (text != previous) atomicWrite(journal) { it.writeText(text) }
        return text
    }
    fun beginWrite(hash: String) { check(preferences.commitChanges { putString("pendingWrite", hash) }) }
    fun pendingWrite(): String? = preferences.getString("pendingWrite", null)
    fun complete(hash: String) {
        check(preferences.commitChanges { remove("pendingWrite").putString("lastHash", hash).putLong("lastSync", System.currentTimeMillis()) })
    }
    fun resetSourceState() {
        check(preferences.commitChanges {
            remove("pendingWrite"); remove("lastHash"); remove("lastSync")
            remove("lastSyncAttempt"); remove("lastSyncResult"); remove("lastSyncDetail"); remove("lastSyncDetailCode")
            remove("fileSyncUri"); remove("fileSyncModified")
            remove("webdavSyncUri"); remove("webdavSyncModified"); remove("webdavSyncVersion")
        })
        baseline.delete(); remote.delete(); transaction.delete()
        File(directory, "candidate.kdbx").delete() // Clean up files left by older builds.
    }
    private companion object {
        val eventPattern = Regex("[A-Z0-9_ :.-]{1,160}")
    }
}
