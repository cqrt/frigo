package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.ReviewData
import nz.mckenzie.frigo.data.ReviewMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding the notification a description belongs to.
 *
 * The buzz goes out when the server sees something. The sentence about it is
 * written a few seconds later, on the review, which lists the events it covers.
 * Whichever of those two put the buzz out, the description has to land on the
 * same notification rather than post a second one.
 */
class AlertLedgerTest {

    /** Ten in the morning, in milliseconds. */
    private val ten = 1_700_000_000_000L

    private fun review(id: String = "r1", detections: List<String> = emptyList()) =
        FrigateReview(id = id, camera = "drive", data = ReviewData(detections = detections))

    /** The common case: the buzz came from the event, and the review covers it. */
    @Test
    fun aDescriptionFindsTheNotificationItsEventMade() {
        val ledger = AlertLedger()
        ledger.noteEvent("e1", 100, ten)

        assertEquals(100, ledger.take(review(detections = listOf("e1")), ten + 10_000))
    }

    /** The other path: the buzz came from the review itself. */
    @Test
    fun aDescriptionFindsTheNotificationItsReviewMade() {
        val ledger = AlertLedger()
        ledger.noteReview("r1", 101, ten)

        assertEquals(101, ledger.take(review(), ten + 10_000))
    }

    /** A review can cover more than one event, and any of them is the way in. */
    @Test
    fun anyOfTheEventsUnderAReviewWillDo() {
        val ledger = AlertLedger()
        ledger.noteEvent("e3", 102, ten)

        assertEquals(102, ledger.take(review(detections = listOf("e1", "e2", "e3")), ten + 10_000))
    }

    /** The description turns up twice; the second one changes nothing. */
    @Test
    fun aNotificationIsOnlyFilledInOnce() {
        val ledger = AlertLedger()
        ledger.noteEvent("e1", 100, ten)

        assertEquals(100, ledger.take(review(detections = listOf("e1")), ten + 10_000))
        assertNull(ledger.take(review(detections = listOf("e1")), ten + 20_000))
    }

    /** Nothing buzzed for this one, so there is nothing to fill in. */
    @Test
    fun aDescriptionWithNoNotificationBehindItGoesQuiet() {
        assertNull(AlertLedger().take(review(detections = listOf("e9")), ten))
    }

    /** Five minutes on, the notification has been swiped away or replaced. */
    @Test
    fun anOldNotificationIsForgotten() {
        val ledger = AlertLedger()
        ledger.noteEvent("e1", 100, ten)

        assertNull(ledger.take(review(detections = listOf("e1")), ten + 6 * 60_000))
    }
}
