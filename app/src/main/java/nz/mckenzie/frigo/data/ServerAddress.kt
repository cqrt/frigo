package nz.mckenzie.frigo.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The addresses worth trying for what someone typed.
 *
 * A bare address is tried over http first when it looks like a box on the home
 * network, and over https first when it looks like a name out on the internet. A
 * Frigate at `192.168.0.99:5000` answers http and not https, and one behind a
 * proxy answers https and not http, so the order is the difference between a
 * stranger getting in and being told their server isn't there.
 *
 * An address with a scheme in it is taken at its word, because it is a choice.
 * [FrigateApi.probe] is what decides which of the two answers, so the guess here
 * only costs a request.
 *
 * No Android here, so the order can be tried without a phone.
 */
internal fun addressCandidates(input: String): List<String> {
    val typed = input.trim()
    if (typed.isEmpty()) return emptyList()

    val hasScheme = typed.startsWith("http://", ignoreCase = true) ||
        typed.startsWith("https://", ignoreCase = true)
    val parsed = (if (hasScheme) typed else "http://$typed").toHttpUrlOrNull()
        ?: return emptyList()
    val clean = clean(parsed.toString())

    if (hasScheme) return listOf(clean)

    // The scheme is the only thing that changes between the two, so it is put on
    // the address the parser has already tidied.
    val rest = clean.substringAfter("://")
    return if (looksLocal(parsed.host)) {
        listOf("http://$rest", "https://$rest")
    } else {
        listOf("https://$rest", "http://$rest")
    }
}

/** An address with no trailing slash and no `/api` on the end. */
private fun clean(raw: String): String {
    var value = raw.trimEnd('/')
    if (value.endsWith("/api", ignoreCase = true)) value = value.dropLast(4).trimEnd('/')
    return value
}

/**
 * Whether that host is one in the house rather than one out on the internet: a
 * private address, a name with no dot in it, or a name that is plainly local.
 */
internal fun looksLocal(host: String): Boolean {
    val name = host.lowercase().trim('[', ']')
    if (name.isEmpty()) return false
    if (name == "localhost" || name == "::1") return true
    if (name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home")) return true

    // A link-local or unique-local IPv6 address is the house as well.
    if (':' in name) {
        return name.startsWith("fe80") || name.startsWith("fc") || name.startsWith("fd")
    }

    val parts = name.split('.')
    if (parts.size != 4) return !name.contains('.')
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 }) return false
    return when (numbers[0]) {
        10, 127 -> true
        172 -> numbers[1] in 16..31
        192 -> numbers[1] == 168
        169 -> numbers[1] == 254
        else -> false
    }
}

/**
 * Whether this failure is the certificate rather than the server being away. A
 * phone refuses a certificate it has not been told to trust, and that is the
 * common case at home, so it is told apart from a timeout or a wrong address.
 */
internal fun Throwable.isCertificateProblem(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is SSLPeerUnverifiedException ||
            cause is SSLHandshakeException ||
            cause is CertificateException
        ) {
            return true
        }
        cause = cause.cause?.takeIf { it !== cause }
    }
    return false
}
