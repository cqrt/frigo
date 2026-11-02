package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Review page can be narrowed to, and what it asks the server for.
 *
 * Frigate decides which labels make an alert or a detection, so the filter has
 * to read its own lists rather than offering every label the model can name.
 */
class ReviewFilterTest {

    private val frigateLabels = listOf("bark", "car", "cat", "person", "speech")

    private val shared = ReviewSettings(
        all = ReviewFlags(
            alertLabels = listOf("person", "car"),
            detectionLabels = null,
        ),
        cameras = emptyMap(),
        objects = frigateLabels,
        tracked = setOf("person", "car", "cat"),
    )

    @Test
    fun alertsOfferTheLabelsThatMakeOne() {
        assertEquals(listOf("person", "car"), shared.labelsFor(ReviewKind.Alerts, null))
    }

    /** No detection list means every other object, which is what is left once alerts are taken. */
    @Test
    fun detectionsOfferEveryOtherObject() {
        assertEquals(
            listOf("bark", "cat", "speech"),
            shared.labelsFor(ReviewKind.Detections, null),
        )
    }

    @Test
    fun aDetectionListOfItsOwnIsTheOneOffered() {
        val named = shared.copy(
            all = shared.all.copy(detectionLabels = listOf("cat")),
        )
        assertEquals(listOf("cat"), named.labelsFor(ReviewKind.Detections, null))
    }

    @Test
    fun aCameraWithItsOwnListsIsOfferedThose() {
        val perCamera = shared.copy(
            cameras = mapOf(
                "door_cam" to ReviewFlags(
                    alertLabels = listOf("cat"),
                    detectionLabels = listOf("bark"),
                ),
            ),
        )

        assertEquals(listOf("cat"), perCamera.labelsFor(ReviewKind.Alerts, "door_cam"))
        assertEquals(listOf("bark"), perCamera.labelsFor(ReviewKind.Detections, "door_cam"))
    }

    /** A camera that never had the page open on it follows the shared list. */
    @Test
    fun aCameraWithNothingOfItsOwnFollowsTheSharedList() {
        assertEquals(listOf("person", "car"), shared.labelsFor(ReviewKind.Alerts, "north_cam"))
        assertEquals(
            listOf("bark", "cat", "speech"),
            shared.labelsFor(ReviewKind.Detections, "north_cam"),
        )
    }

    @Test
    fun theRequestCarriesTheCameraTheLabelsAndTheDay() {
        assertEquals(
            listOf(
                "limit" to "30",
                "after" to "1",
                "before" to "1793491200",
                "cameras" to "north_cam,door_cam",
                "labels" to "person,car",
                "severity" to "alert",
                "reviewed" to "0",
            ),
            reviewQuery(
                severity = "alert",
                limit = 30,
                before = 1793491200.0,
                after = null,
                cameras = listOf("north_cam", "door_cam"),
                labels = listOf("person", "car"),
                showReviewed = false,
            ),
        )
    }

    /** Nothing narrows the list: every camera, every label, and the whole of Frigate's history. */
    @Test
    fun anUnnarrowedRequestAsksForEverything() {
        assertEquals(
            listOf(
                "limit" to "30",
                "after" to "1",
                "severity" to "detection",
                "reviewed" to "0",
            ),
            reviewQuery(
                severity = "detection",
                limit = 30,
                before = null,
                after = null,
                cameras = emptyList(),
                labels = emptyList(),
                showReviewed = false,
            ),
        )
    }

    /** Showing what you've watched is the server's default, so nothing is asked for. */
    @Test
    fun showingReviewedSendsNoReviewedFlag() {
        val query = reviewQuery(
            severity = "alert",
            limit = 30,
            before = null,
            after = null,
            cameras = emptyList(),
            labels = emptyList(),
            showReviewed = true,
        )
        assertEquals(null, query.firstOrNull { it.first == "reviewed" })
    }

    /** A day starts at a whole second, and a fraction in a URL would be the edge of it. */
    @Test
    fun theDayStartIsSentAsWholeSeconds() {
        val query = reviewQuery(
            severity = "alert",
            limit = 30,
            before = null,
            after = 1793404800.75,
            cameras = emptyList(),
            labels = emptyList(),
            showReviewed = false,
        )
        assertEquals("1793404800", query.first { it.first == "after" }.second)
    }
}
