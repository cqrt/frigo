package nz.mckenzie.frigo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * What the app knows about a newer Frigo, for the whole app rather than one page.
 *
 * The Settings page used to ask GitHub itself each time it opened, which left every
 * other screen with no way of knowing a release existed. This holds the answer
 * instead: the Settings tab reads [waiting] for its dot, and the Updates card reads
 * [release] for what to offer.
 *
 * An answer is kept on the phone and stands for an hour, so opening the app ten times
 * in a morning is one ask. GitHub counts anonymous asks by the hour, and 60 of them
 * is the whole allowance for every caller on the same address.
 */
class UpdateWatch(
    private val updater: Updater,
    private val prefs: ServerPrefs,
    /** The version on this phone, which is what a release is compared against. */
    private val current: String,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _release = MutableStateFlow<Update?>(null)

    /** The newest release GitHub last named, or null when there is nothing to say. */
    val release: StateFlow<Update?> = _release.asStateFlow()

    private val _waiting = MutableStateFlow(false)

    /**
     * Whether a newer version is waiting to be noticed. This is the dot on the
     * Settings tab: true from a release later than both this app and the last one
     * whose dot was cleared.
     */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private val _looking = MutableStateFlow(false)

    /** Whether GitHub is being asked right now, so the page can say so. */
    val looking: StateFlow<Boolean> = _looking.asStateFlow()

    private val _answered = MutableStateFlow(false)

    /**
     * Whether GitHub has ever answered on this phone, newer release or not. A fresh
     * install and an older release both have nothing to show, and the page says
     * different things about them.
     */
    val answered: StateFlow<Boolean> = _answered.asStateFlow()

    private var checkedAt = 0L
    private var seen: String? = null
    private var loaded = false

    /** One ask at a time, so a return to the app and a tap on Check again is one request. */
    private val asking = Mutex()

    /** Reads what the phone kept from last time. Called before anything is decided. */
    suspend fun load() {
        if (loaded) return
        asking.withLock {
            if (loaded) return@withLock
            val answer = prefs.readUpdateAnswer()
            checkedAt = answer.checkedAt
            seen = prefs.readSeenVersion()
            _release.value = answer.update
            _answered.value = answer.checkedAt > 0
            _waiting.value = updateWaiting(answer.update, current, seen)
            loaded = true
        }
    }

    /**
     * Asks GitHub only when the answer on the phone has gone stale, which is what
     * opening the app and coming back to it do. A failure changes nothing: an old
     * answer is worth more than no dot, and silence is not news.
     */
    suspend fun lookIfStale() {
        load()
        if (!answerStale(checkedAt, now())) return
        runCatching { look() }
    }

    /**
     * Asks GitHub whatever the answer's age, which is what the button that says
     * Check again means. Throws when GitHub can't be reached, so the page can say so.
     */
    suspend fun look() {
        if (!loaded) load()
        asking.withLock {
            _looking.value = true
            try {
                val found = updater.latest()
                checkedAt = now()
                _release.value = found
                _answered.value = true
                prefs.writeUpdateAnswer(StoredAnswer(checkedAt = checkedAt, update = found))
                _waiting.value = updateWaiting(found, current, seen)
            } finally {
                _looking.value = false
            }
        }
    }

    /**
     * Clears the dot for whatever has been found. Opening Settings does this, because
     * the dot means "there is something waiting" and you are now looking at it.
     *
     * A release later than this one brings the dot back, so a version you have seen
     * does not go quiet about the next one.
     */
    suspend fun markSeen() {
        load()
        asking.withLock {
            val found = _release.value ?: return@withLock
            if (!found.isNewerThan(current)) return@withLock
            val lastSeen = seen
            if (lastSeen != null && compareVersions(found.version, lastSeen) <= 0) return@withLock
            seen = found.version
            prefs.writeSeenVersion(found.version)
            _waiting.value = updateWaiting(found, current, seen)
        }
    }

    /** Fetches a release's APK, reporting how much has arrived as it goes. */
    suspend fun download(update: Update, onProgress: suspend (Long, Long) -> Unit): File =
        updater.download(update, onProgress)

    /** A downloaded APK for this version, if one is still on the phone. */
    fun downloaded(version: String): File? = updater.downloaded(version)
}
