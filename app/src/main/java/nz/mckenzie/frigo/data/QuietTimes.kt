package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The stretch of the day this phone keeps quiet, and the cameras it keeps quiet.
 *
 * It is the phone's own setting rather than the server's. Frigate has nothing
 * like it, and what it silences is this phone's notifications, not what the
 * server records or what any other phone is told.
 *
 * [from] and [until] are minutes after midnight. A window that runs from a time
 * to an earlier one crosses midnight, which is the usual case for a night. The
 * window is half open: the minute it starts is quiet and the minute it ends is
 * not.
 *
 * [hushUntil] is a silence that runs out by itself after an hour. It covers
 * every camera and leaves the window alone, so it is the "be quiet now" that
 * does not change anything you set.
 *
 * There is no Android here, so the rule can be tried without a phone.
 */
@Serializable
data class QuietTimes(
    val on: Boolean = false,
    val from: Int = DEFAULT_FROM,
    val until: Int = DEFAULT_UNTIL,
    val cameras: Set<String> = emptySet(),
    val hushUntil: Long = 0L,
) {

    /** True when this camera's alerts should not reach you at [now]. */
    fun quiets(camera: String, now: Long): Boolean {
        // A hush outranks everything, and covers cameras the window doesn't.
        if (hushUntil > now) return true
        if (!on || camera !in cameras) return false
        return insideWindow(now)
    }

    /**
     * When the quiet ends, or null when it isn't quiet. The later of the window
     * closing and a hush running out, so the sentence stays true for every
     * camera that is quiet.
     */
    fun quietUntil(now: Long): Long? {
        val hush = hushUntil.takeIf { it > now }
        // A window with no camera picked is a window over nothing.
        val window = if (on && cameras.isNotEmpty() && insideWindow(now)) windowEnd(now) else null
        return listOfNotNull(hush, window).maxOrNull()
    }

    /**
     * The next moment the answer for any camera can change: the window opening
     * or closing, or a hush running out. Null when nothing is set to change.
     *
     * The service sleeps until this rather than checking every minute, and it
     * keeps the phone's clock honest when you travel or the clocks change.
     */
    fun nextChange(now: Long): Long? {
        val changes = buildList {
            if (hushUntil > now) add(hushUntil)
            if (on) {
                add(nextOccurrence(from, now))
                add(nextOccurrence(until, now))
            }
        }
        return changes.filter { it > now }.minOrNull()
    }

    /** One line for the Settings row: what is set, in the fewest words that are still true. */
    fun said(now: Long): String {
        quietUntil(now)?.let { return "Quiet until ${clockTime(it)}" }
        if (!on) return "Off"
        val window = if (from == until) "All day" else "${clockTime(from)} to ${clockTime(until)}"
        val which = when (cameras.size) {
            0 -> "no cameras"
            1 -> "one camera"
            else -> "${cameras.size} cameras"
        }
        return "$window, $which"
    }

    private fun insideWindow(now: Long): Boolean {
        val minute = minuteOfDay(now)
        return when {
            from == until -> true
            from < until -> minute >= from && minute < until
            else -> minute >= from || minute < until
        }
    }

    private fun windowEnd(now: Long): Long = nextOccurrence(until, now)

    /**
     * That time of day next, today if it hasn't gone by and tomorrow if it has.
     * Standing exactly on it counts as gone, so the boundary that has just
     * passed is never handed back.
     */
    private fun nextOccurrence(minute: Int, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val today = at.toLocalDate().atTime(minute / 60, minute % 60).atZone(zone)
        val whenAt = if (today.toInstant().toEpochMilli() > now) {
            today
        } else {
            at.toLocalDate().plusDays(1).atTime(minute / 60, minute % 60).atZone(zone)
        }
        return whenAt.toInstant().toEpochMilli()
    }

    private fun minuteOfDay(now: Long): Int {
        val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        return time.hour * 60 + time.minute
    }

    companion object {
        /** Ten at night: the hour most people would pick. */
        const val DEFAULT_FROM = 22 * 60

        /** Seven in the morning. */
        const val DEFAULT_UNTIL = 7 * 60
    }
}

/** A time of day the way the phone writes one, such as 7:00 am or 07:00. */
fun clockTime(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(localTimeFormat())

/** The same for a moment, which is what a hush is counted to. */
fun clockTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(localTimeFormat())

private fun localTimeFormat(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
