package nz.mckenzie.frigo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The two ends of a day, which is what the day chip sends to the server. */
class PeriodTest {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun startOf(day: LocalDate): Double =
        day.atStartOfDay(zone).toEpochSecond().toDouble()

    @Test
    fun allTimeAsksForNoEnds() {
        assertEquals(null to null, Period.All.range())
    }

    @Test
    fun todayStartsAtMidnightAndHasNoEnd() {
        assertEquals(startOf(LocalDate.now(zone)) to null, Period.Today.range())
    }

    @Test
    fun yesterdayIsOneWholeDay() {
        val today = LocalDate.now(zone)
        assertEquals(
            startOf(today.minusDays(1)) to startOf(today),
            Period.Yesterday.range(),
        )
    }

    /** A day picked from the calendar is that day on the phone, not on the server. */
    @Test
    fun aPickedDayIsOneWholeDay() {
        val day = LocalDate.of(2026, 3, 8)
        assertEquals(startOf(day) to startOf(day.plusDays(1)), Period.On(day).range())
    }

    /** A clock change makes that day 23 or 25 hours long, and midnight still starts it. */
    @Test
    fun aDayIsFromItsOwnMidnight() {
        val day = LocalDate.of(2026, 9, 27)
        assertEquals(startOf(day), Period.On(day).range().first)
        assertEquals(startOf(day.plusDays(1)), Period.On(day).range().second)
    }
}
