package nz.mckenzie.frigo.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone keeps quiet, and for which cameras. */
class QuietTimesTest {

    /** A moment on one autumn day, in the phone's own time zone. */
    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2026, 11, 5)
            .atTime(LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private val night = QuietTimes(
        on = true,
        from = 22 * 60,
        until = 7 * 60,
        cameras = setOf("drive"),
    )

    @Test
    fun aSwitchThatIsOffSilencesNothing() {
        assertFalse(night.copy(on = false).quiets("drive", at(23)))
    }

    @Test
    fun aWindowRunsThroughMidnight() {
        assertTrue(night.quiets("drive", at(23)))
        assertTrue(night.quiets("drive", at(2)))
        assertTrue(night.quiets("drive", at(6, 59)))
    }

    /** The minute it starts is quiet, and the minute it ends is not. */
    @Test
    fun theWindowIsHalfOpen() {
        assertTrue(night.quiets("drive", at(22)))
        assertFalse(night.quiets("drive", at(7)))
        assertFalse(night.quiets("drive", at(21, 59)))
        assertFalse(night.quiets("drive", at(12)))
    }

    @Test
    fun aWindowThatDoesNotCrossMidnight() {
        val day = quietAbout(9, 17)
        assertTrue(day.quiets("drive", at(12)))
        assertFalse(day.quiets("drive", at(20)))
        assertFalse(day.quiets("drive", at(8, 59)))
    }

    @Test
    fun onlyThePickedCamerasGoQuiet() {
        assertFalse(night.quiets("shed", at(23)))
    }

    /** A window over no camera is a window over nothing, not over everything. */
    @Test
    fun noCameraPickedSilencesNothing() {
        assertFalse(night.copy(cameras = emptySet()).quiets("drive", at(23)))
    }

    @Test
    fun theSameTwoTimesIsAllDay() {
        val allDay = quietAbout(22, 22)
        assertTrue(allDay.quiets("drive", at(3)))
        assertTrue(allDay.quiets("drive", at(15)))
    }

    @Test
    fun aHushCoversEveryCamera() {
        val hush = QuietTimes(on = false, hushUntil = at(23) + 60 * 60_000L)
        assertTrue(hush.quiets("shed", at(23)))
    }

    @Test
    fun aHushThatHasRunOutIsGone() {
        val hush = QuietTimes(hushUntil = at(23) - 1)
        assertFalse(hush.quiets("shed", at(23)))
    }

    @Test
    fun theQuietEndsWhenTheWindowCloses() {
        val day = 24 * 60 * 60_000L
        assertEquals(at(7) + day, night.quietUntil(at(23)))
    }

    /** The later of the two, so the sentence is true for everything that is quiet. */
    @Test
    fun theQuietEndsWhenTheLastOfTheTwoDoes() {
        val day = 24 * 60 * 60_000L
        // A hush that runs past the window closing has the last word.
        assertEquals(at(8) + day, night.copy(hushUntil = at(8) + day).quietUntil(at(23)))
    }

    @Test
    fun nothingQuietMeansNoEnd() {
        assertNull(QuietTimes().quietUntil(at(23)))
        assertNull(night.copy(cameras = emptySet()).quietUntil(at(23)))
        assertNull(night.quietUntil(at(12)))
    }

    @Test
    fun theNextChangeIsWhenTheWindowOpens() {
        assertEquals(at(22), night.nextChange(at(12)))
    }

    @Test
    fun theNextChangeIsWhenTheWindowCloses() {
        val day = 24 * 60 * 60_000L
        assertEquals(at(7) + day, night.nextChange(at(23)))
    }

    @Test
    fun aHushRunningOutIsAChange() {
        val ends = at(23) + 60 * 60_000L
        assertEquals(ends, QuietTimes(hushUntil = ends).nextChange(at(23)))
    }

    @Test
    fun nothingSetMeansNothingToWaitFor() {
        assertNull(QuietTimes().nextChange(at(23)))
    }

    @Test
    fun theRowSaysWhatIsSet() {
        assertEquals("Off", QuietTimes().said(at(23)))
        assertTrue(night.said(at(12)).endsWith("one camera"))
        assertTrue(night.copy(cameras = emptySet()).said(at(12)).endsWith("no cameras"))
        assertTrue(night.said(at(23)).startsWith("Quiet until "))
    }

    private fun quietAbout(from: Int, until: Int) = QuietTimes(
        on = true,
        from = from * 60,
        until = until * 60,
        cameras = setOf("drive"),
    )
}
