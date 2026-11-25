package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Which address is tried first for what someone typed.
 *
 * A Frigate at home answers http and not https, and one behind a proxy answers
 * https and not http, so the order is the difference between a stranger getting in
 * and being told their server isn't there.
 */
class ServerAddressTest {

    @Test
    fun aPrivateAddressIsTriedOverHttpFirst() {
        assertEquals(
            listOf("http://192.168.0.99:5000", "https://192.168.0.99:5000"),
            addressCandidates("192.168.0.99:5000"),
        )
    }

    @Test
    fun aNameOutOnTheInternetIsTriedOverHttpsFirst() {
        assertEquals(
            listOf("https://frigate.example.com", "http://frigate.example.com"),
            addressCandidates("frigate.example.com"),
        )
    }

    @Test
    fun anAddressWithASchemeIsTakenAtItsWord() {
        assertEquals(
            listOf("https://192.168.0.99:8971"),
            addressCandidates("https://192.168.0.99:8971/"),
        )
    }

    @Test
    fun aTrailingApiComesOff() {
        assertEquals(
            listOf("http://frigate.local:5000", "https://frigate.local:5000"),
            addressCandidates("frigate.local:5000/api"),
        )
    }

    @Test
    fun nothingTypedIsNoAddressAtAll() {
        assertTrue(addressCandidates("  ").isEmpty())
        assertTrue(addressCandidates("not an address").isEmpty())
    }

    @Test
    fun theHouseIsRecognisedByTheAddressItHas() {
        assertTrue(looksLocal("192.168.0.99"))
        assertTrue(looksLocal("10.1.2.3"))
        assertTrue(looksLocal("172.16.4.2"))
        assertTrue(looksLocal("localhost"))
        assertTrue(looksLocal("frigate.local"))
        assertTrue(looksLocal("nas"))
        assertFalse(looksLocal("frigate.example.com"))
        assertFalse(looksLocal("172.32.0.1"))
        assertFalse(looksLocal("8.8.8.8"))
    }

    /** The certificate is told apart from a server that is simply away. */
    @Test
    fun aCertificateIsToldApartFromANetworkThatFailed() {
        assertTrue(SSLHandshakeException("bad").isCertificateProblem())
        assertTrue(SSLPeerUnverifiedException("no name").isCertificateProblem())
        assertTrue(
            RuntimeException("wrapped", SSLHandshakeException("bad").apply { initCause(CertificateException("no")) })
                .isCertificateProblem(),
        )
        assertFalse(java.net.UnknownHostException("nothing there").isCertificateProblem())
        assertFalse(java.io.IOException("timed out").isCertificateProblem())
    }
}
