package nz.mckenzie.frigo.ui

import nz.mckenzie.frigo.data.ReviewDayCount
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.ui.reviews.left
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * What the numbers on the two halves add up to. The server answers day by day,
 * so a day, a week and everything are the same sum over a different span.
 */
class ReviewCountsTest {

    private val today = LocalDate.of(2026, 11, 3)

    private val days = listOf(
        ReviewDayCount("2026-11-01", totalAlerts = 2, reviewedAlerts = 2),
        ReviewDayCount(
            "2026-11-02",
            totalAlerts = 5,
            reviewedAlerts = 1,
            totalDetections = 8,
            reviewedDetections = 3,
        ),
        ReviewDayCount("2026-11-03", totalAlerts = 14, totalDetections = 1),
    )

    @Test
    fun everythingIsEveryDayAddedUp() {
        assertEquals(18, days.left(ReviewKind.Alerts, Period.All, today))
        assertEquals(6, days.left(ReviewKind.Detections, Period.All, today))
    }

    @Test
    fun todayIsOneDayOnly() {
        assertEquals(14, days.left(ReviewKind.Alerts, Period.Today, today))
        assertEquals(1, days.left(ReviewKind.Detections, Period.Today, today))
    }

    @Test
    fun yesterdayIsTheDayBefore() {
        assertEquals(4, days.left(ReviewKind.Alerts, Period.Yesterday, today))
        assertEquals(5, days.left(ReviewKind.Detections, Period.Yesterday, today))
    }

    @Test
    fun aPickedDayIsTheOnlyOneCounted() {
        assertEquals(0, days.left(ReviewKind.Alerts, Period.On(LocalDate.of(2026, 11, 1)), today))
    }

    @Test
    fun aDayWithNothingOnItCountsNothing() {
        assertEquals(0, days.left(ReviewKind.Alerts, Period.Today, LocalDate.of(2026, 10, 1)))
    }

    /** Watched more than Frigate counted would be a server quirk, not less than nothing. */
    @Test
    fun aDayNeverCountsLessThanNothing() {
        val odd = listOf(ReviewDayCount("2026-11-03", totalAlerts = 1, reviewedAlerts = 3))
        assertEquals(0, odd.left(ReviewKind.Alerts, Period.Today, today))
    }
}
