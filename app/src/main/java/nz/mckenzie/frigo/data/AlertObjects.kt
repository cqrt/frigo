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

/**
 * What the phone's object picker offers: the cameras this account may see, and
 * the objects each one can make an alert from.
 *
 * The list itself is one for every camera. The cameras are here so the page can
 * narrow to one of them while you look for an object, which changes nothing
 * about what the list means.
 */
data class AlertObjectChoices(
    val cameras: List<Camera> = emptyList(),
    /** By camera name. */
    val labels: Map<String, List<String>> = emptyMap(),
) {
    /** Every object any of those cameras alerts on, which is the whole list. */
    val all: List<String>
        get() = labels.values.flatten().filter { it.isNotBlank() }.distinct().sorted()

    /** What one camera alerts on, or the whole list when no camera is picked. */
    fun forCamera(camera: String?): List<String> =
        camera?.let { labels[it].orEmpty() } ?: all
}
