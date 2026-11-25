package nz.mckenzie.frigo.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificates this phone was told to trust, and the one place that decides
 * what a request will accept.
 *
 * Frigate at home makes its own certificate, and a phone refuses those. Trusting
 * one is a thing the owner does once, for that one server: the certificate's
 * fingerprint is written down, and a request is let through only while the server
 * still presents that same certificate. Frigate is upgraded by replacing the
 * container, which can make a new certificate, so a changed one is refused rather
 * than accepted quietly: the sign-in page then asks again.
 *
 * The fingerprints are kept here rather than with a server, because the trust
 * manager that OkHttp asks has no idea which server a connection is for. A
 * fingerprint belongs to one certificate and one server, so matching the
 * certificate is matching that server.
 *
 * The store is plain preferences, read as the app starts: a request can be made
 * before anything else has run, and the answer has to be there already.
 */
class TrustedCertificates(context: Context) {

    private val store = context.getSharedPreferences("frigo_trust", Context.MODE_PRIVATE)

    /** Address to fingerprint, in memory, so a request costs no disk read. */
    private val pins = ConcurrentHashMap<String, String>()

    private val defaultTrust: X509TrustManager = run {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    init {
        store.all.forEach { (address, fingerprint) ->
            (fingerprint as? String)?.takeIf { it.isNotBlank() }?.let { pins[address] = it }
        }
    }

    /** The fingerprint trusted for that address, or null when it is not trusted. */
    fun fingerprintFor(address: String): String? = pins[cleanAddress(address)]

    /** Remembers that this address holds that certificate, from now on. */
    fun remember(address: String, fingerprint: String) {
        val where = cleanAddress(address)
        if (where.isBlank() || fingerprint.isBlank()) return
        pins[where] = fingerprint
        store.edit().putString(where, fingerprint).apply()
    }

    /** Forgets an address, which is what signing out of it does. */
    fun forget(address: String) {
        val where = cleanAddress(address)
        pins.remove(where)
        store.edit().remove(where).apply()
    }

    /**
     * Lets this client trust the certificates written down here, and nothing else.
     * A server with a certificate a phone already trusts never meets the question.
     */
    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val manager = PinnedTrustManager(defaultTrust, pins)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }
        return builder
            .sslSocketFactory(context.socketFactory, manager)
            .hostnameVerifier(PinnedHostnameVerifier(pins))
    }

    /**
     * Asks an address which certificate it holds, without sending anything worth
     * reading. This is what the sign-in page offers to trust: the certificate a
     * server presents can't be read off a refused handshake, so it is asked for
     * once on purpose.
     */
    fun inspect(address: String): String? {
        val captor = CapturingTrustManager()
        val client = runCatching {
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(captor), SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, captor)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }.getOrNull() ?: return null

        return runCatching {
            val request = Request.Builder()
                .url("${address.trimEnd('/')}/api/version")
                .get()
                .build()
            client.newCall(request).execute().close()
            captor.seen?.firstOrNull()?.let(::fingerprintOf)
        }.getOrNull()
    }

    private fun cleanAddress(address: String): String = address.trim().trimEnd('/')

    private companion object {
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}

/**
 * The phone's own trust first, then the certificates written down here. A failure
 * of both is passed on as it was, so a screen can say the certificate isn't trusted.
 */
private class PinnedTrustManager(
    private val fallback: X509TrustManager,
    private val pins: Map<String, String>,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        fallback.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            fallback.checkServerTrusted(chain, authType)
        } catch (refused: CertificateException) {
            if (!isPinned(chain, pins)) throw refused
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = fallback.acceptedIssuers
}

/**
 * A certificate trusted here may carry a name that doesn't match the address it is
 * served from, which is normal for one a box made for itself. That is let through
 * only for a certificate on the list; every other name is checked as usual.
 */
private class PinnedHostnameVerifier(
    private val pins: Map<String, String>,
) : HostnameVerifier {

    private val standard = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (standard.verify(hostname, session)) return true
        val chain = runCatching {
            session.peerCertificates.filterIsInstance<X509Certificate>()
        }.getOrDefault(emptyList())
        return isPinned(chain, pins)
    }
}

/** Whether one of these certificates is on the list this phone was told to trust. */
private fun isPinned(chain: List<X509Certificate>?, pins: Map<String, String>): Boolean {
    val leaf = chain?.firstOrNull() ?: return false
    val said = MessageDigest.getInstance("SHA-256")
        .digest(leaf.encoded)
        .joinToString(":") { "%02X".format(it) }
    return pins.containsValue(said)
}

private fun isPinned(chain: Array<X509Certificate>, pins: Map<String, String>): Boolean =
    isPinned(chain.toList(), pins)

/** Takes the chain a server presents and lets the request through, to read it. */
private class CapturingTrustManager : X509TrustManager {

    @Volatile
    var seen: Array<X509Certificate>? = null

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        seen = chain
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
