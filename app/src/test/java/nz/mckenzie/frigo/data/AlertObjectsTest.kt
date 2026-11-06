package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The objects this phone lets buzz.
 *
 * The list is the phone's own, because Frigate turns a non-admin's change to its
 * alerts lists down. Empty is the whole of what the server sends, which is how the
 * app behaves without one.
 */
class AlertObjectsTest {

    @Test
    fun anEmptyListLetsEverythingThrough() {
        val objects = AlertObjects()

        assertTrue(objects.everything)
        assertTrue(objects.lets("person"))
        assertTrue(objects.lets("dog"))
    }

    @Test
    fun oneObjectTickedNarrowsTheList() {
        val objects = AlertObjects(setOf("person"))

        assertFalse(objects.everything)
        assertTrue(objects.lets("person"))
        assertFalse(objects.lets("car"))
    }

    /** Frigate's own names, matched as it writes them. */
    @Test
    fun aNameIsMatchedTheWayTheServerWritesIt() {
        val objects = AlertObjects(setOf("smoke_detector"))

        assertTrue(objects.lets("smoke_detector"))
        assertFalse(objects.lets("Smoke detector"))
    }

    /** It is kept on the phone as one block, so it outlives a restart. */
    @Test
    fun theListSurvivesThePhone() {
        val objects = AlertObjects(setOf("person", "car"))
        val json = Json { ignoreUnknownKeys = true }

        val back = json.decodeFromString(
            AlertObjects.serializer(),
            json.encodeToString(AlertObjects.serializer(), objects),
        )

        assertEquals(objects, back)
    }

    /** The picker's cameras narrow what is on show, not what a tick means. */
    @Test
    fun aPickedCameraShowsItsOwnObjectsAndEveryCameraShowsThemAll() {
        val choices = AlertObjectChoices(
            cameras = listOf(Camera("drive", "Drive"), Camera("shed", "Shed")),
            labels = mapOf(
                "drive" to listOf("person", "car"),
                "shed" to listOf("dog"),
            ),
        )

        assertEquals(listOf("car", "dog", "person"), choices.all)
        assertEquals(listOf("dog"), choices.forCamera("shed"))
        assertEquals(choices.all, choices.forCamera(null))
    }

    /** A camera the answer doesn't name offers nothing, and adds nothing to the whole. */
    @Test
    fun aCameraWithNoObjectsOfItsOwnIsEmpty() {
        val choices = AlertObjectChoices(labels = mapOf("drive" to listOf("person")))

        assertEquals(emptyList<String>(), choices.forCamera("quay"))
        assertEquals(listOf("person"), choices.all)
    }
}
