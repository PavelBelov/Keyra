package app.keyra

import android.net.Uri

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.EntryValue

class ImportDraft(val uri: Uri) {
    var key: ByteArray? = null
        private set
    private var password: EntryValue.Encrypted? = null
    var error = ""
    fun passwordText() = password?.content.orEmpty()
    fun updatePassword(value: String) { password = EntryValue.Encrypted(EncryptedValue.fromString(value)); error = "" }
    fun selectKey(value: ByteArray?) { key?.fill(0); key = value; error = "" }
    fun dispose() { key?.fill(0); key = null; password = null; error = "" }
}
