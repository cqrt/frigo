package nz.mckenzie.frigo.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Signing in to a server whose sign-in is turned off.
 *
 * Frigate with authentication disabled answers `/api/config` to anyone, and turns
 * a login down. The app has to hear that, keep the server with no account, and go
 * on sending no `Authorization` header at all: a token it never issued is refused,
 * and every screen would be empty behind it.
 */
@RunWith(RobolectricTestRunner::class)
class SignInTest {

    private val context = RuntimeEnvironment.getApplication()
    private val box = MockWebServer()

    private val address get() = box.url("/").toString().trimEnd('/')

    private fun repository(): FrigoRepository {
        val client = OkHttpClient()
        return FrigoRepository(
            context = context,
            api = FrigateApi(context, client),
            homeAssistantApi = HomeAssistantApi(context, client),
            prefs = ServerPrefs(context),
            trust = TrustedCertificates(context),
            cacheDir = context.cacheDir,
        )
    }

    @Before
    fun start() {
        box.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/version") == true -> ok("""{"version":"0.18.0"}""")

                request.path?.startsWith("/api/login") == true ->
                    MockResponse().setResponseCode(401).setBody("""{"message":"Login failed"}""")

                request.path?.startsWith("/api/config") == true -> ok(
                    """{"cameras":{"north_cam":{"enabled":true,"friendly_name":"North cam"}}}""",
                )

                request.path?.startsWith("/api/profile") == true -> ok(
                    """{"username":"anonymous","role":"admin"}""",
                )

                else -> MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")
            }
        }
        box.start()
    }

    @After
    fun stop() = box.shutdown()

    private fun ok(body: String) = MockResponse().setBody(body).setHeader("Content-Type", "application/json")

    @Test
    fun aServerWithNoSignInSignsInWithoutAnAccount() = runBlocking {
        val repository = repository()

        repository.signIn(address, "", "")

        val gate = repository.gate.value
        assertTrue(gate is Gate.SignedIn)
        assertEquals("", (gate as Gate.SignedIn).username)

        val saved = repository.servers.value.first { it.baseUrl == address }
        assertEquals("", saved.username)
        assertNull(saved.token)
        assertNull(saved.password)
        assertEquals(false, saved.keepPassword)
    }

    @Test
    fun aServerWithNoSignInIsSentNoAuthorizationHeader() = runBlocking {
        val repository = repository()
        repository.signIn(address, "", "")

        // Drain what signing in asked for, so the next request is the one under test.
        while (box.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
            // One request at a time until the queue is quiet.
        }

        repository.cameras()

        val sent = box.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(sent?.getHeader("Authorization"))
    }

    @Test
    fun aSignInThatIsTurnedDownOnAnOpenServerStillSignsIn() = runBlocking {
        // Someone typed a username and password at a server that has sign-in off.
        val repository = repository()

        repository.signIn(address, "test", "wrong")

        assertEquals("", repository.servers.value.first { it.baseUrl == address }.username)
    }

    /** A server that turns the login down and wants one says so, rather than signing in. */
    @Test
    fun aTurnedDownSignInOnAClosedServerSaysSo() = runBlocking {
        box.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/version") == true -> ok("""{"version":"0.18.0"}""")
                request.path?.startsWith("/api/login") == true ->
                    MockResponse().setResponseCode(401).setBody("""{"message":"Login failed"}""")
                else -> MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}""")
            }
        }

        val failure = runCatching {
            repository().signIn(address, "test", "wrong")
        }.exceptionOrNull()

        assertTrue(failure is FrigateException)
        assertEquals(401, (failure as FrigateException).status)
    }
}
