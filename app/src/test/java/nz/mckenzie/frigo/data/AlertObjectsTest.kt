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

    /** Everything on one camera is a list of its own with nobody on it. */
    @Test
    fun everythingOnOneCameraLeavesTheOthersFiltered() {
        val objects = AlertObjects(labels = setOf("person"))
            .everythingFor("door_cam")

        assertTrue(objects.lets("door_cam", "dog"))
        assertFalse(objects.lets("north_cam", "dog"))
    }

    /** Writing a camera's list keeps every other camera's. */
    @Test
    fun writingOneCameraLeavesTheRestAlone() {
        val objects = AlertObjects(labels = setOf("person"))
            .withLabels("door_cam", setOf("cat"))

        assertEquals(setOf("person"), objects.labelsFor("north_cam"))
        assertEquals(setOf("cat"), objects.labelsFor("door_cam"))
    }

    /** Everything for every camera is the phone saying nothing at all. */
    @Test
    fun everythingForEveryCameraClearsThemAll() {
        val objects = AlertObjects(
            labels = setOf("person"),
            cameras = mapOf("door_cam" to setOf("cat")),
        ).everythingFor(null)

        assertTrue(objects.everything)
        assertEquals(emptyMap<String, Set<String>>(), objects.cameras)
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
