package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.ReviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Finding the notification a description belongs to.
 *
 * The buzz goes out when the server reports an alert. The sentence about it is
 * written a few seconds later, on the same review, and has to land on the
 * notification already there rather than post a second one.
 */
class AlertLedgerTest {

    /** Ten in the morning, in milliseconds. */
    private val ten = 1_700_000_000_000L

    private fun review(id: String = "r1") =
        FrigateReview(id = id, camera = "drive", data = ReviewData())

    /** The buzz came from the review, and its description follows. */
    @Test
    fun aDescriptionFindsTheNotificationItsReviewMade() {
        val ledger = AlertLedger()
        ledger.noteReview("r1", 101, ten)

        assertEquals(101, ledger.take(review(), ten + 10_000))
    }

    /** The description turns up twice; the second one changes nothing. */
    @Test
    fun aNotificationIsOnlyFilledInOnce() {
        val ledger = AlertLedger()
        ledger.noteReview("r1", 101, ten)

        assertEquals(101, ledger.take(review(), ten + 10_000))
        assertNull(ledger.take(review(), ten + 20_000))
    }

    /** Nothing buzzed for this one, so there is nothing to fill in. */
    @Test
    fun aDescriptionWithNoNotificationBehindItGoesQuiet() {
        assertNull(AlertLedger().take(review(), ten))
    }

    /** A description for a different review does not land on this one. */
    @Test
    fun anotherReviewKeepsItsOwnNotification() {
        val ledger = AlertLedger()
        ledger.noteReview("r1", 101, ten)

        assertNull(ledger.take(review("r2"), ten + 10_000))
        assertEquals(101, ledger.take(review("r1"), ten + 10_000))
    }

    /** Five minutes on, the notification has been swiped away or replaced. */
    @Test
    fun anOldNotificationIsForgotten() {
        val ledger = AlertLedger()
        ledger.noteReview("r1", 101, ten)

        assertNull(ledger.take(review(), ten + 6 * 60_000))
    }
}
