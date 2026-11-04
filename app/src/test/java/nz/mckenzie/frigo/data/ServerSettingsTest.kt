package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy of a server's settings the phone keeps, so the settings pages open
 * when it doesn't answer. It is written and read as one block, and a Frigate
 * release that adds keys must not break the read.
 */
class ServerSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val settings = ServerSettings(
        config = FrigateConfig(
            cameras = mapOf(
                "north_cam" to CameraConfig(
                    friendlyName = "North cam",
                    record = SwitchConfig(enabled = false),
                    notifications = SwitchConfig(enabled = true),
                    review = ReviewBlockConfig(
                        alerts = ReviewFlagConfig(labels = listOf("person", "car")),
                    ),
                ),
            ),
            notifications = SwitchConfig(enabled = true),
        ),
        profile = FrigateProfile(username = "matt", role = "admin"),
        readAt = 1_700_000_000_000,
    )

    @Test
    fun aCopyComesBackAsItWentIn() {
        val stored = json.encodeToString(ServerSettings.serializer(), settings)
        val read = json.decodeFromString(ServerSettings.serializer(), stored)
        assertEquals(settings, read)
    }

    /** What the pages are built from has to survive the trip, not just the shape. */
    @Test
    fun thePagesAreTheSameFromACopy() {
        val read = json.decodeFromString(
            ServerSettings.serializer(),
            json.encodeToString(ServerSettings.serializer(), settings),
        )
        val camera = read.config.cameras.getValue("north_cam")
        assertEquals("North cam", camera.friendlyName)
        assertEquals(false, camera.switches()[CameraSwitch.Recording])
        assertEquals(listOf("person", "car"), camera.review.alerts.labels)
        assertTrue(read.config.alertRules().buzzes("north_cam", "person"))
        assertEquals("admin", read.profile.role)
        assertEquals(1_700_000_000_000, read.readAt)
    }

    /** A copy written by an older version has fewer keys, and still reads. */
    @Test
    fun aCopyWithLessInItStillReads() {
        val read = json.decodeFromString(ServerSettings.serializer(), """{"readAt":42}""")
        assertEquals(42, read.readAt)
        assertTrue(read.config.cameras.isEmpty())
        assertEquals("", read.profile.role)
    }
}
