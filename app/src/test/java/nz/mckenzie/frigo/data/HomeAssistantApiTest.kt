package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * What the app puts on the wire when it asks Home Assistant about a light.
 *
 * A stand-in box answers on the loopback address, so the path, the header and the
 * body are read back exactly as Home Assistant would read them. That is worth a
 * test of its own: the REST endpoint takes the action's own data, and anything
 * shaped differently is turned down with a 400 that says little, so the light
 * simply never comes on.
 */
@RunWith(RobolectricTestRunner::class)
class HomeAssistantApiTest {

    private val context = RuntimeEnvironment.getApplication()

    /** The stand-in box, one answer per request, in the order the app makes them. */
    private val box = MockWebServer()

    private val address get() = box.url("/").toString().trimEnd('/')

    private fun api() = HomeAssistantApi(context, OkHttpClient())

    @Before
    fun start() = box.start()

    @After
    fun stop() = box.shutdown()

    /**
     * The action's data is one object naming the entity.
     *
     * A list of them is how this used to send it, and Home Assistant answered that
     * with a 400, so the light never came on and the picture said the box didn't
     * answer.
     */
    @Test
    fun turningALightOnSendsOneObjectNamingIt() {
        box.enqueue(MockResponse().setBody("[]"))

        api().turn(address, token = "abc", entity = "light.drive_floodlight", on = true)

        val sent = box.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/services/light/turn_on", sent.path)
        assertEquals("Bearer abc", sent.getHeader("Authorization"))
        assertEquals("light.drive_floodlight", entityIn(sent.body.readUtf8()))
    }

    /** Off goes to the other action, with the same body. */
    @Test
    fun turningALightOffGoesToTurnOff() {
        box.enqueue(MockResponse().setBody("[]"))

        api().turn(address, token = "abc", entity = "light.drive_floodlight", on = false)

        val sent = box.takeRequest()
        assertEquals("/api/services/light/turn_off", sent.path)
        assertEquals("light.drive_floodlight", entityIn(sent.body.readUtf8()))
    }

    /** The address is asked who it is with the token as a bearer token. */
    @Test
    fun theAddressIsCheckedWithTheToken() {
        box.enqueue(MockResponse().setBody("""{"message": "API running."}"""))

        api().check(address, token = "abc")

        val sent = box.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/api/", sent.path)
        assertEquals("Bearer abc", sent.getHeader("Authorization"))
    }

    /** A box that has no such light is not a box that says the light is off. */
    @Test
    fun anUnknownLightReadsAsNoAnswer() {
        box.enqueue(MockResponse().setResponseCode(404).setBody("""{"message": "Entity not found."}"""))
        assertNull(api().isOn(address, "abc", "light.gone"))

        box.enqueue(MockResponse().setBody("""{"entity_id": "light.on", "state": "on"}"""))
        assertEquals(true, api().isOn(address, "abc", "light.on"))

        box.enqueue(MockResponse().setBody("""{"entity_id": "light.off", "state": "off"}"""))
        assertEquals(false, api().isOn(address, "abc", "light.off"))
    }

    /** The whole house's states are read for the lights in them, named by the box. */
    @Test
    fun theLightsComeOutOfTheStatesAnswer() {
        box.enqueue(
            MockResponse().setBody(
                """
                [
                  {"entity_id": "light.door_floodlight", "state": "on",
                   "attributes": {"friendly_name": "Door Floodlight"}},
                  {"entity_id": "sensor.temperature", "state": "18.2", "attributes": {}}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(Light("light.door_floodlight", "Door Floodlight")),
            api().lights(address, token = "abc"),
        )
    }

    /** A refused token is said as a refused token, not as a box that is away. */
    @Test
    fun aRefusedTokenSaysSo() {
        box.enqueue(MockResponse().setResponseCode(401).setBody("""{"message": "Unauthorized"}"""))

        val failure = runCatching { api().lights(address, token = "abc") }.exceptionOrNull()

        assertEquals(
            context.words(nz.mckenzie.frigo.R.string.ha_token_wrong),
            failure?.message,
        )
    }

    /**
     * A call Home Assistant turns down is shown in its own words, which name the
     * reason. A sentence of the app's own would be a guess at it.
     */
    @Test
    fun aRefusedCallSaysWhateverTheBoxSaid() {
        val said = "Failed to call service light/turn_on. Entity not found."
        box.enqueue(MockResponse().setResponseCode(400).setBody("""{"message": "$said"}"""))

        val failure = runCatching {
            api().turn(address, "abc", "light.gone", on = true)
        }.exceptionOrNull()

        assertEquals(said, failure?.message)
    }

    /** The entity id in an action body, failing on a body that isn't one object. */
    private fun entityIn(body: String): String? {
        val parsed = Json.parseToJsonElement(body)
        assertTrue("not a JSON object: $body", parsed is JsonObject)
        return (parsed as JsonObject)["entity_id"]?.jsonPrimitive?.content
    }
}
