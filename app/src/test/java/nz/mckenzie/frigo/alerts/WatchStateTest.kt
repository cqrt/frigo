package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.CameraAlerts
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the quiet notification says while the app is watching. */
class WatchStateTest {

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
            WatchState.Connected.text(rules),
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
            WatchState.Connected.text(rules),
        )
    }
}
