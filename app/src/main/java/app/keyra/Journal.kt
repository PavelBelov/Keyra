package app.keyra

import java.time.Instant
import android.content.Context

data class JournalEntry(val time: Instant, val information: String)

fun syncFailureDescription(context: Context, code: String?): String? {
    val resource = when (code) {
        "NOT_FOUND" -> R.string.ui_source_file_not_found_12
        "PERMISSION" -> R.string.ui_access_to_source_denied_13
        "AUTH" -> R.string.ui_authorization_required_14
        "NETWORK", "TEMPORARY" -> R.string.ui_source_unavailable_15
        "QUOTA" -> R.string.ui_storage_is_full_16
        "CHANGED" -> R.string.ui_source_changed_during_sync_17
        "UNKNOWN_WRITE" -> R.string.ui_upload_result_unknown_reconciliation_required_18
        "CERTIFICATE" -> R.string.ui_server_certificate_requires_approval_19
        "CERTIFICATE_EXPIRED" -> R.string.ui_certificate_has_expired_197
        "CERTIFICATE_NOT_YET_VALID" -> R.string.ui_certificate_is_not_yet_valid_198
        "CERTIFICATE_HOSTNAME" -> R.string.ui_certificate_does_not_match_the_server_name_199
        "TLS" -> R.string.ui_tls_certificate_verification_failed_20
        "CONFIGURATION" -> R.string.ui_invalid_source_settings_21
        else -> return null
    }
    return L10n.text(context, resource)
}

fun journalInformation(context: Context, event: String): String? {
    val parts = event.split(' ')
    val code = parts.firstOrNull().orEmpty()
    if (code.startsWith("ENTRY_") || code.startsWith("GROUP_")) {
        val information = when (code) {
            "ENTRY_ADDED" -> L10n.text(context, R.string.ui_entry_added_282)
            "ENTRY_EDITED" -> L10n.text(context, R.string.ui_entry_edited_283)
            "ENTRY_DELETED" -> L10n.text(context, R.string.ui_entry_permanently_deleted_284)
            "ENTRY_TRASHED" -> L10n.text(context, R.string.ui_entry_moved_to_recycle_bin_285)
            "ENTRY_MOVED" -> L10n.text(context, R.string.ui_entry_moved_286)
            "ENTRY_RESTORED" -> L10n.text(context, R.string.ui_entry_restored_from_recycle_bin_287)
            "GROUP_ADDED" -> L10n.text(context, R.string.ui_folder_added_288)
            "GROUP_EDITED" -> L10n.text(context, R.string.ui_folder_edited_289)
            "GROUP_DELETED" -> L10n.text(context, R.string.ui_folder_permanently_deleted_290)
            "GROUP_TRASHED" -> L10n.text(context, R.string.ui_folder_moved_to_recycle_bin_291)
            "GROUP_MOVED" -> L10n.text(context, R.string.ui_folder_moved_292)
            "GROUP_RESTORED" -> L10n.text(context, R.string.ui_folder_restored_from_recycle_bin_293)
            else -> return null
        }
        return information + parts.getOrNull(1)?.let { " · ${it.take(8)}" }.orEmpty()
    }
    if (code == "SYNC_RESULT") {
        val detail = syncFailureDescription(context, parts.getOrNull(2))
        return when (parts.getOrNull(1)) {
            "SYNCED" -> L10n.text(context, R.string.ui_database_synced_successful_22)
            "PENDING" -> L10n.text(context, R.string.ui_sync_incomplete_23, detail ?: L10n.text(context, R.string.ui_unsent_changes_retry_scheduled_24))
            "LOCKED" -> L10n.text(context, R.string.ui_sync_postponed_database_locked_25)
            "CONFLICT" -> L10n.text(context, R.string.ui_sync_failed_26, detail ?: L10n.text(context, R.string.ui_data_conflict_27))
            "APPROVAL_REQUIRED" -> L10n.text(context, R.string.ui_sync_awaiting_upload_approval_28)
            "AUTH_REQUIRED" -> L10n.text(context, R.string.ui_sync_failed_26, detail ?: L10n.text(context, R.string.ui_authorization_required_14))
            "OFFLINE" -> L10n.text(context, R.string.ui_sync_failed_26, detail ?: L10n.text(context, R.string.ui_source_unavailable_15))
            else -> L10n.text(context, R.string.ui_sync_failed_26, detail ?: L10n.text(context, R.string.ui_operation_could_not_be_completed_29))
        }
    }
    return when (code) {
        "DATABASE_CREATED" -> L10n.text(context, R.string.ui_database_created_30)
        "DATABASE_OPENED" -> L10n.text(context, R.string.ui_database_connected_31)
        "MASTER_KEY_CHANGED" -> L10n.text(context, R.string.ui_database_password_changed_32)
        "IMPORT_COMPLETE" -> L10n.text(context, R.string.ui_database_import_completed_33)
        "EXPORT_COMPLETE" -> L10n.text(context, R.string.ui_database_exported_34)
        "KEY_EXPORT_COMPLETE" -> L10n.text(context, R.string.ui_key_file_exported_35)
        "IMPORT_CONFLICT" -> L10n.text(context, R.string.ui_database_imported_with_conflicts_select_versions_to_keep_36)
        "CONFLICT_RESOLVED" -> L10n.text(context, R.string.ui_data_conflict_resolved_37)
        "SOURCE_ATTACHED" -> L10n.text(context, R.string.ui_sync_source_connected_38)
        "SOURCE_DETACHED" -> L10n.text(context, R.string.ui_sync_source_disconnected_39)
        "OPERATION_FAILED" -> L10n.text(context, R.string.ui_operation_failed_40) + parts.getOrNull(1)?.let { " · $it" }.orEmpty()
        else -> null
    }
}

fun parseJournal(context: Context, text: String): List<JournalEntry> = text.lineSequence().mapNotNull { line ->
    val split = line.indexOf(' ')
    if (split <= 0) return@mapNotNull null
    val time = runCatching { Instant.parse(line.substring(0, split)) }.getOrNull() ?: return@mapNotNull null
    journalInformation(context, line.substring(split + 1))?.let { JournalEntry(time, it) }
}.toList().sortedByDescending { it.time }
