package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The switches page: what it reads and what it sends.
 *
 * Each switch is one setting in the file Frigate keeps its own settings in, so a
 * wrong key leaves a camera following the server's default while the page shows
 * it as changed.
 */
@RunWith(RobolectricTestRunner::class)
class CameraSwitchTest {

    /** The sentence about a switch the server holds off comes from the app's resources. */
    private val context = RuntimeEnvironment.getApplication()

    /** The server's file has far more in it than this app reads, so extra keys are ignored. */
    private val config = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): FrigateConfig =
        config.decodeFromString(FrigateConfig.serializer(), text)

    @Test
    fun aCameraReadsBackItsEightSwitches() {
        val parsed = parse(
            """
            {
              "cameras": {
                "3d_printer": {
                  "detect": {"enabled": false, "width": 640},
                  "motion": {"enabled": true, "threshold": 30},
                  "record": {"enabled": false},
                  "snapshots": {"enabled": true},
                  "audio": {"enabled": false, "listen": ["scream"]},
                  "birdseye": {"enabled": true},
                  "face_recognition": {"enabled": true},
                  "lpr": {"enabled": false}
                }
              }
            }
            """,
        )

        val switches = parsed.cameras.getValue("3d_printer").switches()

        assertEquals(false, switches[CameraSwitch.ObjectDetection])
        assertEquals(true, switches[CameraSwitch.MotionDetection])
        assertEquals(false, switches[CameraSwitch.Recording])
        assertEquals(true, switches[CameraSwitch.Snapshots])
        assertEquals(false, switches[CameraSwitch.AudioDetection])
        assertEquals(true, switches[CameraSwitch.Birdseye])
        assertEquals(true, switches[CameraSwitch.FaceRecognition])
        assertEquals(false, switches[CameraSwitch.LicensePlate])
    }

    /** A camera that says nothing about a feature has it on, which is where Frigate starts. */
    @Test
    fun aCameraThatSaysNothingHasEverythingOn() {
        val switches = parse("""{"cameras": {"north_cam": {}}}""")
            .cameras.getValue("north_cam").switches()

        CameraSwitch.entries.forEach { assertEquals(true, switches[it]) }
    }

    /** One switch is sent on its own, so the rest of the camera and the other cameras stay put. */
    @Test
    fun turningOneSwitchOffSendsThatSwitchAlone() {
        val change = cameraSwitchChange("north_cam", CameraSwitch.Recording, false)

        assertEquals("config/cameras/north_cam/record", change.updateTopic)
        assertEquals(
            """{"cameras":{"north_cam":{"record":{"enabled":false}}}}""",
            change.configData.toString(),
        )
    }

    /** The topic names the same part as the change, because that is how Frigate finds it. */
    @Test
    fun eachSwitchNamesItsOwnPartOfTheCamera() {
        val expected = mapOf(
            CameraSwitch.ObjectDetection to "detect",
            CameraSwitch.MotionDetection to "motion",
            CameraSwitch.Recording to "record",
            CameraSwitch.Snapshots to "snapshots",
            CameraSwitch.AudioDetection to "audio",
            CameraSwitch.Birdseye to "birdseye",
            CameraSwitch.FaceRecognition to "face_recognition",
            CameraSwitch.LicensePlate to "lpr",
        )

        expected.forEach { (switch, section) ->
            val change = cameraSwitchChange("door_cam", switch, true)
            assertEquals("config/cameras/door_cam/$section", change.updateTopic)
            assertEquals(
                """{"cameras":{"door_cam":{"$section":{"enabled":true}}}}""",
                change.configData.toString(),
            )
        }
    }

    /** Face recognition and number plates only work when the server has them on. */
    @Test
    fun theServerSwitchStopsTheTwoRecognitionSwitches() {
        val page = CameraSwitchPage(
            cameras = emptyList(),
            switches = emptyMap(),
            faceRecognitionOff = true,
            licensePlateOff = true,
        )

        assertEquals("Off for the whole server.", page.blocked(context, CameraSwitch.FaceRecognition))
        assertEquals("Off for the whole server.", page.blocked(context, CameraSwitch.LicensePlate))
        assertNull(page.blocked(context, CameraSwitch.Recording))
    }

    /** The server's own settings are read from its answer, on means a camera can use the feature. */
    @Test
    fun theServerSwitchIsReadFromTheAnswer() {
        val off = parse("""{"face_recognition": {"enabled": false}, "lpr": {"enabled": false}}""")
        val on = parse("""{"face_recognition": {"enabled": true}, "lpr": {"enabled": true}}""")
        val absent = parse("{}")

        assertEquals(false, off.faceRecognition.enabled)
        assertEquals(false, off.lpr.enabled)
        assertEquals(true, on.faceRecognition.enabled)
        assertEquals(true, on.lpr.enabled)
        assertEquals(true, absent.faceRecognition.enabled)
        assertEquals(true, absent.lpr.enabled)
    }
}
