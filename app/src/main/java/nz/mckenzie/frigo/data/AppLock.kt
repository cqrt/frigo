package nz.mckenzie.frigo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this launch of the app is still behind the cover.
 *
 * A process starts locked, and nothing but the phone's own prompt opens it.
 * Coming back to the app from another one is the same process, so it stays
 * open; a cold start is a new one, so it is locked again. That is the whole
 * rule, which is why this hangs off the container: the container is made once,
 * when the process is.
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
}
