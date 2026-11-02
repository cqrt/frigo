package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSpansTest {

    /** Ten second segments, each ending a hair before the next one starts. */
    @Test
    fun segmentsWithSliversBetweenThemBecomeOneRun() {
        val spans = (0 until 6).map { n ->
            val start = 1_000.0 + n * 10
            RecordingSpan(start, start + 9.9667)
        }
        val joined = spans.joined()
        assertEquals(1, joined.size)
        assertEquals(1_000.0, joined[0].startTime, 0.001)
        assertEquals(1_059.9667, joined[0].endTime, 0.001)
    }

    /** A second of nothing is not a hole you can watch, so it goes. */
    @Test
    fun aHoleOfASecondIsFilledIn() {
        val joined = listOf(
            RecordingSpan(1_000.0, 1_010.0),
            RecordingSpan(1_011.0, 1_020.0),
        ).joined()
        assertEquals(1, joined.size)
        assertEquals(1_000.0, joined[0].startTime, 0.001)
        assertEquals(1_020.0, joined[0].endTime, 0.001)
    }

    /** A minute of nothing is what the bar draws as grey, so it stays. */
    @Test
    fun aRealGapIsLeftAlone() {
        val joined = listOf(
            RecordingSpan(1_000.0, 1_010.0),
            RecordingSpan(1_070.0, 1_080.0),
        ).joined()
        assertEquals(2, joined.size)
        assertEquals(1_010.0, joined[0].endTime, 0.001)
        assertEquals(1_070.0, joined[1].startTime, 0.001)
    }

    @Test
    fun anOverlapBecomesTheLongerOfTheTwo() {
        val joined = listOf(
            RecordingSpan(1_000.0, 1_020.0),
            RecordingSpan(1_015.0, 1_030.0),
        ).joined()
        assertEquals(1, joined.size)
        assertEquals(1_030.0, joined[0].endTime, 0.001)
    }

    @Test
    fun theRowsComeBackInOrderEvenWhenFrigateSendsThemAnyhow() {
        val joined = listOf(
            RecordingSpan(1_030.0, 1_040.0),
            RecordingSpan(1_000.0, 1_010.0),
        ).joined()
        assertEquals(2, joined.size)
        assertEquals(1_000.0, joined[0].startTime, 0.001)
        assertEquals(1_040.0, joined[1].endTime, 0.001)
    }

    @Test
    fun oneRunOnItsOwnIsLeftAsItIs() {
        val span = RecordingSpan(1_000.0, 1_010.0)
        assertEquals(listOf(span), listOf(span).joined())
        assertEquals(emptyList<RecordingSpan>(), emptyList<RecordingSpan>().joined())
    }
}
