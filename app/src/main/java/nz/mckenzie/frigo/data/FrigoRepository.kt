package nz.mckenzie.frigo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
import java.util.Locale

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

    /** The last answer on what each camera can give, and on what they are all doing. */
    private val limits = mutableMapOf<String, Pair<Long, StreamLimits>>()
    private var statsAt: Pair<Long, FrigateStats>? = null

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    private val _alerts = MutableStateFlow(ServerPrefs.Alerts())
    val alerts: StateFlow<ServerPrefs.Alerts> = _alerts.asStateFlow()

    /**
     * Whether the alert choices have been read off the phone yet. The service
     * starts by itself after a restart, before any screen has run, and it needs
     * the saved choices rather than a blank slate.
     */
    @Volatile private var alertsLoaded = false

    /**
     * Reads what should set your phone off, once. Safe to call from anywhere;
     * the second caller gets the choices already in memory.
     */
    suspend fun loadAlerts() {
        if (alertsLoaded) return
        _alerts.value = prefs.readAlerts()
        alertsLoaded = true
    }

    /** Called once on launch. Keeps you signed in if the token still works. */
    suspend fun restore() {
        loadAlerts()
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

    suspend fun events(
        camera: String? = null,
        labels: List<String> = emptyList(),
        subLabels: List<String> = emptyList(),
        limit: Int = 30,
        after: Double? = null,
        before: Double? = null,
    ): List<FrigateEvent> = authorized { baseUrl, token ->
        api.events(
            baseUrl,
            token,
            camera = camera,
            labels = labels,
            subLabels = subLabels,
            limit = limit,
            after = after,
            before = before,
        )
    }

    /** The moments Frigate thought were worth a look, newest first. */
    suspend fun reviews(
        severity: String,
        limit: Int = 30,
        before: Double? = null,
        showReviewed: Boolean = false,
    ): List<FrigateReview> = authorized { baseUrl, token ->
        api.reviews(
            baseUrl,
            token,
            severity = severity,
            limit = limit,
            before = before,
            showReviewed = showReviewed,
        )
    }

    /** The moments Frigate flagged on one camera over a stretch of the day. */
    suspend fun reviewSpikes(camera: String, after: Double, before: Double): List<FrigateReview> =
        authorized { baseUrl, token -> api.reviewSpikes(baseUrl, token, camera, after, before) }

    /**
     * Says a review has been watched. Frigate keeps that on the server, so the
     * web page drops it from its list too.
     */
    suspend fun markReviewReviewed(reviewId: String) {
        authorized { baseUrl, token -> api.markReviewed(baseUrl, token, reviewId) }
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

    /** The names Frigate can recognise, as a sub label on the events it records. */
    suspend fun subLabels(): List<String> = runCatching {
        authorized { baseUrl, token -> api.subLabels(baseUrl, token) }
    }.getOrDefault(emptyList()).distinct().sorted()

    suspend fun setAlerts(next: ServerPrefs.Alerts) {
        prefs.writeAlerts(next)
        _alerts.value = next
        alertsLoaded = true
    }

    /** Whether the reviews list shows the ones you've already watched. */
    suspend fun readShowReviewed(): Boolean = prefs.readShowReviewed()

    suspend fun setShowReviewed(on: Boolean) = prefs.writeShowReviewed(on)

    /** Which way the cameras are laid out, remembered between launches. */
    suspend fun readCameraLayout(): Int = prefs.readCameraLayout()

    suspend fun setCameraLayout(layout: Int) = prefs.writeCameraLayout(layout)

    /** Whether the home page tiles play live. A flow, so Settings is felt at once. */
    val liveTiles: Flow<Boolean> get() = prefs.liveTiles

    suspend fun setLiveTiles(on: Boolean) = prefs.writeLiveTiles(on)

    /** Which colours the app uses. A flow, so Settings changes it at once. */
    val theme: Flow<Int> get() = prefs.theme

    suspend fun setTheme(mode: Int) = prefs.writeTheme(mode)

    /**
     * The picture size and frame rate this camera was last watched at, or null the
     * first time it is opened.
     */
    suspend fun streamChoice(camera: String): Pair<Int, Int>? = prefs.readStreamChoice(camera)

    suspend fun setStreamChoice(camera: String, height: Int, rate: Int) =
        prefs.writeStreamChoice(camera, height, rate)

    suspend fun latestFrame(camera: String, height: Int = 360): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.latestFrame(baseUrl, token, camera, height) }
    }.getOrNull()

    /**
     * What this camera can actually give: the size of the picture it sends, and the
     * frames a second it sends them at.
     *
     * A saved choice is only worth honouring inside these. Asking for more is what
     * makes the server scale frames up and the phone fall behind, and the bill is paid
     * by every camera on the box.
     *
     * The answer is kept for a while, because opening a camera should not cost two
     * round trips, and a camera does not change size between one tap and the next.
     */
    suspend fun streamLimits(camera: String): StreamLimits {
        val now = System.currentTimeMillis()
        limits[camera]?.let { (at, found) ->
            if (now - at < LimitsStandMillis) return found
        }

        // Both answers are wanted and neither needs the other, so they are asked for
        // at the same time rather than one after the other.
        val (picture, sending) = coroutineScope {
            val height = async { nativeHeight(camera) }
            val stats = async { stats() }
            height.await() to stats.await()
        }

        val found = StreamLimits(
            height = (picture ?: StreamLimits.MostHeight)
                .coerceAtMost(StreamLimits.MostHeight),
            rate = (sending?.cameras?.get(camera)?.settingRate ?: StreamLimits.MostRate)
                .coerceIn(StreamLimits.LeastRate, StreamLimits.MostRate),
        )
        limits[camera] = now to found
        return found
    }

    /**
     * The height of the picture the camera itself sends, or null when it cannot be
     * asked. The picture is read for its header alone, so this costs one small
     * response rather than a bitmap.
     */
    private suspend fun nativeHeight(camera: String): Int? = runCatching {
        val frame = authorized { baseUrl, token ->
            api.latestFrame(baseUrl, token, camera, height = null)
        }
        MjpegReader.sizeOf(frame).takeIf { it > 0 }
    }.getOrNull()

    /**
     * The server's own census of every camera, kept for a few seconds. The live screen
     * asks it whether its camera is still sending, and asks it again for the rate the
     * camera sends at, which is one answer worth having once rather than twice.
     */
    private suspend fun stats(): FrigateStats? {
        val now = System.currentTimeMillis()
        statsAt?.let { (at, found) -> if (now - at < StatsStandMillis) return found }
        val found = runCatching {
            authorized { baseUrl, token -> api.stats(baseUrl, token) }
        }.getOrNull()
        if (found != null) statsAt = now to found
        return found
    }

    /**
     * Whether a camera is sending frames at all.
     *
     * Frigate counts the frames arriving from each camera over the last few seconds,
     * so a camera whose picture has stopped counts zero. The stream goes on handing
     * back the last frame it sent, which is why this is asked separately: a stale
     * picture on screen looks exactly like a live one.
     *
     * Null means no answer either way, which is what the screens show as nothing
     * unusual: the server has only just started, it has no such camera, or the call
     * failed. Calling a camera offline on a guess is worse than saying nothing.
     */
    suspend fun cameraSending(camera: String): Boolean? {
        val answer = stats() ?: return null
        // For the first couple of minutes Frigate has not counted anything yet, and
        // every camera on a fresh server would read as stopped.
        if (answer.service.uptime?.let { it < WarmUpSeconds } == true) return null
        return answer.cameras[camera]?.let { it.cameraFps > 0.0 }
    }

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
     * The address of a camera's sound and the token to send with it.
     *
     * Frigate's API carries no sound at all, so this goes to go2rtc through the
     * proxy, under a path of its own. It is a stream rather than a file: it runs
     * until it is closed.
     */
    fun soundSource(camera: String): ClipSource {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return ClipSource(url = "${current.baseUrl}/api/stream.aac?src=$camera", token = token)
    }

    /**
     * The address of a camera's own video and the token to send with it.
     *
     * This is the camera's own H.264, left as the camera sends it, coming from
     * go2rtc through the same proxy path the sound uses. It carries the sound with
     * it. Frigate's own stream is one frame per camera, kept at the size it decodes
     * for detection, so this is the only way to see what the camera actually sees.
     */
    fun liveSource(camera: String): ClipSource {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return ClipSource(url = "${current.baseUrl}/api/stream.mp4?src=$camera", token = token)
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
     * A stretch of a camera's recordings, cut by Frigate as it sends it. The
     * moments are epoch seconds, which is what its own timeline works in, and they
     * are sent to the millisecond: which frame a cut begins on turns on a fraction
     * of a second.
     */
    fun recordingClipSource(camera: String, start: Double, end: Double): ClipSource {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.token ?: throw FrigateException(401, "Not signed in")
        return ClipSource(
            url = "${current.baseUrl}/api/$camera/start/${moment(start)}/end/${moment(end)}/clip.mp4",
            token = token,
        )
    }

    /** A moment written the way a URL is read: digits, then a dot. */
    private fun moment(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds)

    /** Which days this camera kept recordings for. */
    suspend fun recordingDays(camera: String, timezone: String): List<RecordingDay> =
        authorized { baseUrl, token -> api.recordingDays(baseUrl, token, camera, timezone) }

    /** The stretches of recording between two moments, gaps and all. */
    suspend fun recordingSpans(camera: String, after: Double, before: Double): List<RecordingSpan> =
        authorized { baseUrl, token ->
            api.recordingSpans(baseUrl, token, camera, after, before)
        }

    /** A picture of the recording at that moment, for while the clip arrives. */
    suspend fun recordingStill(camera: String, moment: Double, height: Int = 720): ByteArray? =
        runCatching {
            authorized { baseUrl, token -> api.recordingStill(baseUrl, token, camera, moment, height) }
        }.getOrNull()

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

    /**
     * Clips are the biggest thing the app keeps. Yesterday's go, because nothing
     * older can be played from the screen, and past a limit the oldest of today's
     * go too: a run through an hour at 16× fetches thirty of them.
     */
    private fun forgetOldClips() {
        val files = clipDir.listFiles()?.filter { it.isFile } ?: return
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        files.filter { it.lastModified() < yesterday }.forEach { it.delete() }

        var room = files.filter { it.exists() }.sumOf { it.length() }
        files.filter { it.exists() }.sortedBy { it.lastModified() }.forEach { file ->
            if (room > ClipsBytes) {
                room -= file.length()
                file.delete()
            }
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

        /** How much of the phone the downloaded clips may take: 600 MB. */
        private const val ClipsBytes = 600L * 1024 * 1024

        /** Seconds after a server starts during which its frame counts mean nothing. */
        private const val WarmUpSeconds = 120.0

        /**
         * How long an answer about a camera's own size and rate stands. Long, because
         * a camera is not swapped between one tap and the next, and asking again on
         * every camera change would put two requests in front of the picture.
         */
        private const val LimitsStandMillis = 30L * 60 * 1000

        /** How long the server's census stands. Two calls in a row share one answer. */
        private const val StatsStandMillis = 15_000L

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
