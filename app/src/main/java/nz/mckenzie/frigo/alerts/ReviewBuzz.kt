package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.AlertPeople
import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.FrigateReview

/**
 * Whether one review message deserves a buzz, and which reviews have already had
 * one.
 *
 * A review that is an alert at its start buzzes at once. One that starts as a
 * detection buzzes when it turns into an alert, but only inside the first few
 * seconds: that window is what covers a zone, where someone has to step into the
 * driveway before it counts. A review that changes twice buzzes once, because its
 * id is remembered for a few minutes.
 *
 * This has no Android in it, so the rule can be tried without a phone.
 */
class ReviewBuzz(
    private val windowMs: Long = WINDOW_MS,
    private val keptMs: Long = KEPT_MS,
) {

    /** The reviews already buzzed, and when, by review id. */
    private val buzzed = mutableMapOf<String, Long>()

    /**
     * True when this message is worth a buzz, and saying so marks the review as
     * buzzed so the next change to it stays quiet.
     */
    fun buzzes(
        review: FrigateReview,
        change: String?,
        now: Long,
        rules: AlertRules,
        hidden: Set<String> = emptySet(),
        only: Set<String> = emptySet(),
        leftOut: AlertPeople = AlertPeople(),
    ): Boolean {
        if (!allowed(review, change, now, rules, hidden, only, leftOut)) return false
        buzzed[review.id] = now
        return true
    }

    /**
     * The same answer without remembering it.
     *
     * The service asks this when a message arrives and again once the name is in
     * hand, so an alert Frigate is about to put a name on costs no trip through the
     * rule twice with the first answer written down.
     *
     * [hidden] is the cameras turned off on this phone. [only] is the objects this
     * phone lets through and [leftOut] is the people it stays quiet about: both can
     * only take a buzz away.
     */
    fun allowed(
        review: FrigateReview,
        change: String?,
        now: Long,
        rules: AlertRules,
        hidden: Set<String> = emptySet(),
        only: Set<String> = emptySet(),
        leftOut: AlertPeople = AlertPeople(),
    ): Boolean {
        if (change != "new" && change != "update") return false
        if (review.severity != ALERT) return false
        if (review.id.isBlank() || review.camera.isBlank()) return false
        if (review.camera in hidden) return false

        // A detection that turns into an alert only counts near its own start.
        if (change == "update") {
            val started = review.startedAtMillis
            if (started <= 0 || now - started > windowMs) return false
        }

        forgetOld(now)
        if (review.id in buzzed) return false
        // Frigate names a recognised person on the review. One of those names on
        // this phone's list is the whole reason the list exists.
        if (leftOut.leavesOut(review.data.subLabels)) return false
        if (review.data.objects.none { rules.buzzes(review.camera, it, only) }) return false

        return true
    }

    /** Drops the ids of reviews old enough that they can no longer arrive twice. */
    private fun forgetOld(now: Long) {
        buzzed.entries.removeAll { now - it.value > keptMs }
    }

    companion object {
        /** A review that turns into an alert faster than this still counts. */
        const val WINDOW_MS = 10_000L

        /** How long a review's id is remembered, so a review that changes twice buzzes once. */
        const val KEPT_MS = 3 * 60_000L

        /** The severity Frigate gives a review it thinks is worth telling you about. */
        const val ALERT = "alert"
    }
}
