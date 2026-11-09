package app.keyra.storage

import androidx.core.net.toUri
import androidx.core.content.edit

import android.content.Context
import app.keyra.SecureSecrets
import app.keyra.SyncWorker
import app.keyra.core.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Connection(val kind: String, val location: String, val user: String = "", val password: String = "") {
    fun bytes() = JSONObject().put("kind", kind).put("location", location).put("user", user).put("password", password).toString().toByteArray()
    companion object { fun decode(data: ByteArray): Connection = JSONObject(data.toString(Charsets.UTF_8)).let { Connection(it.getString("kind"), it.getString("location"), it.optString("user"), it.optString("password")) } }
}

class Providers(private val context: Context, private val secrets: SecureSecrets) {
    val certificates = WebDavCertificates(secrets)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val factories = mapOf<String, (Connection) -> StorageProvider>(
        "webdav" to { c -> WebDavStorage(c.location, c.user, c.password, certificates.client(client, c.location)) },
        "saf" to { c -> SafStorage(context, c.location.toUri()) }
    )
    fun connection(): Connection? = if (secrets.has("connection")) secrets.getTransport("connection").let { bytes -> try { Connection.decode(bytes).takeIf { it.kind in factories } } finally { bytes.fill(0) } } else null
    fun save(connection: Connection) {
        val data = connection.bytes(); try { secrets.putTransport("connection", data) } finally { data.fill(0) }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { putString("sourceKind", connection.kind).putString("sourceRevision", java.util.UUID.randomUUID().toString()) }
        SyncWorker.schedule(context)
    }
    fun clear() {
        secrets.remove("connection")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { remove("sourceKind").putString("sourceRevision", java.util.UUID.randomUUID().toString()) }
    }
    fun create(connection: Connection): StorageProvider = factories[connection.kind]?.invoke(connection) ?: throw StorageException(StorageError.UNSUPPORTED)
}
