package nz.mckenzie.frigo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * The month behind the day picker. Every day has to land under the right weekday,
 * with the blanks where the month begins and ends, however it starts.
 */
class MonthWeeksTest {

    /** November 2026 starts on a Sunday, so a Monday-first week opens with six blanks. */
    @Test
    fun aMonthStartingOnSundayLeavesSixBlanksBeforeIt() {
        val weeks = monthWeeks(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, weeks.size)
        assertEquals(List(6) { null } + LocalDate.of(2026, 11, 1), weeks.first())
        assertEquals(LocalDate.of(2026, 11, 30), weeks.last().dropLast(6).last())
        assertEquals(6, weeks.last().count { it == null })
    }

    /** A month that starts on the first day of the week needs no blanks at all. */
    @Test
    fun aMonthStartingOnMondayStartsAtTheFirstDay() {
        val weeks = monthWeeks(YearMonth.of(2026, 6), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 6, 1), weeks.first().first())
        // June has thirty days, so the last week holds the thirtieth and five blanks.
        assertEquals(LocalDate.of(2026, 6, 30), weeks.last().filterNotNull().last())
    }

    /** The phone decides where a week starts, and the grid follows it. */
    @Test
    fun aSundayFirstWeekPutsSundayInTheFirstColumn() {
        val weeks = monthWeeks(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), weeks.first().first())
    }

    /** Every day of the month appears once, in order, and nothing else does. */
    @Test
    fun everyDayAppearsOnceAndInOrder() {
        val month = YearMonth.of(2026, 2)
        val days = monthWeeks(month).flatten().filterNotNull()
        assertEquals(month.lengthOfMonth(), days.size)
        assertEquals(days.sorted(), days)
    }

    /** A week is always seven cells, blanks included, so the columns line up. */
    @Test
    fun everyWeekHoldsSevenCells() {
        (1..12).forEach { month ->
            monthWeeks(YearMonth.of(2026, month)).forEach { week ->
                assertEquals(7, week.size)
            }
        }
    }

    /** A leap February keeps its twenty-ninth day. */
    @Test
    fun aLeapFebruaryKeepsItsExtraDay() {
        val days = monthWeeks(YearMonth.of(2028, 2)).flatten()
        assertEquals(LocalDate.of(2028, 2, 29), days.filterNotNull().last())
    }

    /** Nothing is drawn outside the month's own days. */
    @Test
    fun theBlanksAreOnlyEverAtTheEdges() {
        val cells = monthWeeks(YearMonth.of(2026, 11)).flatten()
        val firstDay = cells.indexOfFirst { it != null }
        val lastDay = cells.indexOfLast { it != null }
        cells.take(firstDay).forEach { assertNull(it) }
        cells.drop(lastDay + 1).forEach { assertNull(it) }
    }
}
