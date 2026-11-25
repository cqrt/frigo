package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Home Assistant says about the lights in the house, read into the handful of
 * things a screen needs: which light belongs to which camera, and what it is called.
 *
 * None of this touches the network, so the answers can be tried without a box on the
 * other end of one.
 */
class HomeAssistantTest {

    private val driveFloodlight = Light("light.drive_floodlight", "Drive Floodlight")
    private val shedFloodlight = Light("light.shed_floodlight", "Shed Floodlight")
    private val porch = Light("light.porch_lamp", "Porch lamp")

    /** Lights and switches are offered: a sensor is nothing this app turns on. */
    @Test
    fun theStatesAnswerIsNarrowedToLightsAndSwitchesNamedTheWayTheBoxNamesThem() {
        val states = """
            [
              {"entity_id": "light.porch_lamp", "state": "off",
               "attributes": {"friendly_name": "Porch lamp"}},
              {"entity_id": "switch.drive_floodlight", "state": "on", "attributes": {}},
              {"entity_id": "light.drive_floodlight", "state": "on",
               "attributes": {"friendly_name": "Drive Floodlight"}},
              {"entity_id": "person.matt", "state": "home", "attributes": {}}
            ]
        """.trimIndent()

        assertEquals(
            listOf(porch, Light("switch.drive_floodlight", "Drive floodlight"), driveFloodlight),
            lightsIn(states),
        )
    }

    /** A light the box gave no name for is called after its id, which is readable enough. */
    @Test
    fun aLightWithNoFriendlyNameFallsBackToItsId() {
        val states = """[{"entity_id": "light.shed_floodlight", "state": "off"}]"""

        assertEquals(listOf(Light("light.shed_floodlight", "Shed floodlight")), lightsIn(states))
    }

    /** An answer that isn't a list of states is nothing rather than a crash. */
    @Test
    fun anAnswerThatIsNotWhatWasAskedForIsNoLights() {
        assertTrue(lightsIn("{\"message\": \"API running.\"}").isEmpty())
        assertTrue(lightsIn("").isEmpty())
    }

    /** `light.door_cam_floodlight` reads as "Door cam floodlight". */
    @Test
    fun aNameIsMadeOutOfTheId() {
        assertEquals("Door cam floodlight", lightName("light.door_cam_floodlight"))
        assertEquals("Porch", lightName("light.porch"))
    }

    /**
     * The light named after the camera comes first, because that is the one Tapo
     * Cameras Control made for it, and the list otherwise starts at the top of the
     * alphabet every time.
     */
    @Test
    fun theCameraOwnLightComesFirstAndTheRestFollowByName() {
        val lights = listOf(porch, shedFloodlight, driveFloodlight)

        assertEquals(
            listOf(shedFloodlight, driveFloodlight, porch),
            lightsForCamera(lights, camera = "shed", title = "Shed"),
        )
    }

    /** A camera whose light is named the phone's way for it is found just the same. */
    @Test
    fun thePhonesOwnNameForTheCameraFindsTheLight() {
        val gate = Light("light.gate", "Front gate floodlight")
        val lights = listOf(porch, gate)

        assertEquals(
            listOf(gate, porch),
            lightsForCamera(lights, camera = "cam1", title = "Front gate"),
        )
    }

    /** No light named after the camera is a list by name, not a list with nothing in it. */
    @Test
    fun aCameraWithNoLightOfItsOwnGetsThemAllByName() {
        val lights = listOf(porch, shedFloodlight)

        assertEquals(
            listOf(porch, shedFloodlight),
            lightsForCamera(lights, camera = "cam1", title = "Front gate"),
        )
    }

    /** What people type is tidied, and a Home Assistant is a box at home, so http. */
    @Test
    fun theAddressIsTidied() {
        assertEquals("http://192.168.1.20:8123", homeAssistantAddress(" 192.168.1.20:8123 "))
        assertEquals("http://192.168.1.20:8123", homeAssistantAddress("http://192.168.1.20:8123/"))
        assertEquals("https://ha.example.nz", homeAssistantAddress("https://ha.example.nz/api"))
        assertEquals("http://homeassistant.local:8123", homeAssistantAddress("homeassistant.local:8123/"))
    }

    /** Nothing that isn't an address is quietly turned into one. */
    @Test
    fun anAddressThatIsNotOneIsRefused() {
        assertNull(homeAssistantAddress("   "))
        assertNull(homeAssistantAddress("not an address"))
    }
}
