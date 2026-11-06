package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.AlertPeople
import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.CameraAlerts
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.ReviewData
import nz.mckenzie.frigo.data.ReviewMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the server's review messages buzz the phone.
 *
 * A review can start as a detection and turn into an alert a few seconds later,
 * which is what a zone needs: someone has to step into the driveway before it
 * counts. The rule is a window from the review's own start, and it is checked
 * against a clock the test hands in.
 */
class ReviewBuzzTest {

    /** A server telling you about people on the drive. */
    private val rules = AlertRules(
        cameras = mapOf(
            "drive" to CameraAlerts(notify = true, alertsOn = true, labels = listOf("person")),
            "quay" to CameraAlerts(notify = false, alertsOn = true, labels = listOf("person")),
        ),
    )

    /** Ten in the morning, in milliseconds. */
    private val ten = 1_700_000_000_000L

    private fun review(
        id: String = "1",
        camera: String = "drive",
        severity: String = "alert",
        objects: List<String> = listOf("person"),
        names: List<String> = emptyList(),
        sentence: String? = null,
        startedAt: Long = ten,
    ) = FrigateReview(
        id = id,
        camera = camera,
        startTime = startedAt / 1000.0,
        severity = severity,
        data = ReviewData(
            objects = objects,
            subLabels = names,
            metadata = sentence?.let { ReviewMetadata(title = it, shortSummary = it) },
        ),
    )

    @Test
    fun aReviewThatStartsAsAnAlertBuzzes() {
        assertTrue(ReviewBuzz().buzzes(review(), "new", ten, rules))
    }

    /** It starts as a detection, and turns into an alert two seconds later. */
    @Test
    fun aDetectionThatBecomesAnAlertBuzzes() {
        val buzz = ReviewBuzz()
        val detection = review(severity = "detection")
        assertFalse(buzz.buzzes(detection, "new", ten, rules))
        assertTrue(buzz.buzzes(review(), "update", ten + 2_000, rules))
    }

    /** Twenty seconds on, the moment has passed and the phone stays quiet. */
    @Test
    fun aDetectionThatBecomesAnAlertLongAfterItsStartDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(), "update", ten + 20_000, rules))
    }

    /** Its first message buzzed, so the next change to it does not. */
    @Test
    fun theSameReviewDoesNotBuzzTwice() {
        val buzz = ReviewBuzz()
        assertTrue(buzz.buzzes(review(), "new", ten, rules))
        assertFalse(buzz.buzzes(review(), "update", ten + 1_000, rules))
        assertFalse(buzz.buzzes(review(), "update", ten + 5_000, rules))
    }

    /** An id is forgotten after a few minutes, so memory does not grow all day. */
    @Test
    fun anOldReviewIdIsForgotten() {
        val buzz = ReviewBuzz()
        assertTrue(buzz.buzzes(review(id = "1"), "new", ten, rules))
        assertTrue(buzz.buzzes(review(id = "2"), "new", ten + 5 * 60_000, rules))
        assertTrue(buzz.buzzes(review(id = "1"), "new", ten + 6 * 60_000, rules))
    }

    @Test
    fun aReviewWithNoAlertSeverityDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(severity = ""), "new", ten, rules))
        assertFalse(ReviewBuzz().buzzes(review(severity = "detection"), "new", ten, rules))
    }

    @Test
    fun aCameraTheServerTurnedOffDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(camera = "quay"), "new", ten, rules))
    }

    /** Turning a camera off on this phone silences it here, whatever the server says. */
    @Test
    fun aCameraTurnedOffOnThisPhoneDoesNotBuzz() {
        assertTrue(ReviewBuzz().buzzes(review(), "new", ten, rules))
        assertFalse(
            ReviewBuzz().buzzes(review(id = "2"), "new", ten, rules, hidden = setOf("drive")),
        )
    }

    @Test
    fun aReviewWithNoObjectOnTheAlertListDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(objects = listOf("car")), "new", ten, rules))
    }

    /** One of the things in a review can be the one that counts. */
    @Test
    fun aReviewCountsWhenAnyOfItsObjectsIsOnTheList() {
        assertTrue(ReviewBuzz().buzzes(review(objects = listOf("car", "person")), "new", ten, rules))
    }

    /** An `end` message is the review finishing, not something new to tell you. */
    @Test
    fun anEndMessageDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(), "end", ten, rules))
    }

    @Test
    fun aReviewWithNoIdDoesNotBuzz() {
        assertFalse(ReviewBuzz().buzzes(review(id = ""), "new", ten, rules))
    }

    /** A list of this phone's own can silence an alert the server would send. */
    @Test
    fun anAlertWhoseObjectIsNotOnThisPhonesListDoesNotBuzz() {
        assertFalse(
            ReviewBuzz().buzzes(
                review(id = "1"),
                "new",
                ten,
                rules,
                only = AlertObjects(labels = setOf("car")),
            ),
        )
        assertTrue(
            ReviewBuzz().buzzes(
                review(id = "2"),
                "new",
                ten,
                rules,
                only = AlertObjects(labels = setOf("person")),
            ),
        )
    }

    /** A list kept for one camera doesn't silence another. */
    @Test
    fun aListForAnotherCameraDoesNotSilenceThisOne() {
        val objects = AlertObjects(cameras = mapOf("quay" to setOf("car")))

        assertTrue(ReviewBuzz().buzzes(review(), "new", ten, rules, only = objects))
    }

    /** A person this phone stays quiet about is left out, whatever the object is. */
    @Test
    fun aReviewNamingSomeoneLeftOutDoesNotBuzz() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(
            ReviewBuzz().buzzes(review(names = listOf("Matt")), "new", ten, rules, leftOut = people),
        )
        assertTrue(
            ReviewBuzz().buzzes(
                review(id = "2", names = listOf("Angie")),
                "new",
                ten,
                rules,
                leftOut = people,
            ),
        )
    }

    /** Frigate marks a name it has verified itself, and the tick matches it either way. */
    @Test
    fun aVerifiedNameIsLeftOutToo() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(
            ReviewBuzz().buzzes(
                review(names = listOf("Matt-verified")),
                "new",
                ten,
                rules,
                leftOut = people,
            ),
        )
    }

    /** A review with no name yet is decided by its objects, as it always was. */
    @Test
    fun aReviewWithNoNameIsDecidedByItsObjects() {
        assertTrue(
            ReviewBuzz().buzzes(review(), "new", ten, rules, leftOut = AlertPeople(setOf("Matt"))),
        )
    }

    /**
     * Frigate's sentence is where the name often is, and by the time an alert has
     * waited for it there are no sub labels to go on.
     */
    @Test
    fun aSentenceNamingSomeoneLeftOutDoesNotBuzz() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(
            ReviewBuzz().buzzes(
                review(sentence = "Matt walking up the driveway."),
                "new",
                ten,
                rules,
                leftOut = people,
            ),
        )
        assertTrue(
            ReviewBuzz().buzzes(
                review(id = "2", sentence = "Someone walking up the driveway."),
                "new",
                ten,
                rules,
                leftOut = people,
            ),
        )
    }

    /** The service asks twice while it waits for a name; the first ask must not count. */
    @Test
    fun askingWithoutRememberingLeavesTheReviewFreeToBuzz() {
        val buzz = ReviewBuzz()

        assertTrue(buzz.allowed(review(), "new", ten, rules))
        assertTrue(buzz.buzzes(review(), "new", ten, rules))
        assertFalse(buzz.allowed(review(), "new", ten + 1_000, rules))
    }
}
