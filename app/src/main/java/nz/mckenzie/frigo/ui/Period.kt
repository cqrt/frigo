package nz.mckenzie.frigo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import nz.mckenzie.frigo.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The stretch of time a list covers. */
sealed interface Period {
    data object All : Period
    data object Today : Period
    data object Yesterday : Period

    /**
     * The day just gone, ending now rather than at midnight. Frigate's own page
     * opens on this one, so it is where the two can be compared.
     */
    data object Last24Hours : Period

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

    Period.Last24Hours -> (Instant.now().epochSecond - SecondsInADay).toDouble() to null

    Period.Yesterday -> {
        val today = LocalDate.now(zone)
        today.minusDays(1).startSeconds() to today.startSeconds()
    }

    is Period.On -> day.startSeconds() to day.plusDays(1).startSeconds()
}

private const val SecondsInADay = 86_400

/** How the day chip says it. */
@Composable
fun Period.said(): String = when (this) {
    Period.All -> stringResource(R.string.period_all_time)
    Period.Today -> stringResource(R.string.period_today)
    Period.Last24Hours -> stringResource(R.string.period_last_24_hours)
    Period.Yesterday -> stringResource(R.string.period_yesterday)
    is Period.On -> dateFormat("EEE d MMM").format(day)
}
