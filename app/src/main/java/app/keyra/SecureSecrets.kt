package app.keyra

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate Keystore keys for system-authenticated unlock, local keyfiles and transport credentials. */
class SecureSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private fun key(auth: Boolean, keyfile: Boolean = false): SecretKey {
        val alias = if (keyfile) "keyra-keyfile-v1" else if (auth) "keyra-vault-v1" else "keyra-transport-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(auth)
            if (auth) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            init(spec.build()); generateKey()
        }
    }
    fun encryptCipher(auth: Boolean) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(auth)) }
    fun decryptCipher(name: String, auth: Boolean) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val iv = Base64.decode(prefs.getString("$name.iv", null) ?: error("SECRET_MISSING"), Base64.NO_WRAP)
        init(Cipher.DECRYPT_MODE, key(auth), GCMParameterSpec(128, iv))
    }
    fun save(name: String, cipher: Cipher, bytes: ByteArray) {
        val encrypted = cipher.doFinal(bytes)
        check(prefs.commitChanges { putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)) })
    }
    fun read(name: String, cipher: Cipher): ByteArray = cipher.doFinal(Base64.decode(prefs.getString(name, null) ?: error("SECRET_MISSING"), Base64.NO_WRAP))
    fun has(name: String) = prefs.contains(name)
    fun remove(name: String) { check(prefs.commitChanges { remove(name).remove("$name.iv") }) }
    fun putTransport(name: String, value: ByteArray) = save(name, encryptCipher(false), value)
    fun getTransport(name: String) = read(name, decryptCipher(name, false))
    fun putVaultKeyfile(bytes: ByteArray?) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(false, keyfile = true)) }
        // A presence byte distinguishes an absent key from an empty keyfile.
        val encoded = if (bytes == null) byteArrayOf(0) else byteArrayOf(1) + bytes
        try { save("vault-keyfile", cipher, encoded) } finally { encoded.fill(0) }
    }
    fun getVaultKeyfile(): ByteArray? {
        if (!has("vault-keyfile")) return null
        val iv = Base64.decode(prefs.getString("vault-keyfile.iv", null) ?: error("SECRET_MISSING"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false, keyfile = true), GCMParameterSpec(128, iv))
        }
        val encoded = read("vault-keyfile", cipher)
        try {
            check(encoded.isNotEmpty() && encoded[0].toInt() in 0..1) { "SECRET_INVALID" }
            return if (encoded[0].toInt() == 0) null else encoded.copyOfRange(1, encoded.size)
        } finally { encoded.fill(0) }
    }
}
