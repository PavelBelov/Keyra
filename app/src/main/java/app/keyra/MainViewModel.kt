package app.keyra

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.models.Entry
import app.keyra.core.Kdbx
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Panel { SETTINGS, DATA, IMPORT, JOURNAL }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as KeyraApp).repository
    var group: UUID? = null
    var entry: UUID? = null
    var query = ""
    var searching = false
    var autofillInitialized = false
    var autofillPickerToken: String? = null
    var linkingAutofill = false
    var panel: Panel? = null
    var syncSourceKind: String? = null
    var editing: EntryDraft? = null
    var generator: PasswordGeneratorDraft? = null
    val saving = MutableStateFlow(false)
    var importDraft: ImportDraft? = null
    val importingDatabase = MutableStateFlow(false)
    fun discardImport() { importDraft?.dispose(); importDraft = null }
    fun startImport() {
        val draft = importDraft ?: return
        if (importingDatabase.value || repository.database.value == null) return
        val password = draft.passwordText()
        if (password.isEmpty()) { draft.error = L10n.text(getApplication<Application>(), R.string.ui_enter_a_password_148); return }
        val key = draft.key?.copyOf()
        importingDatabase.value = true
        viewModelScope.launch {
            var file: File? = null
            try {
                val source = withContext(Dispatchers.IO) {
                    File.createTempFile("import-", ".kdbx", getApplication<Application>().cacheDir).also { target ->
                        file = target
                        requireNotNull(getApplication<Application>().contentResolver.openInputStream(draft.uri))
                            .use { input -> target.outputStream().use(input::copyTo) }
                    }
                }
                if (importDraft !== draft || repository.database.value == null) return@launch
                repository.import(source, password, key)
                if (importDraft === draft && repository.database.value != null) {
                    if (repository.message.value.isEmpty()) { discardImport(); panel = Panel.DATA }
                    else draft.error = repository.message.value
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (importDraft === draft) draft.error = L10n.text(getApplication<Application>(), R.string.ui_could_not_import_the_database_check_the_file_password_and_key_189) }
            finally { key?.fill(0); file?.delete(); importingDatabase.value = false }
        }
    }

    fun discardEditing() {
        generator?.dispose(); generator = null
        editing?.dispose(); editing = null
    }

    fun saveEditing() {
        val draft = editing ?: return
        if (saving.value || repository.database.value == null) return
        val original = draft.original
        draft.addPendingTags()
        val tags = draft.tags.toList()
        val fields = draft.snapshot()
        saving.value = true
        viewModelScope.launch {
            try {
                repository.mutate { db ->
                    val updated: Entry
                    val parent: UUID
                    if (original == null) {
                        check(Kdbx.group(db, draft.parent) != null) { "PARENT_GROUP_MISSING" }
                        updated = Entry(draft.id, fields = fields, tags = tags); parent = draft.parent
                    } else {
                        val current = db.getEntry { it.uuid == draft.id } ?: error("ENTRY_NO_LONGER_EXISTS")
                        check(sameEntryFields(current.second.fields, original.fields) && current.second.tags == original.tags) { "ENTRY_CHANGED_DURING_EDIT" }
                        updated = Kdbx.edit(current.second, fields, tags); parent = current.first.uuid
                    }
                    Kdbx.putEntry(db, parent, updated)
                }
                val saved = repository.database.value?.getEntry { it.uuid == draft.id }?.second
                if (editing === draft && saved != null && repository.message.value.isEmpty() && sameEntryFields(saved.fields, fields) && saved.tags == tags) {
                    editing = null; generator?.dispose(); generator = null
                    entry = draft.id; draft.dispose()
                }
            } finally { saving.value = false }
        }
    }

    override fun onCleared() { discardEditing(); discardImport(); super.onCleared() }
}
