package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This phone's own list of the server's cameras: which ones it leaves out, what it
 * calls them, and the order they appear in.
 *
 * None of it reaches the server, so a camera turned off here is still recorded and
 * still buzzes every other phone. What the list decides is what this phone draws.
 */
class CameraListTest {

    private fun camera(name: String, title: String) = Camera(name = name, title = title)

    private val drive = camera("drive", "Drive")
    private val shed = camera("shed", "Shed")
    private val quay = camera("quay", "Quay")

    /** Your own order first, then anything you haven't placed, by name. */
    @Test
    fun theOrderPickedComesFirstAndTheRestFollowByName() {
        val list = CameraList(order = listOf("quay", "drive"))

        assertEquals(
            listOf("quay", "drive", "shed"),
            list.shown(listOf(drive, shed, quay)).map { it.name },
        )
    }

    /** A camera the server has added since the order was last touched lands at the end. */
    @Test
    fun aCameraAddedLaterLandsAtTheEnd() {
        val list = CameraList(order = listOf("shed"))

        assertEquals(
            listOf("shed", "drive", "quay"),
            list.shown(listOf(drive, quay, shed)).map { it.name },
        )
    }

    /** The screens draw `shown`; the manage list and a camera's own page draw `all`. */
    @Test
    fun aHiddenCameraIsLeftOutOfShownAndKeptInAll() {
        val list = CameraList(hidden = setOf("shed"))

        assertEquals(listOf("drive", "quay"), list.shown(listOf(drive, shed, quay)).map { it.name })
        assertEquals(listOf("drive", "quay", "shed"), list.all(listOf(drive, shed, quay)).map { it.name })
        assertTrue(list.hides("shed"))
        assertFalse(list.hides("drive"))
    }

    /** A name of its own where there is one, and the server's when there isn't. */
    @Test
    fun aNameOfYourOwnIsUsedAndABlankOneFallsBack() {
        val named = CameraList(names = mapOf("drive" to "Front gate", "shed" to "   "))

        assertEquals(
            listOf("Front gate", "Shed"),
            named.shown(listOf(drive, shed)).map { it.title },
        )
    }

    /** The server renaming a camera leaves the old name behind, so the camera comes back. */
    @Test
    fun aNameTheServerNoLongerHasIsIgnored() {
        val list = CameraList(
            hidden = setOf("old_drive"),
            names = mapOf("old_drive" to "Front gate"),
            order = listOf("old_drive"),
        )

        assertEquals(listOf("drive"), list.shown(listOf(drive)).map { it.name })
        assertEquals(listOf("Drive"), list.shown(listOf(drive)).map { it.title })
    }

    /** A row dropped somewhere new, said against the order the drag started in. */
    @Test
    fun movingPutsACameraWhereItWasDropped() {
        val before = listOf("drive", "shed", "quay")

        assertEquals(
            listOf("shed", "drive", "quay"),
            CameraList().moving("shed", 0, before).order,
        )
        assertEquals(
            listOf("shed", "quay", "drive"),
            CameraList().moving("drive", 2, before).order,
        )
    }

    /** The switch writes the set of hidden cameras, and nothing else moves with it. */
    @Test
    fun turningOneOffAndBackLeavesTheRestAlone() {
        val list = CameraList(names = mapOf("drive" to "Gate"), order = listOf("drive"))

        val off = list.withHidden("shed", true)
        assertTrue(off.hides("shed"))
        assertEquals(list.names, off.names)
        assertEquals(list.order, off.order)

        val back = off.withHidden("shed", false)
        assertFalse(back.hides("shed"))
    }

    /** Clearing a name goes back to the server's, rather than to a nameless camera. */
    @Test
    fun clearingANameGoesBackToTheServers() {
        val named = CameraList().naming("drive", "Front gate")
        assertEquals("Front gate", named.name("drive", "Drive"))

        val cleared = named.naming("drive", "")
        assertEquals("Drive", cleared.name("drive", "Drive"))
        assertTrue(cleared.names.isEmpty())
    }
}
