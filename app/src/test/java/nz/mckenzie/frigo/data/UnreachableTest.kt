package nz.mckenzie.frigo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Which failures mean the server is away, and which mean it answered.
 *
 * Only the first kind hands the settings pages the copy on the phone. A session
 * that has run out and a certificate the phone won't take both have their own
 * answer on screen, so they must not be read as "away".
 */
class UnreachableTest {

    @Test
    fun aPhoneWithNoNetworkIsAway() {
        assertTrue(IOException("no route").isUnreachable())
    }

    @Test
    fun aNameThatDoesNotResolveIsAway() {
        assertTrue(UnknownHostException("frigate.example.com").isUnreachable())
    }

    @Test
    fun aServerThatNeverAnswersIsAway() {
        assertTrue(SocketTimeoutException("timeout").isUnreachable())
        assertTrue(ConnectException("refused").isUnreachable())
    }

    @Test
    fun aProxySayingTheBoxBehindItIsOutIsAway() {
        assertTrue(FrigateException(502, "Bad gateway").isUnreachable())
        assertTrue(FrigateException(503, "Not available").isUnreachable())
        assertTrue(FrigateException(504, "Timed out").isUnreachable())
    }

    @Test
    fun aSessionThatRanOutIsNotAway() {
        assertFalse(FrigateException(401, "Unauthorized").isUnreachable())
        assertFalse(FrigateException(403, "Forbidden").isUnreachable())
    }

    /** The server answered, it just didn't like the request. */
    @Test
    fun anotherAnswerFromTheServerIsNotAway() {
        assertFalse(FrigateException(400, "Bad request").isUnreachable())
        assertFalse(FrigateException(500, "Server error").isUnreachable())
    }

    /** A certificate is a wrong address, not a server that is out. Both are IOExceptions. */
    @Test
    fun aCertificateThePhoneWontTakeIsNotAway() {
        assertFalse(SSLHandshakeException("untrusted").isUnreachable())
    }

    @Test
    fun anythingElseIsNotAway() {
        assertFalse(IllegalStateException("something else").isUnreachable())
    }
}
