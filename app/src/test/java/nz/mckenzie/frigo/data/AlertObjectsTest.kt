package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The objects this phone lets buzz, camera by camera.
 *
 * The list is the phone's own, because Frigate turns a non-admin's change to its
 * alerts lists down. Empty is the whole of what the server sends, which is how the
 * app behaves without one. All cameras is the list every camera follows, and a
 * camera with one of its own follows that.
 */
class AlertObjectsTest {

    @Test
    fun anEmptyListLetsEverythingThrough() {
        val objects = AlertObjects()

        assertTrue(objects.everything)
        assertTrue(objects.lets("door_cam", "person"))
        assertTrue(objects.lets("door_cam", "dog"))
    }

    @Test
    fun oneObjectTickedNarrowsTheList() {
        val objects = AlertObjects(labels = setOf("person"))

        assertFalse(objects.everything)
        assertTrue(objects.lets("door_cam", "person"))
        assertFalse(objects.lets("door_cam", "car"))
    }

    /** A camera with a list of its own follows that and not the shared one. */
    @Test
    fun aCameraWithItsOwnListIsJudgedByIt() {
        val objects = AlertObjects(
            labels = setOf("person", "car"),
            cameras = mapOf("door_cam" to setOf("cat")),
        )

        assertFalse(objects.everything)
        assertTrue(objects.lets("door_cam", "cat"))
        assertFalse(objects.lets("door_cam", "person"))
        // Every other camera follows the shared list.
        assertTrue(objects.lets("north_cam", "person"))
        assertFalse(objects.lets("north_cam", "cat"))
    }

    /** A camera with everything it alerts on ticked is kept as no list at all. */
    @Test
    fun tickingEveryObjectMeansNoList() {
        val objects = AlertObjects()
            .withChosen("door_cam", setOf("person", "car"), setOf("person", "car"))

        assertEquals(emptySet<String>(), objects.cameras.getValue("door_cam"))
        assertTrue(objects.lets("door_cam", "dog"))
    }

    /** Taking one off is a list, and only that camera's. */
    @Test
    fun takingOneOffKeepsTheRest() {
        val objects = AlertObjects()
            .withChosen("door_cam", setOf("person"), setOf("person", "car"))

        assertEquals(setOf("person"), objects.labelsFor("door_cam"))
        assertTrue(objects.lets("north_cam", "dog"))
    }

    /** A camera kept with an empty list is not one the phone is filtering. */
    @Test
    fun aCameraSetToEverythingLeavesThePhoneUnfiltered() {
        val objects = AlertObjects().withLabels("door_cam", emptySet())

        assertTrue(objects.everything)
        assertTrue(objects.lets("door_cam", "dog"))
    }

    /** Frigate's own names, matched as it writes them. */
    @Test
    fun aNameIsMatchedTheWayTheServerWritesIt() {
        val objects = AlertObjects(labels = setOf("smoke_detector"))

        assertTrue(objects.lets("door_cam", "smoke_detector"))
        assertFalse(objects.lets("door_cam", "Smoke detector"))
    }

    /** It is kept on the phone as one block, so it outlives a restart. */
    @Test
    fun theListSurvivesThePhone() {
        val objects = AlertObjects(
            labels = setOf("person", "car"),
            cameras = mapOf("door_cam" to setOf("person")),
        )
        val json = Json { ignoreUnknownKeys = true }

        val back = json.decodeFromString(
            AlertObjects.serializer(),
            json.encodeToString(AlertObjects.serializer(), objects),
        )

        assertEquals(objects, back)
    }

    /** A list written by the version with one list for every camera still reads. */
    @Test
    fun aListFromBeforeTheCamerasStillReads() {
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(
            AlertObjects.serializer(),
            """{"labels":["person"]}""",
        )

        assertEquals(AlertObjects(labels = setOf("person")), back)
        assertTrue(back.lets("door_cam", "person"))
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
