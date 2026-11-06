package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable

/**
 * The objects this phone will let buzz, kept as a list of its own.
 *
 * What makes an alert is Frigate's: a camera's notifications switch, its alerts
 * switch, and the objects listed on its alerts page. A non-admin can't change
 * any of that, because the server turns the save down. So the phone keeps a short
 * list and uses it to narrow what the server already sends. It never writes to
 * the server, and it can only take an alert away.
 *
 * One list for every camera. Empty means everything the server sends, which is
 * how the app behaves without a list at all.
 *
 * There is no Android here, so the rule can be tried without a phone.
 */
@Serializable
data class AlertObjects(val labels: Set<String> = emptySet()) {

    /** True when the server's own answer stands: every object it sends. */
    val everything: Boolean get() = labels.isEmpty()

    /** Whether this object may reach you. An empty list lets everything through. */
    fun lets(label: String): Boolean = labels.isEmpty() || label in labels
}
