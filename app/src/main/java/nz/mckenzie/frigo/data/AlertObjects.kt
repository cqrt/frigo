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
 * One list per camera. [labels] is the list every camera follows, and a camera
 * with [cameras] entry of its own follows that instead. Either one being empty
 * means everything the server sends for it, which is how the app behaves without
 * a list at all.
 *
 * There is no Android here, so the rule can be tried without a phone.
 */
@Serializable
data class AlertObjects(
    /** The list every camera follows unless it has one of its own. */
    val labels: Set<String> = emptySet(),
    /** Lists of their own, by the server's name for the camera. */
    val cameras: Map<String, Set<String>> = emptyMap(),
) {

    /** True when nothing is held back anywhere: every camera gets everything. */
    val everything: Boolean get() = labels.isEmpty() && cameras.values.all { it.isEmpty() }

    /**
     * What this camera lets through. A camera with no list of its own follows the
     * shared one, and an empty list is everything.
     */
    fun labelsFor(camera: String): Set<String> = cameras[camera] ?: labels

    /** Whether this camera seeing this object may reach you. */
    fun lets(camera: String, label: String): Boolean {
        val list = labelsFor(camera)
        return list.isEmpty() || label in list
    }

    /** The same with a list written for one camera. */
    fun withLabels(camera: String, chosen: Set<String>): AlertObjects =
        copy(cameras = cameras + (camera to chosen))

    /**
     * The same with one camera's list written, and every object it can alert on
     * written as no list at all: ticking all of them is what "everything" means.
     */
    fun withChosen(camera: String, chosen: Set<String>, offered: Set<String>): AlertObjects =
        withLabels(camera, if (chosen.containsAll(offered)) emptySet() else chosen)
}

/**
 * What the phone's object picker offers: the cameras this account may see, and the
 * objects each one can make an alert from.
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
