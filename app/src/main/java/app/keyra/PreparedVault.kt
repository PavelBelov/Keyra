package app.keyra

import app.keyra.storage.Connection
import java.io.File

/** A downloaded encrypted database, owned by the first-run opening flow. */
class PreparedVault(val file: File, val connection: Connection?, val label: String, val writable: Boolean) {
    fun dispose() { file.delete() }
}
