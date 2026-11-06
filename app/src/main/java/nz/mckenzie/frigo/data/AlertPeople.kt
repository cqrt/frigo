package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable

/**
 * The people this phone stays quiet about, by the name Frigate knows them by.
 *
 * Frigate can't leave a name out of an alert: what makes an alert is the object and
 * the zone, and its own push sends every alert for the cameras an account may see.
 * The name Frigate's face recognition puts on a person is carried on the alert the
 * phone receives, though, so the phone can hold that one back.
 *
 * It is the phone's own list, like the objects list beside it. Your server and its
 * other phones are unchanged.
 *
 * There is no Android here, so the rule can be tried without a phone.
 */
@Serializable
data class AlertPeople(val leftOut: Set<String> = emptySet()) {

    /** True when nobody is left out, which is how the app behaves without a list. */
    val everyone: Boolean get() = leftOut.isEmpty()

    /**
     * Whether this name may reach you. Only a name on the list is held back, so a
     * person Frigate doesn't recognise still buzzes.
     */
    fun lets(name: String): Boolean = name.removeSuffix(Verified) !in leftOut

    /** True when any of the names Frigate gave this is one to leave out. */
    fun leavesOut(names: List<String>): Boolean = names.any { !lets(it) }
}

/**
 * Frigate marks a name it has itself verified with a suffix, and the app never
 * shows the mark, so a tick matches the name either way.
 */
private const val Verified = "-verified"
