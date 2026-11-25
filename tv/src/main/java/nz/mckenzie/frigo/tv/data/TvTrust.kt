package nz.mckenzie.frigo.tv.data

import android.content.Context
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The certificates this television was told to trust.
 *
 * A television has no keyboard and a remote for a pointer, so asking it to approve
 * a certificate is a poor time. The phone has already trusted that server's
 * certificate by the time it pairs, and hands the fingerprint over with the
 * address. This keeps it, and a request is let through only while the server still
 * presents that same certificate.
 *
 * The fingerprint lives here rather than with the server for the same reason it
 * does on the phone: the trust manager OkHttp asks has no idea which server a
 * connection is for, and a certificate belongs to one server.
 */
class TvTrust(context: Context) {

    private val store = context.getSharedPreferences("frigo_tv_trust", Context.MODE_PRIVATE)
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

    fun remember(address: String, fingerprint: String) {
        val where = address.trim().trimEnd('/')
        if (where.isBlank() || fingerprint.isBlank()) return
        pins[where] = fingerprint
        store.edit().putString(where, fingerprint).apply()
    }

    fun forget(address: String) {
        val where = address.trim().trimEnd('/')
        pins.remove(where)
        store.edit().remove(where).apply()
    }

    fun apply(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val manager = PinnedTrustManager(defaultTrust, pins)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }
        return builder
            .sslSocketFactory(context.socketFactory, manager)
            .hostnameVerifier(PinnedHostnameVerifier(pins))
    }
}

/** The platform's own trust first, then the certificates written down here. */
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
            if (!isPinned(chain.toList(), pins)) throw refused
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = fallback.acceptedIssuers
}

/** A certificate on the list may carry a name that doesn't match the address. */
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

/** Whether one of these certificates is the one this television was told to trust. */
private fun isPinned(chain: List<X509Certificate>?, pins: Map<String, String>): Boolean {
    val leaf = chain?.firstOrNull() ?: return false
    val said = MessageDigest.getInstance("SHA-256")
        .digest(leaf.encoded)
        .joinToString(":") { "%02X".format(it) }
    return pins.containsValue(said)
}
