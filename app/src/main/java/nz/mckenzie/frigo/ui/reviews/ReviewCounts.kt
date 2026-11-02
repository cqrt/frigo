package nz.mckenzie.frigo.ui.reviews

import nz.mckenzie.frigo.data.ReviewDayCount
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.ui.Period
import java.time.LocalDate
import java.time.ZoneId

/**
 * How many of one kind are left to look at, over the days a period covers.
 *
 * The server answers day by day, so a period of one day is that day's number and
 * a period of everything adds them up. Days are named the way the summary names
 * them, which is the phone's own calendar because that is what the day buttons
 * use.
 */
internal fun List<ReviewDayCount>.left(
    kind: ReviewKind,
    period: Period,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
): Int {
    val days = period.days(today)
    return filter { days == null || it.day in days }.sumOf { it.left(kind) }
}

/** What one day has left of one kind. */
internal fun ReviewDayCount.left(kind: ReviewKind): Int = when (kind) {
    ReviewKind.Alerts -> (totalAlerts - reviewedAlerts).coerceAtLeast(0)
    ReviewKind.Detections -> (totalDetections - reviewedDetections).coerceAtLeast(0)
}

/** Said when there is nothing left of one kind to look at. */
internal fun ReviewKind.emptyLeft(): String = when (this) {
    ReviewKind.Alerts -> "No alerts left to review."
    ReviewKind.Detections -> "No detections left to review."
}

/** Said when Frigate hasn't kept any of one kind at all. */
internal fun ReviewKind.emptyAll(): String = when (this) {
    ReviewKind.Alerts ->
        "No alerts yet. Frigate makes one when something you care about is seen " +
            "in a zone with alerts on."
    ReviewKind.Detections -> "No detections yet."
}

/** The days a period covers, or null for all of them. */
private fun Period.days(today: LocalDate): Set<String>? = when (this) {
    Period.All -> null
    Period.Today -> setOf(today.toString())
    Period.Yesterday -> setOf(today.minusDays(1).toString())
    is Period.On -> setOf(day.toString())
}
