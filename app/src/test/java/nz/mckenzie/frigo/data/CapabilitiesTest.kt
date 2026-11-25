package nz.mckenzie.frigo.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What a server can do, and whether it is too old to promise all of Frigo.
 *
 * A 0.14 server has no `/api/review`, so Review and the alerts both stand on
 * nothing there. A 404 is the answer that says so, and any other answer, including
 * a refusal, means the route is there.
 */
@RunWith(RobolectricTestRunner::class)
class CapabilitiesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val box = MockWebServer()

    private val address get() = box.url("/").toString().trimEnd('/')

    @Before
    fun start() = box.start()

    @After
    fun stop() = box.shutdown()

    private fun ok(body: String) = MockResponse().setBody(body)

    private fun api() = FrigateApi(context, OkHttpClient())

    @Test
    fun anOldServerIsOlderThanFrigoSupports() {
        assertTrue(ServerCapabilities(version = "0.14.1").olderThanFrigo)
        assertTrue(ServerCapabilities(version = "0.15.2-foo").olderThanFrigo)
        assertFalse(ServerCapabilities(version = "0.16.0").olderThanFrigo)
        assertFalse(ServerCapabilities(version = "0.18.1").olderThanFrigo)
        // A server that wouldn't say its version is not called old on a guess.
        assertFalse(ServerCapabilities(version = "").olderThanFrigo)
    }

    @Test
    fun aServerWithNoReviewHasNoneOfIt() {
        box.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/stats") == true ->
                    ok("""{"service":{"version":"0.14.1"}}""")

                request.path?.startsWith("/api/review") == true ->
                    MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")

                request.path?.startsWith("/api/labels") == true ->
                    MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")

                else -> ok("{}")
            }
        }

        val found = api().capabilities(address, token = "")

        assertEquals("0.14.1", found.version)
        assertFalse(found.reviews)
        assertFalse(found.labels)
        assertTrue(found.olderThanFrigo)
        assertTrue(found.limited)
    }

    /** A refusal is not a missing endpoint: the route is there, and it stays. */
    @Test
    fun aRefusedEndpointStillCountsAsThere() {
        box.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/stats") == true ->
                    ok("""{"service":{"version":"0.18.2"}}""")

                request.path?.startsWith("/api/review") == true ->
                    MockResponse().setResponseCode(401).setBody("""{"message":"unauthorized"}""")

                else -> MockResponse().setResponseCode(200).setBody("[]")
            }
        }

        val found = api().capabilities(address, token = "abc")

        assertTrue(found.reviews)
        assertFalse(found.olderThanFrigo)
        assertFalse(found.limited)
    }
}
