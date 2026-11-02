package nz.mckenzie.frigo.ui

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The stretch of time a list covers. */
sealed interface Period {
    data object All : Period
    data object Today : Period
    data object Yesterday : Period

    /** One day, as the phone's calendar has it. */
    data class On(val day: LocalDate) : Period
}

private val zone: ZoneId get() = ZoneId.systemDefault()

/** Midnight at the start of a day, in epoch seconds, which is how Frigate counts time. */
private fun LocalDate.startSeconds(): Double = atStartOfDay(zone).toEpochSecond().toDouble()

/** The two ends the server is asked for, as epoch seconds, or nothing for either. */
fun Period.range(): Pair<Double?, Double?> = when (this) {
    Period.All -> null to null

    Period.Today -> LocalDate.now(zone).startSeconds() to null

    Period.Yesterday -> {
        val today = LocalDate.now(zone)
        today.minusDays(1).startSeconds() to today.startSeconds()
    }

    is Period.On -> day.startSeconds() to day.plusDays(1).startSeconds()
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/** How the day chip says it. */
fun Period.said(): String = when (this) {
    Period.All -> "All time"
    Period.Today -> "Today"
    Period.Yesterday -> "Yesterday"
    is Period.On -> day.format(dayFormat)
}
