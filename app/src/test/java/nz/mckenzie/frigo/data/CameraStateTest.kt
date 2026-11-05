package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The line under a camera's name on the Settings page. It has to say what is
 * off without listing eight things, and it must leave out a switch the server
 * holds off, which is off on every camera anyway.
 */
@RunWith(RobolectricTestRunner::class)
class CameraStateTest {

    /** The switch names come from the app's own resources, so they need a Context. */
    private val context = RuntimeEnvironment.getApplication()

    private fun page(
        switches: CameraSwitches = CameraSwitches(),
        licensePlateOff: Boolean = false,
        faceRecognitionOff: Boolean = false,
    ) = CameraSwitchPage(
        cameras = listOf(Camera(name = "north_cam", title = "North cam")),
        switches = mapOf("north_cam" to switches),
        faceRecognitionOff = faceRecognitionOff,
        licensePlateOff = licensePlateOff,
        admin = true,
    )

    @Test
    fun aCameraWithEverythingOnSaysSo() {
        val state = page().cameraState(context, "north_cam")
        assertEquals("Everything on", state.said)
        assertFalse(state.somethingOff)
    }

    /** The server holds license plate recognition off, so it says nothing about this camera. */
    @Test
    fun aSwitchTheServerHoldsOffIsLeftOut() {
        val state = page(licensePlateOff = true).cameraState(context, "north_cam")
        assertEquals("Everything on", state.said)
        assertFalse(state.somethingOff)
    }

    @Test
    fun oneSwitchOffIsNamed() {
        val state = page(CameraSwitches().with(CameraSwitch.ObjectDetection, false))
            .cameraState(context, "north_cam")
        assertEquals("Object detection off", state.said)
        assertTrue(state.somethingOff)
    }

    @Test
    fun twoAreNamedTogether() {
        val state = page(
            CameraSwitches()
                .with(CameraSwitch.ObjectDetection, false)
                .with(CameraSwitch.Recording, false),
        ).cameraState(context, "north_cam")
        assertEquals("Object detection and recording off", state.said)
    }

    /** Past two, the rest are counted rather than listed, so the line stays one line. */
    @Test
    fun threeOrMoreAreCounted() {
        val state = page(
            CameraSwitches()
                .with(CameraSwitch.ObjectDetection, false)
                .with(CameraSwitch.Recording, false)
                .with(CameraSwitch.AudioDetection, false),
        ).cameraState(context, "north_cam")
        assertEquals("Object detection, recording and 1 more off", state.said)
        assertTrue(state.somethingOff)
    }

    @Test
    fun almostEverythingOffIsStillOneLine() {
        val all = CameraSwitch.entries.fold(CameraSwitches()) { set, switch -> set.with(switch, false) }
        val state = page(all).cameraState(context, "north_cam")
        assertEquals("Object detection, motion detection and 6 more off", state.said)
    }

    /** A camera the page has never read says nothing rather than guessing. */
    @Test
    fun anUnknownCameraSaysEverythingIsOn() {
        val state = page().cameraState(context, "door_cam")
        assertEquals("Everything on", state.said)
    }
}
