package app.keyra.core

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

data class DavEntry(val url: HttpUrl, val name: String, val directory: Boolean)
data class DavFolder(val url: HttpUrl, val entries: List<DavEntry>)

/** Read-only DAV discovery. Never send credentials to URLs supplied by another origin. */
class WebDavBrowser(private val client: OkHttpClient, user: String, password: String) {
    private val authorization = Credentials.basic(user, password)
    fun open(endpoint: String): DavFolder {
        val url = endpoint.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty())
        val initial = try { properties(url, "1") } catch (error: StorageException) {
            if (error.kind == StorageError.NOT_FOUND && url.pathSegments.lastOrNull()?.endsWith(".kdbx", true) == true)
                return open(parent(url).toString())
            throw error
        }
        val self = initial.firstOrNull { samePath(it.url, url) }
            ?: throw StorageException(StorageError.UNSUPPORTED)
        val folder = if (self.directory) self.url else parent(url)
        val entries = if (self.directory) initial else properties(folder, "1")
        return DavFolder(folder, entries.filter { entry ->
            !samePath(entry.url, folder) && samePath(parent(entry.url), folder)
        }.distinctBy { it.url }.sortedWith(compareByDescending<DavEntry> { it.directory }.thenBy { it.name.lowercase() }))
    }
    private fun properties(url: HttpUrl, depth: String, redirects: Int = 0): List<DavEntry> = network {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/></d:prop></d:propfind>"""
        val request = Request.Builder().url(url).header("Authorization", authorization).header("Depth", depth)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (response.code in listOf(301, 302, 307, 308)) {
                val target = response.header("Location")?.let(url::resolve)
                if (redirects >= 3 || target == null || target.scheme != url.scheme || target.host != url.host ||
                    target.port != url.port || target.username.isNotEmpty() || target.password.isNotEmpty())
                    throw StorageException(StorageError.UNSUPPORTED)
                response.close()
                return@network properties(target, depth, redirects + 1)
            }
            response.checked()
            if (response.code != 207) throw StorageException(StorageError.UNSUPPORTED)
            val bytes = response.body?.byteStream()?.use { it.readLimited(4 * 1024 * 1024) }
                ?: throw StorageException(StorageError.UNSUPPORTED)
            require(bytes.size <= 4 * 1024 * 1024 && !bytes.hasDoctype())
            val entries = mutableListOf<DavEntry>()
            val handler = object : DefaultHandler() {
                var href = ""; var name = ""; var collection = false; var status = ""; var successful = false
                var propName = ""; var propCollection = false
                val stack = ArrayDeque<String>(); val text = StringBuilder()
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("EXTERNAL_ENTITY")
                override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
                    require(stack.size < 64)
                    stack.addLast(if (uri == "DAV:") localName else ""); text.clear()
                    if (uri == "DAV:" && localName == "response") { href = ""; name = ""; collection = false; successful = false }
                    if (uri == "DAV:" && localName == "propstat") { status = ""; propName = ""; propCollection = false }
                    if (uri == "DAV:" && localName == "collection") propCollection = true
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String, qName: String?) {
                    if (uri == "DAV:") when (localName) {
                        "href" -> if (stack.size == 3) href = text.toString().trim()
                        "displayname" -> propName = text.toString().trim()
                        "status" -> status = text.toString().trim()
                        "propstat" -> if (status.split(' ').getOrNull(1)?.toIntOrNull()?.let { it in 200..299 } == true) {
                            successful = true; name = propName.ifBlank { name }; collection = collection || propCollection
                        }
                        "response" -> {
                            val target = url.resolve(href)?.newBuilder()?.fragment(null)?.build()
                            if (href.isNotBlank() && target != null && target.scheme == url.scheme && target.host == url.host &&
                                target.port == url.port && target.username.isEmpty() && target.password.isEmpty() && successful) {
                                val normalized = if (collection && !target.encodedPath.endsWith('/')) target.newBuilder().addPathSegment("").build() else target
                                entries += DavEntry(normalized, name.ifBlank { target.pathSegments.lastOrNull { it.isNotEmpty() } ?: "/" }, collection)
                            }
                        }
                    }
                    stack.removeLast(); text.clear()
                }
            }
            val parser = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newSAXParser().xmlReader
            parser.contentHandler = handler; parser.entityResolver = handler
            parser.parse(InputSource(bytes.inputStream()))
            entries
        }
    }
    companion object {
        private fun samePath(a: HttpUrl, b: HttpUrl) = a.encodedPath.trimEnd('/') == b.encodedPath.trimEnd('/')
        fun parent(url: HttpUrl): HttpUrl {
            val path = url.encodedPath.trimEnd('/').substringBeforeLast('/', "") + "/"
            return url.newBuilder().encodedPath(path).query(null).fragment(null).build()
        }
    }
}
