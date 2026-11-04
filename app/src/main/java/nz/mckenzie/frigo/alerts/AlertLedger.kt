package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.FrigateReview

/**
 * Which notification in the shade belongs to which review.
 *
 * The description of a review arrives a few seconds after the buzz went out, so
 * the two have to be joined back up to rewrite the notification already there
 * rather than post a second one. Every buzz comes from a review, and the
 * description carries that same review's id, which is the thread to pull.
 *
 * This has no Android in it, so the joining can be tried without a phone.
 */
class AlertLedger(private val keptMs: Long = KEPT_MS) {

    private class Held(val notification: Int, val at: Long)

    private val held = mutableMapOf<String, Held>()

    /** Notes the notification that went out for a review. */
    fun noteReview(id: String, notification: Int, now: Long) {
        forgetOld(now)
        held[id] = Held(notification, now)
    }

    /**
     * The notification a description belongs to, if one is still in the shade.
     * It is taken out as it is handed over, so a description that turns up twice
     * rewrites the notification once.
     */
    fun take(review: FrigateReview, now: Long): Int? {
        forgetOld(now)
        return held.remove(review.id)?.notification
    }

    /** Drops what is old enough that no description can still be coming for it. */
    private fun forgetOld(now: Long) {
        held.entries.removeAll { now - it.value.at > keptMs }
    }

    companion object {
        /** How late a description can be and still find its notification. */
        const val KEPT_MS = 5 * 60_000L
    }
}
