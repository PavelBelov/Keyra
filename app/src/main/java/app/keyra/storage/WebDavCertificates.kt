package app.keyra.storage

import app.keyra.SecureSecrets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class WebDavCertificateApprovalRequired : CertificateException("WebDAV certificate approval required")

data class WebDavCertificateChallenge(val origin: String, val certificate: X509Certificate, val replacing: Boolean) {
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it.toInt() and 255) }
    val identity: String get() = "$origin|$fingerprint"
}

data class WebDavCertificateInfo(val challenge: WebDavCertificateChallenge, val trusted: Boolean, val accepted: Boolean)

/** Explicit trust is scoped to one HTTPS host and port and one exact server certificate. */
class WebDavCertificates(private val secrets: SecureSecrets) {
    private val mutableInfo = MutableStateFlow<Map<String, WebDavCertificateInfo>>(emptyMap())
    val information = mutableInfo.asStateFlow()
    fun infoFor(endpoint: String): WebDavCertificateInfo? = runCatching { information.value[origin(endpoint)] }.getOrNull()
    @Synchronized private fun record(info: WebDavCertificateInfo) { mutableInfo.value = mutableInfo.value + (info.challenge.origin to info) }
    private val mutablePending = MutableStateFlow<WebDavCertificateChallenge?>(null)
    val pending = mutablePending.asStateFlow()
    fun pendingFor(endpoint: String): WebDavCertificateChallenge? = runCatching {
        mutablePending.value?.takeIf { it.origin == origin(endpoint) }
    }.getOrNull()
    private fun origin(endpoint: String) = endpoint.toHttpUrl().newBuilder().username("").password("")
        .encodedPath("/").query(null).fragment(null).build().toString()
    private fun name(origin: String) = "webdav-certificate-" + MessageDigest.getInstance("SHA-256")
        .digest(origin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun accept(challenge: WebDavCertificateChallenge) {
        challenge.certificate.checkValidity()
        secrets.putTransport(name(challenge.origin), challenge.certificate.encoded)
        record(WebDavCertificateInfo(challenge, trusted = false, accepted = true))
        if (mutablePending.value?.identity == challenge.identity) mutablePending.value = null
    }

    fun client(base: OkHttpClient, endpoint: String): OkHttpClient {
        val url = endpoint.toHttpUrl()
        if (!url.isHttps) return base
        val origin = origin(endpoint)
        val pinned = if (secrets.has(name(origin))) secrets.getTransport(name(origin)) else null
        val pinnedCertificate = pinned?.let {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate
        }
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + listOfNotNull(pinnedCertificate)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Empty server certificate chain")
                // Expired certificates are never accepted via this exception mechanism.
                try { leaf.checkValidity() } catch (error: CertificateException) {
                    record(WebDavCertificateInfo(WebDavCertificateChallenge(origin, leaf, pinned != null), trusted = false, accepted = false))
                    throw error
                }
                if (pinned != null) {
                    if (MessageDigest.isEqual(pinned, leaf.encoded)) return
                } else {
                    try { system.checkServerTrusted(chain, authType); return }
                    catch (_: CertificateException) { /* Require explicit approval below. */ }
                }
                val challenge = WebDavCertificateChallenge(origin, leaf, pinned != null)
                record(WebDavCertificateInfo(challenge, trusted = false, accepted = false))
                mutablePending.value = challenge
                throw WebDavCertificateApprovalRequired()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        // Keep OkHttp's standard hostname verifier. No redirects or trust-all fallback.
        return base.newBuilder().sslSocketFactory(tls.socketFactory, trust).addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val leaf = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
            if (leaf != null) record(WebDavCertificateInfo(WebDavCertificateChallenge(origin, leaf, false),
                trusted = pinned == null, accepted = pinned != null))
            response
        }.build()
    }
}
