package nz.mckenzie.frigo.alerts

import nz.mckenzie.frigo.data.FrigateReview

/**
 * Which notification in the shade belongs to which thing your server saw.
 *
 * The description of a review arrives a few seconds after the buzz went out, so
 * the two have to be joined back up to rewrite the notification already there
 * rather than post a second one. Either path can put the buzz out: an event, or
 * the review itself. Frigate lists the event ids on the review, which is the
 * thread to pull when the buzz came from the event.
 *
 * This has no Android in it, so the joining can be tried without a phone.
 */
class AlertLedger(private val keptMs: Long = KEPT_MS) {

    private class Held(val notification: Int, val at: Long)

    private val held = mutableMapOf<String, Held>()

    /** Notes the notification that went out for one of the server's events. */
    fun noteEvent(id: String, notification: Int, now: Long) {
        note(key(Event, id), notification, now)
    }

    /** Notes the notification that went out for a review. */
    fun noteReview(id: String, notification: Int, now: Long) {
        note(key(Review, id), notification, now)
    }

    /**
     * The notification a description belongs to, if one is still in the shade.
     * It is taken out as it is handed over, so a description that turns up twice
     * rewrites the notification once.
     */
    fun take(review: FrigateReview, now: Long): Int? {
        forgetOld(now)

        val keys = listOf(key(Review, review.id)) + review.detections.map { key(Event, it) }
        val found = keys.firstNotNullOfOrNull { held[it]?.notification } ?: return null

        keys.forEach { held.remove(it) }
        return found
    }

    private fun note(key: String, notification: Int, now: Long) {
        forgetOld(now)
        held[key] = Held(notification, now)
    }

    /** Drops what is old enough that no description can still be coming for it. */
    private fun forgetOld(now: Long) {
        held.entries.removeAll { now - it.value.at > keptMs }
    }

    private fun key(kind: String, id: String) = "$kind:$id"

    companion object {
        /** How late a description can be and still find its notification. */
        const val KEPT_MS = 5 * 60_000L

        private const val Event = "event"
        private const val Review = "review"
    }
}
