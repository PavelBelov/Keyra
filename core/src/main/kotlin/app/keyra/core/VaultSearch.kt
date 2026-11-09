package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.findEntries
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group

/** Search unprotected field values and native KeePass tags. */
fun KeePassDatabase.searchUnprotected(query: String): List<Pair<Group, List<Entry>>> = findEntries { entry ->
    entry.tags.any { it.contains(query, ignoreCase = true) } ||
        entry.fields.values.any { value -> value is EntryValue.Plain && value.content.contains(query, ignoreCase = true) }
}
