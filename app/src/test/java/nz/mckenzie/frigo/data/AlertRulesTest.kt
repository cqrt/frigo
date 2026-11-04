package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the server has to agree to before a camera's sighting buzzes the phone.
 *
 * The answers are all on the server: the camera's own notifications switch, and
 * whether its alerts are on with this object in their list. Turning either one
 * off silences the phone. The switch at the top of the server's notifications
 * page is a batch over the cameras, so it is not asked about here.
 */
class AlertRulesTest {

    private val config = Json { ignoreUnknownKeys = true }

    /**
     * A server with its notifications on, one camera that tells you about people,
     * and one camera nobody has touched.
     */
    private val rules = config.decodeFromString(
        FrigateConfig.serializer(),
        """
        {
          "notifications": { "enabled": true },
          "review": { "alerts": { "enabled": true, "labels": ["person", "car"] } },
          "cameras": {
            "drive": {
              "notifications": { "enabled": true },
              "review": { "alerts": { "enabled": true } }
            },
            "back": {
              "notifications": { "enabled": true },
              "review": { "alerts": { "enabled": true, "labels": ["dog"] } }
            },
            "shed": {}
          }
        }
        """.trimIndent(),
    ).alertRules()

    @Test
    fun aCameraTellingYouAboutAPersonBuzzes() {
        assertTrue(rules.buzzes("drive", "person"))
    }

    /** Its alerts carry no list of its own, so it follows the server-wide one. */
    @Test
    fun aCameraFollowingTheSharedListBuzzesForWhatIsOnIt() {
        assertTrue(rules.buzzes("drive", "car"))
        assertFalse(rules.buzzes("drive", "dog"))
    }

    /** A camera nobody has touched is off, so nothing on it buzzes. */
    @Test
    fun aCameraWithNoNotificationsOffBuzzesForNothing() {
        assertFalse(rules.buzzes("shed", "person"))
    }

    /** A camera with a list of its own follows that, not the shared one. */
    @Test
    fun aCameraWithItsOwnListBuzzesForThatListOnly() {
        assertTrue(rules.buzzes("back", "dog"))
        assertFalse(rules.buzzes("back", "person"))
    }

    /**
     * The server's own switch is a batch over the cameras. Leave it off, turn the
     * cameras on one at a time, and that camera still buzzes.
     */
    @Test
    fun aCameraOnWithTheServersOwnSwitchOffStillBuzzes() {
        val batchOff = config.decodeFromString(
            FrigateConfig.serializer(),
            """
            {
              "notifications": { "enabled": false },
              "cameras": { "drive": { "notifications": { "enabled": true } } }
            }
            """.trimIndent(),
        ).alertRules()

        assertTrue(batchOff.buzzes("drive", "person"))
    }

    @Test
    fun aCameraWithAlertsOffBuzzesForNothing() {
        val off = rules.copy(
            cameras = rules.cameras.mapValues { it.value.copy(alertsOn = false) },
        )
        assertFalse(off.buzzes("drive", "person"))
    }

    @Test
    fun anObjectThatMakesAnAlertOnOneCameraOnlyIsQuietOnTheOther() {
        assertTrue(rules.buzzes("back", "dog"))
        assertFalse(rules.buzzes("drive", "dog"))
    }

    /** An object the server doesn't name at all is never an alert. */
    @Test
    fun anObjectNobodyListedDoesNotBuzz() {
        assertFalse(rules.buzzes("drive", "bark"))
    }

    /**
     * The alert labels Frigate starts every server with, when a camera and the
     * server-wide settings both leave the list out.
     */
    @Test
    fun aServerThatNeverNamedItsAlertsStartsWithPeopleAndCars() {
        val plain = config.decodeFromString(
            FrigateConfig.serializer(),
            """
            {
              "notifications": { "enabled": true },
              "cameras": { "drive": { "notifications": { "enabled": true } } }
            }
            """.trimIndent(),
        ).alertRules()

        assertTrue(plain.buzzes("drive", "person"))
        assertTrue(plain.buzzes("drive", "car"))
    }

    /** Every camera gets an entry, so the quiet notification can count them. */
    @Test
    fun everyCameraOnTheServerIsInTheRules() {
        assertEquals(setOf("drive", "back", "shed"), rules.cameras.keys)
    }
}
