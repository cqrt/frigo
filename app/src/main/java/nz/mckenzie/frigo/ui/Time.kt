package nz.mckenzie.frigo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import nz.mckenzie.frigo.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The language the screen is being read in.
 *
 * The app can be in a language the phone isn't, and a screen with its days and
 * months in another language reads as a mistake. Every format on a screen is
 * built with this rather than with the phone's own.
 */
@Composable
fun screenLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** A date pattern in the screen's language, built once for that language. */
@Composable
fun dateFormat(pattern: String): DateTimeFormatter {
    val locale = screenLocale()
    return remember(pattern, locale) { DateTimeFormatter.ofPattern(pattern, locale) }
}

/**
 * "Just now", "14 min ago", "1 hr ago", "3 days ago", and a date once it is a
 * week old. The counts are plurals so a language that says them differently can.
 */
@Composable
fun relativeTime(millis: Long): String {
    val minutes = ((System.currentTimeMillis() - millis) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.time_minutes_ago, minutes, minutes)
        minutes < 60 * 24 -> {
            val hours = minutes / 60
            pluralStringResource(R.plurals.time_hours_ago, hours, hours)
        }

        minutes < 60 * 24 * 7 -> {
            val days = minutes / (60 * 24)
            pluralStringResource(R.plurals.time_days_ago, days, days)
        }

        else -> dateFormat("d MMM").format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )
    }
}
