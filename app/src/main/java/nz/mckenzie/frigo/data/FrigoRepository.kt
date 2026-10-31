package nz.mckenzie.frigo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.File
import java.io.IOException

/** What the app is showing: a spinner, the login form, or your cameras. */
sealed interface Gate {
    data object Loading : Gate

    /**
     * The login form. The address and username are filled in when they are still
     * known, which is the usual case when a session has expired.
     */
    data class SignedOut(
        val message: String? = null,
        val address: String = "",
        val username: String = "",
    ) : Gate

    data class SignedIn(val baseUrl: String, val username: String) : Gate
}

/**
 * Where an event's clip is, and the token the player has to send with it. The
 * player fetches the clip itself, so it needs both rather than a call to make.
 */
data class ClipSource(val url: String, val token: String)

/**
 * Frigate's message feed, and the token it needs. The app stays connected to it
 * while it is watching for alerts, and each new event arrives as a message
 * rather than being asked for.
 */
data class StreamSource(val url: String, val token: String)

/**
 * Everything that talks to Frigate. It holds the session token and hands the
 * screens plain data.
 *
 * Frigate 0.18 has no refresh endpoint: the sign-in issues one JWT that lasts a
 * day, and the server lengthens it whenever a request arrives near the end of
 * that day. So the token is kept and updated when the server hands back a fresh
 * one, and when it finally does expire you sign in again.
 */
