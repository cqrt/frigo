package nz.mckenzie.frigo.alerts

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
     *
     * [change] is the message's `type`: `new` or `update`. Anything else is a
     * message about a review that has moved on.
     *
     * [hidden] is the cameras turned off on this phone. One of them never buzzes,
     * whatever the server's rules say. [only] is the objects this phone lets
     * through, which can only take a buzz away.
     */
    fun buzzes(
        review: FrigateReview,
        change: String?,
        now: Long,
        rules: AlertRules,
        hidden: Set<String> = emptySet(),
        only: Set<String> = emptySet(),
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
        if (review.data.objects.none { rules.buzzes(review.camera, it, only) }) return false

        buzzed[review.id] = now
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
