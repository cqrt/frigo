package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable

/**
 * This phone's own list of the server's cameras: which ones it leaves out, what it
 * calls them, and the order they appear in.
 *
 * It also holds the light in Home Assistant that goes with a camera. Frigate has
 * nothing to say about a floodlight, so which light belongs to which camera is the
 * same kind of choice as what the camera is called: this phone's, about this server.
 *
 * It belongs to the phone rather than to the server. Turning a camera off here takes
 * it off this phone's screens and stops it buzzing here. The server is untouched: it
 * still records, and every other phone is told what it was always told.
 *
 * A camera is named by the server's key for it, so a camera hidden by name that the
 * server later renames comes back into view rather than staying hidden for ever.
 *
 * There is no Android here, so the list can be tried without a phone.
 */
@Serializable
data class CameraList(
    /** The cameras turned off on this phone. */
    val hidden: Set<String> = emptySet(),
    /** What this phone calls a camera, by the server's name for it. */
    val names: Map<String, String> = emptyMap(),
    /** The order picked on the manage list, first to last. */
    val order: List<String> = emptyList(),
    /**
     * The light in Home Assistant that goes with a camera, by the server's name for
     * it. Frigate knows nothing about a floodlight, so the light is picked here.
     */
    val lights: Map<String, String> = emptyMap(),
) {

    /** What this phone calls a camera: its own name if it has one, otherwise the server's. */
    fun name(camera: String, server: String): String =
        names[camera]?.takeIf { it.isNotBlank() } ?: server

    /** The light set for this camera, or null when it has none. */
    fun light(camera: String): String? = lights[camera]?.takeIf { it.isNotBlank() }

    /** The same list with this camera's light. A blank one goes back to none. */
    fun withLight(camera: String, entity: String?): CameraList {
        val trimmed = entity?.trim().orEmpty()
        return copy(lights = if (trimmed.isEmpty()) lights - camera else lights + (camera to trimmed))
    }

    /** Whether this camera is turned off on this phone. */
    fun hides(camera: String): Boolean = camera in hidden

    /** Named and in your order, with the ones turned off left out. This is what the screens draw. */
    fun shown(cameras: List<Camera>): List<Camera> = ordered(cameras).filterNot { hides(it.name) }

    /** The same with the ones turned off still in. The manage list and a camera's own page. */
    fun all(cameras: List<Camera>): List<Camera> = ordered(cameras)

    /** The same list with one camera turned off, or back on. */
    fun withHidden(camera: String, on: Boolean): CameraList =
        copy(hidden = if (on) hidden + camera else hidden - camera)

    /** The same list with this phone's name for a camera. A blank name goes back to the server's. */
    fun naming(camera: String, name: String): CameraList {
        val trimmed = name.trim()
        return copy(names = if (trimmed.isEmpty()) names - camera else names + (camera to trimmed))
    }

    /**
     * The same list with [camera] moved to [to].
     *
     * [among] is the order the rows were in when the drag started, because the order
     * this list keeps may not name every camera yet: one the server added since the
     * last drag sits in the alphabetical tail, and dropping a row has to place it
     * against the rows actually on screen.
     */
    fun moving(camera: String, to: Int, among: List<String> = order): CameraList {
        val kept = (among.ifEmpty { order }).distinct().filterNot { it == camera }
        val at = to.coerceIn(0, kept.size)
        return copy(order = kept.take(at) + camera + kept.drop(at))
    }

    /** Named, and in your order: the ones you placed first, then the rest by name. */
    private fun ordered(cameras: List<Camera>): List<Camera> {
        val placed = order.withIndex().associate { (at, camera) -> camera to at }
        val yours = cameras.filter { it.name in placed }.sortedBy { placed.getValue(it.name) }
        val rest = cameras.filterNot { it.name in placed }.sortedBy { it.title.lowercase() }
        return (yours + rest).map { Camera(name = it.name, title = name(it.name, it.title)) }
    }
}
