package nz.mckenzie.frigo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this launch of the app is still behind the cover.
 *
 * A process starts locked, and nothing but the phone's own prompt opens it.
 * Coming back to the app from another one leaves it open; closing it from the
 * recent apps list locks it again, and so does a cold start. That is the whole
 * rule, which is why this hangs off the container: the container is made once,
 * when the process is, and a close is reported from the activity.
 *
 * A turn of the phone does not reach it at all, because the manifest hands that
 * to the activity rather than rebuilding it.
 */
class AppLock {

    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Called by the cover, once the phone has said who it is. */
    fun unlock() {
        _locked.value = false
    }

    /**
     * Called when the app is closed rather than left, so the next open asks.
     *
     * The process outlives a swipe out of the recent apps list for a while, which
     * is why the close has to be said rather than waited for.
     */
    fun relock() {
        _locked.value = true
    }
}