class FrigoRepository(
    private val api: FrigateApi,
    private val prefs: ServerPrefs,
    /** Where fetched clips are kept, so watching one twice costs one download. */
    cacheDir: File,
) {

    private val clipDir = File(cacheDir, "clips")

    private val mutex = Mutex()
    @Volatile private var stored: ServerPrefs.Stored? = null

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    private val _alerts = MutableStateFlow(ServerPrefs.Alerts())
    val alerts: StateFlow<ServerPrefs.Alerts> = _alerts.asStateFlow()

    /** Called once on launch. Keeps you signed in if the token still works. */
    suspend fun restore() {
        _alerts.value = prefs.readAlerts()
        val saved = prefs.read()
        if (saved == null || saved.token.isNullOrBlank()) {
            _gate.value = Gate.SignedOut(
                address = saved?.baseUrl.orEmpty(),
                username = saved?.username.orEmpty(),
            )
            return
        }
        stored = saved

        val result = runCatching {
            withContext(Dispatchers.IO) { api.checkToken(saved.baseUrl, saved.token) }
        }

        result.onSuccess { fresh ->
            if (fresh != null) saveToken(fresh)
            _gate.value = Gate.SignedIn(saved.baseUrl, saved.username)
        }.onFailure { error ->
            // A server that cannot be reached is not a sign-out: the phone may be
            // out of signal, and the screens show their own errors for that.
            if (error is FrigateException && error.isAuthFailure) {
                forgetToken()
                _gate.value = Gate.SignedOut(
                    message = "Your session has expired. Sign in again.",
                    address = saved.baseUrl,
                    username = saved.username,
                )
            } else {
                _gate.value = Gate.SignedIn(saved.baseUrl, saved.username)
            }
        }
    }

    suspend fun signIn(address: String, username: String, password: String) {
        val baseUrl = normalizeBaseUrl(address)
        val token = withContext(Dispatchers.IO) { api.login(baseUrl, username, password) }
        val saved = ServerPrefs.Stored(baseUrl = baseUrl, username = username, token = token)
        stored = saved
        prefs.write(saved)
        _gate.value = Gate.SignedIn(baseUrl, username)
    }

    suspend fun signOut() {
        mutex.withLock {
            stored = null
            prefs.clear()
        }
        _gate.value = Gate.SignedOut()
    }

    /**
     * The cameras to show, and only the ones this user may watch.
     *
     * `/api/config` lists every camera on the server no matter who asks, so a
     * restricted user would be shown cameras that answer 403 when the app asks
     * for a picture. `/api/profile` carries the list the role allows, and that
     * is what filters this. A user who may see everything gets no list, which
     * means all of them.
     */
    suspend fun cameras(): List<Camera> = authorized { baseUrl, token ->
        val allowed = api.profile(baseUrl, token).allowedCameras
        api.config(baseUrl, token).cameras
            .filterValues { it.enabled }
            .filter { (name, _) -> allowed == null || name in allowed }
            .map { (name, config) ->
                Camera(name = name, title = config.friendlyName ?: prettyCameraName(name))
            }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun events(camera: String? = null, limit: Int = 30, before: Double? = null): List<FrigateEvent> =
        authorized { baseUrl, token ->
            api.events(baseUrl, token, camera = camera, labels = null, limit = limit, before = before)
        }

    /** The moments Frigate thought were worth a look, newest first. */
    suspend fun reviews(
        severity: String,
        limit: Int = 30,
        before: Double? = null,
    ): List<FrigateReview> = authorized { baseUrl, token ->
        api.reviews(baseUrl, token, severity = severity, limit = limit, before = before)
    }

    /**
     * The picture for a review. Frigate keeps a thumbnail for the review itself,
     * but that file sits behind the web server rather than the API, so this uses
     * the thumbnail of the first thing it saw instead, which is the same picture.
     */
    suspend fun reviewThumbnail(review: FrigateReview): ByteArray? =
        review.detections.firstOrNull()?.let { thumbnail(it) }

    /** The objects this server has been set up to look for, which is what you choose from. */
    suspend fun labels(): List<String> = authorized { baseUrl, token ->
        // Lower case, the way Frigate names them on an event. The settings screen
        // capitalises them for display.
        api.labels(baseUrl, token).distinct().sorted()
    }

    suspend fun setAlerts(next: ServerPrefs.Alerts) {
        prefs.writeAlerts(next)
        _alerts.value = next
    }

    suspend fun latestFrame(camera: String, height: Int = 360): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.latestFrame(baseUrl, token, camera, height) }
    }.getOrNull()

    suspend fun thumbnail(eventId: String): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.eventThumbnail(baseUrl, token, eventId) }
    }.getOrNull()

    suspend fun snapshot(eventId: String): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.eventSnapshot(baseUrl, token, eventId) }
    }.getOrNull()

    /** The MJPEG feed. The caller closes the response when it stops watching. */
    suspend fun openMjpeg(camera: String, height: Int, fps: Int): Response =
        authorized { baseUrl, token -> api.openMjpeg(baseUrl, token, camera, height, fps) }

    /**
     * The address of an event's clip and the token it needs. Frigate cuts the clip
     * out of your recordings on request, so it is fetched by the player rather than
     * downloaded here.
     */
    fun clipSource(eventId: String): ClipSource {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return ClipSource(url = "${current.baseUrl}/api/events/$eventId/clip.mp4", token = token)
    }

    /**
     * A review's clip. Frigate cuts it from your recordings when it is asked for
     * one, so the period it covers is the review's start and end.
     */
    fun reviewClipSource(reviewId: String): ClipSource {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return ClipSource(url = "${current.baseUrl}/api/review/$reviewId/clip.mp4", token = token)
    }

    /**
     * The clip as a file on the phone, fetched if it isn't already there.
     *
     * Frigate cuts a clip while it sends it, with no length and no support for
     * ranges, and a phone on a home connection can see it arrive in dribs. A
     * player reading that stream directly calls it stuck after ten seconds
     * without progress and gives up. Copied to a file first, the player has the
     * whole clip: it starts at once, it can be scrubbed, and a bad minute on the
     * network costs a retry rather than the clip.
     */
    suspend fun clipFile(
        source: ClipSource,
        key: String,
        onProgress: (Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val name = key.replace(NOT_IN_A_FILENAME, "_") + ".mp4"
        val clip = File(clipDir, name)
        if (clip.isFile && clip.length() > 0) {
            // A clip kept from an earlier version, or one that arrived sound,
            // gets the same check. It costs a few milliseconds and does nothing
            // at all to a file that is already right.
            ClipRepair.repair(clip)
            return@withContext clip
        }

        clipDir.mkdirs()
        forgetOldClips()

        val part = File(clipDir, "$name.part")
        val job = coroutineContext[Job]
        try {
            api.downloadClip(source, part, onProgress) { job?.isActive != false }
            // The clip arrives with the recordings' timing, which can leave a
            // player holding the first frame for hours. Straightening it costs a
            // moment and changes nothing on a clip that is already sound.
            if (ClipRepair.repair(part)) android.util.Log.i("Frigo", "clip timing repaired")
            if (!part.renameTo(clip)) throw IOException("The clip couldn't be moved into place")
            clip
        } catch (failure: Throwable) {
            part.delete()
            clip.delete()
            throw failure
        }
    }

    /** Yesterday's clips are not worth the room. */
    private fun forgetOldClips() {
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        clipDir.listFiles()?.forEach { file ->
            if (file.lastModified() < yesterday) file.delete()
        }
    }

    /**
     * Frigate's message feed, for the service that watches for alerts. Null when
     * nobody is signed in, which is also when there is nothing to watch.
     *
     * The saved sign-in is read here when this process has not loaded it yet. The
     * service starts on its own after a phone restart, before any screen has run,
     * and without this it would find nothing and give up.
     */
    suspend fun streamSource(): StreamSource? {
        val current = stored ?: prefs.read()?.also { stored = it } ?: return null
        val token = current.token ?: return null
        return StreamSource(url = "${current.baseUrl}/ws", token = token)
    }

    /**
     * Runs [block] with the session token. A 401 means the token aged out, so we
     * ask the server whether it has a fresh one before giving up on the session.
     */
    private suspend fun <T> authorized(block: suspend (baseUrl: String, token: String) -> T): T {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return try {
            withContext(Dispatchers.IO) { block(current.baseUrl, token) }
        } catch (e: FrigateException) {
            if (!e.isAuthFailure) throw e
            val renewed = renewSession()
            withContext(Dispatchers.IO) { block(current.baseUrl, renewed) }
        }
    }

    /**
     * Frigate refreshes a session in place rather than issuing a new one, so the
     * only recovery is asking /api/auth and keeping whatever token comes back.
     */
    private suspend fun renewSession(): String = mutex.withLock {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")

        val fresh = runCatching {
            withContext(Dispatchers.IO) { api.checkToken(current.baseUrl, token) }
        }.getOrElse {
            forgetToken()
            _gate.value = Gate.SignedOut(
                message = "Your session has expired. Sign in again.",
                address = current.baseUrl,
                username = current.username,
            )
            throw FrigateException(401, "Your session has expired. Sign in again.")
        }

        if (fresh != null && fresh != token) saveToken(fresh)
        fresh ?: token
    }

    private suspend fun saveToken(token: String) {
        val current = stored ?: return
        val next = current.copy(token = token)
        stored = next
        prefs.write(next)
    }

    /** Keeps the address and username, drops the token that no longer works. */
    private suspend fun forgetToken() {
        val current = stored ?: return
        val next = current.copy(token = null)
        stored = next
        prefs.write(next)
    }

    companion object {
        /** Clips are named after the review or event they belong to. */
        private val NOT_IN_A_FILENAME = Regex("[^A-Za-z0-9._-]")

        /**
         * Takes what people actually type - `frigate.example.com`, a full URL, or
         * one that already ends in /api - and returns a clean base.
         */
        fun normalizeBaseUrl(input: String): String {
            var value = input.trim()
            if (value.isEmpty()) throw FrigateException(0, "Enter your server address")
            if (!value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
            ) {
                value = "https://$value"
            }
            value = value.trimEnd('/')
            if (value.endsWith("/api", ignoreCase = true)) {
                value = value.dropLast(4).trimEnd('/')
            }
            val parsed = value.toHttpUrlOrNull()
                ?: throw FrigateException(0, "That doesn't look like a web address")
            return parsed.toString().trimEnd('/')
        }
    }
}
