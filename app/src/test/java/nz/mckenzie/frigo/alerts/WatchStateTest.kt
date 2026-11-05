package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.CameraAlerts
import nz.mckenzie.frigo.data.QuietTimes
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** What the quiet notification says while the app is watching. */
@RunWith(RobolectricTestRunner::class)
class WatchStateTest {

    /** The sentences come from the app's own resources, so they need a Context. */
    private val context = RuntimeEnvironment.getApplication()

    /** A moment on one autumn day, in the phone's own time zone. */
    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 11, 5)
            .atTime(LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val buzzesForPerson = AlertRules(
        cameras = mapOf(
            "drive" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
        ),
    )

    @Test
    fun itCountsTheCamerasThatWillBuzzAndNamesTheirObjects() {
        val rules = AlertRules(
            cameras = mapOf(
                "drive" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
                "shed" to CameraAlerts(notify = false, alertsOn = true, labels = listOf("person")),
            ),
        )

        assertEquals(
            "One camera for person. New events arrive as they happen.",
            WatchState.Connected.text(context, rules),
        )
    }

    /** The one thing that can keep every camera quiet is every camera being off. */
    @Test
    fun everyCameraOffSaysSo() {
        val rules = AlertRules(
            cameras = mapOf(
                "drive" to CameraAlerts(notify = false, alertsOn = true, labels = listOf("person")),
            ),
        )

        assertEquals(
            "Every camera is off on your server's notifications page, so nothing will buzz.",
            WatchState.Connected.text(context, rules),
        )
    }

    /** Every camera turned off on this phone is the phone's own doing, so the line says so. */
    @Test
    fun everyCameraHiddenSaysSo() {
        val text = WatchState.Connected.text(
            context,
            buzzesForPerson,
            hidden = setOf("drive"),
        )

        assertEquals(
            "Every camera that would buzz is turned off on this phone, so nothing will buzz. " +
                "Turn one back on in Settings, Cameras.",
            text,
        )
    }

    /** One of two hidden still leaves a camera that will buzz, and the count says one. */
    @Test
    fun aCameraStillOnIsCountedAndTheHiddenOneIsNot() {
        val rules = AlertRules(
            cameras = mapOf(
                "drive" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
                "shed" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
            ),
        )

        val text = WatchState.Connected.text(context, rules, hidden = setOf("drive"))

        assertEquals("One camera for person. New events arrive as they happen.", text)
    }

    /** Quiet on purpose looks the same as broken unless the line says why. */
    @Test
    fun itSaysWhenTheQuietEnds() {
        val quiet = QuietTimes(
            on = true,
            from = 22 * 60,
            until = 7 * 60,
            cameras = setOf("drive"),
        )

        val text = WatchState.Connected.text(context, buzzesForPerson, quiet, at(23))

        assertTrue(text.startsWith("Quiet until "))
        assertTrue(text.endsWith("One camera for person. New events arrive as they happen."))
    }

    /** Half a window still buzzes, so the line must not claim more silence than there is. */
    @Test
    fun aCameraOutsideTheWindowLeavesTheLineAlone() {        val rules = AlertRules(
            cameras = mapOf(
                "drive" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
                "shed" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
            ),
        )
        val quiet = QuietTimes(
            on = true,
            from = 22 * 60,
            until = 7 * 60,
            cameras = setOf("drive"),
        )

        val text = WatchState.Connected.text(context, rules, quiet, at(23))

        assertFalse(text.contains("Quiet"))
        assertEquals("2 cameras for person. New events arrive as they happen.", text)
    }
}
