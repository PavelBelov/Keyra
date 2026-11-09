package app.keyra.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException

internal fun Response.checked(): Response {
    if (isSuccessful) return this
    val kind = when (code) {
        401 -> StorageError.AUTH
        403 -> StorageError.PERMISSION
        404, 410 -> StorageError.NOT_FOUND
        409, 412 -> StorageError.CHANGED
        429, 500, 502, 503, 504 -> StorageError.TEMPORARY
        507 -> StorageError.QUOTA
        else -> StorageError.UNSUPPORTED
    }
    close()
    throw StorageException(kind)
}

internal inline fun <T> network(writing: Boolean = false, block: () -> T): T = try { block() }
catch (e: StorageException) { throw e }
catch (e: IOException) { throw StorageException(if (writing) StorageError.UNKNOWN_WRITE else StorageError.NETWORK, e) }

class WebDavStorage(
    endpoint: String,
    user: String,
    password: String,
    private val client: OkHttpClient
) : StorageProvider {
    private val url = endpoint.toHttpUrl().also { require(it.username.isEmpty() && it.password.isEmpty()) }
    private val authorization = Credentials.basic(user, password)
    override var conditionalWrite = false
        private set
    var verifiedMetadata: RemoteFile? = null
        private set
    private fun request() = Request.Builder().url(url).header("Authorization", authorization).header("Accept-Encoding", "identity")
    private fun descriptor(response: Response): RemoteFile {
        val etag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
        conditionalWrite = etag != null
        val allowed = response.header("Allow")?.split(',')?.map { it.trim().uppercase() }
        val modified = response.header("Last-Modified")?.let {
            runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }
        return RemoteFile(etag, writable = allowed?.contains("PUT"), modifiedAt = modified)
    }
    override fun metadata(): RemoteFile = network {
        val response = client.newCall(request().head().build()).execute()
        if (response.code == 405 || response.code == 501) {
            response.close()
            client.newCall(request().get().build()).execute().checked().use(::descriptor)
        } else response.checked().use(::descriptor)
    }
    override fun download(target: File): RemoteFile = network {
        verifiedMetadata = null
        client.newCall(request().get().build()).execute().checked().use { response ->
            val meta = descriptor(response)
            atomicWrite(target) { f -> response.body!!.byteStream().use { i -> f.outputStream().use(i::copyTo) } }
            meta.copy(sha256 = target.sha256()).also { verifiedMetadata = it }
        }
    }
    override fun write(source: File, condition: WriteCondition): RemoteFile = network(true) {
        val builder = request().put(source.asRequestBody("application/octet-stream".toMediaType()))
        when (condition) {
            is WriteCondition.Unchanged -> { require(condition.version.startsWith('"')); builder.header("If-Match", condition.version) }
            WriteCondition.UserApproved -> Unit
        }
        client.newCall(builder.build()).execute().checked().use { }
        // A successful PUT is insufficient: verify bytes before declaring synchronization complete.
        val check = File.createTempFile("verify-", ".kdbx", source.parentFile)
        try {
            val result = download(check)
            if (result.sha256 != source.sha256()) throw StorageException(StorageError.CHANGED)
            result
        } finally { check.delete() }
    }
}
