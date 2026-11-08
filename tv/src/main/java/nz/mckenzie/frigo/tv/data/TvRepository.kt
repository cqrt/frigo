package nz.mckenzie.frigo.tv.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Everything the television knows: which Frigate it is signed in to, the session
 * it sends with every request, and the cameras it has been told about.
 *
 * The password is kept, so a session that has run out is signed in again here
 * rather than on the phone. Frigate's token lasts a day and there is no endpoint
 * that makes another without one, so this is the only way a television stays
 * useful across a week.
 */
class TvRepository(
    private val prefs: TvPrefs,
    private val api: TvApi,
    /** Where a clip is kept while it is watched, so a second look costs nothing. */
    private val cacheDir: File,
) {

    /** Where the server is, and the token to send. Changes when the token is renewed. */
    data class Session(val baseUrl: String, val token: String)

    /** What the screen is showing. */
    sealed interface Gate {
        data object Loading : Gate

        /** No server yet, or the pairing was cleared: the code screen. */
        data object Pairing : Gate

        data class Ready(val cameras: List<Camera>) : Gate

        /**
         * Nothing could be shown. [refused] separates a server that turned the
         * sign-in down, which needs the TV paired again, from one that is not
         * answering, which needs a moment and another try.
         */
        data class Problem(val refused: Boolean) : Gate
    }

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** One sign-in at a time: six tiles failing together should not mean six logins. */
    private val signingIn = Mutex()

    /** Called once, when the app opens. */
    suspend fun start() = withContext(Dispatchers.IO) {
        _defaultCamera.value = prefs.readDefaultCamera()
        _screen.value = prefs.readScreen()
        val server = prefs.readServer()
        if (server == null) {
            _gate.value = Gate.Pairing
            return@withContext
        }
        _gate.value = Gate.Loading
        signIn(server)
    }

    /**
     * The phone has sent the address and a sign-in. Frigate is asked before
     * anything is kept, so a wrong password is refused at the television and
     * the phone can try again. Answers null when the TV is paired, or the
     * sentence to show when it is not.
     */
    suspend fun accept(
        baseUrl: String,
        username: String,
        password: String,
    ): String? = withContext(Dispatchers.IO) {
        signingIn.withLock {
            val address = baseUrl.trim().trimEnd('/')
            try {
                val token = api.login(address, username, password)
                val cameras = api.cameras(address, token)
                prefs.writeServer(TvPrefs.Server(address, username, password, token))
                _session.value = Session(address, token)
                _gate.value = Gate.Ready(cameras)
                null
            } catch (refused: TvException) {
                refused.message
            } catch (failed: Throwable) {
                "The TV could not reach $address."
            }
        }
    }

    /**
     * Signs in again with the password it was given, for a token that has run
     * out. Answers the session, or null when the server turned it down. The
     * screen is left alone: a picture that stops is better than a screen that
     * throws away the cameras it was showing.
     */
    suspend fun renew(): Session? = withContext(Dispatchers.IO) {
        signingIn.withLock {
            val server = prefs.readServer() ?: return@withLock null
            runCatching {
                val token = api.login(server.baseUrl, server.username, server.password)
                prefs.writeServer(server.copy(token = token))
                Session(server.baseUrl, token).also { _session.value = it }
            }.getOrNull()
        }
    }

    /**
     * Asks the server for its cameras again. A camera added or turned off on the
     * server reaches the television without it being restarted. A server that
     * doesn't answer leaves the list as it was.
     */
    suspend fun refreshCameras() = withContext(Dispatchers.IO) {
        val current = _session.value ?: return@withContext
        val cameras = runCatching { api.cameras(current.baseUrl, current.token) }
            .getOrElse { failure ->
                if (failure.isAuthFailure()) {
                    val renewed = renew() ?: return@withContext
                    runCatching { api.cameras(renewed.baseUrl, renewed.token) }.getOrNull()
                } else {
                    null
                }
            } ?: return@withContext
        val gate = _gate.value
        if (gate is Gate.Ready) _gate.value = Gate.Ready(cameras)
    }

    /** Called by a stream or a tile the server has just turned down. */
    suspend fun sessionOrRenew(): Session? {
        val current = _session.value
        if (current != null) return current
        return renew()
    }

    /** The phone's pairing has been taken away, or the password no longer works. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        prefs.clear()
        _session.value = null
        _gate.value = Gate.Pairing
    }

    /** Where the big picture plays from, and the token it sends. */
    fun streamUrl(camera: String): String? {
        val current = _session.value ?: return null
        return runCatching { api.streamUrl(current.baseUrl, camera) }.getOrNull()
    }

    /**
     * One camera's small live feed, decoded, until the collector stops or the
     * feed ends. It runs on the IO dispatcher, so a tile only ever touches a
     * finished picture.
     */
    fun pictures(camera: String, height: Int, fps: Int): Flow<Bitmap> = flow {
        val current = sessionOrRenew() ?: throw TvException(0, "The TV is not signed in.")
        val response = try {
            api.openMjpeg(current.baseUrl, current.token, camera, height, fps)
        } catch (refused: TvException) {
            if (!refused.isAuthFailure()) throw refused
            val renewed = renew() ?: throw refused
            api.openMjpeg(renewed.baseUrl, renewed.token, camera, height, fps)
        }
        response.use { whole ->
            val body = whole.body ?: throw IOException("The live picture had no body.")
            val reader = MjpegReader(body.byteStream())
            while (currentCoroutineContext().isActive) {
                val frame = reader.nextFrame() ?: break
                val picture = MjpegReader.decode(frame) ?: continue
                emit(picture)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** The camera's last whole frame, which stands in until the video arrives. */
    suspend fun still(camera: String, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        val current = sessionOrRenew() ?: return@withContext null
        runCatching { MjpegReader.decode(api.latestJpeg(current.baseUrl, current.token, camera, height)) }
            .getOrNull()
    }

    // --- alerts -----------------------------------------------------------

    private val _unseenAlerts = MutableStateFlow(false)

    /** Whether something has been flagged since the alerts page was last opened. */
    val unseenAlerts: StateFlow<Boolean> = _unseenAlerts.asStateFlow()

    /**
     * The alerts of the last two days that nobody has watched, newest first,
     * with each camera's name as this television shows it.
     */
    suspend fun alerts(): List<Alert> = withContext(Dispatchers.IO) {
        val current = sessionOrRenew() ?: throw TvException(0, "The TV is not signed in.")
        val since = System.currentTimeMillis() / 1_000 - TWO_DAYS
        val titles = camerasByName()
        api.alerts(current.baseUrl, current.token, since).map { it.named(titles) }
    }

    /**
     * Alerts that started after [after], for the picture that takes over when
     * one is flagged. A server that does not answer answers with nothing: this
     * runs quietly behind a screen that has other things to do.
     */
    suspend fun alertTakeovers(after: Long): List<Alert> = withContext(Dispatchers.IO) {
        val current = sessionOrRenew() ?: return@withContext emptyList()
        val titles = camerasByName()
        runCatching { api.alerts(current.baseUrl, current.token, after, limit = 10) }
            .getOrDefault(emptyList())
            .map { it.named(titles) }
    }

    private fun Alert.named(titles: Map<String, String>): Alert =
        copy(camera = titles[cameraName] ?: cameraName)

    /** Whether a person or a car brings its camera up. */
    val takeover: Flow<Boolean> = prefs.takeover

    suspend fun setTakeover(on: Boolean) = prefs.writeTakeover(on)

    private val _defaultCamera = MutableStateFlow<String?>(null)

    /** The camera the television opens on, or null for the first in the list. */
    val defaultCamera: StateFlow<String?> = _defaultCamera.asStateFlow()

    suspend fun setDefaultCamera(name: String?) {
        prefs.writeDefaultCamera(name)
        _defaultCamera.value = name
    }

    /** Whether the live picture's sound is on. */
    val liveSound: Flow<Boolean> = prefs.liveSound

    suspend fun setLiveSound(on: Boolean) = prefs.writeLiveSound(on)

    private val _screen = MutableStateFlow(ScreenOne)

    /** One big picture with a strip, or a wall of every camera. */
    val screen: StateFlow<String> = _screen.asStateFlow()

    suspend fun setScreen(value: String) {
        prefs.writeScreen(value)
        _screen.value = value
    }

    /**
     * Asks whether anything has come in since the page was last opened. A
     * television that has never opened it counts the last day, so the dot means
     * "there are alerts" the first time rather than nothing at all.
     */
    suspend fun refreshUnseenAlerts() = withContext(Dispatchers.IO) {
        val current = sessionOrRenew() ?: return@withContext
        val now = System.currentTimeMillis() / 1_000
        val since = prefs.readAlertsSeen() ?: (now - TWO_DAYS)
        val newest = runCatching {
            api.alerts(current.baseUrl, current.token, since, limit = 1)
        }.getOrNull()
        _unseenAlerts.value = !newest.isNullOrEmpty()
    }

    /** The page has been opened, so the dot beside it has nothing left to say. */
    suspend fun markAlertsSeen() = withContext(Dispatchers.IO) {
        prefs.writeAlertsSeen(System.currentTimeMillis() / 1_000)
        _unseenAlerts.value = false
    }

    /** Says an alert has been watched, so it leaves the list here and on the phone. */
    suspend fun markAlertWatched(alert: Alert) = withContext(Dispatchers.IO) {
        val current = sessionOrRenew() ?: return@withContext
        runCatching { api.markReviewed(current.baseUrl, current.token, alert.id) }
    }

    /** An alert's picture: the first thing it saw. */
    suspend fun alertThumbnail(alert: Alert): Bitmap? = withContext(Dispatchers.IO) {
        val event = alert.eventId ?: return@withContext null
        val current = sessionOrRenew() ?: return@withContext null
        runCatching {
            MjpegReader.decode(api.eventThumbnail(current.baseUrl, current.token, event))
        }.getOrNull()
    }

    /**
     * Fetches an alert's clip and keeps it on the television, so it plays from
     * the box rather than stalling on a cut Frigate is still making. [onProgress]
     * is a whole percentage, or null while the size is unknown.
     */
    suspend fun clipFile(alert: Alert, onProgress: (Int?) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            val current = sessionOrRenew() ?: return@withContext null
            val dir = File(cacheDir, "alerts").apply { mkdirs() }
            val file = File(dir, alert.id.replace('/', '_') + ".mp4")
            if (file.isFile && file.length() > 0) return@withContext file

            // A clip is worth keeping while it is new; an old one is only disk.
            val stale = System.currentTimeMillis() - DAY_MILLIS
            dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }

            val part = File(dir, file.name + ".part")
            part.delete()
            try {
                api.open(current.baseUrl, current.token, api.clipPath(alert.id)).use { response ->
                    val total = response.body?.contentLength() ?: -1L
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(part).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                copied += read
                                if (total > 0) onProgress((copied * 100 / total).toInt())
                            }
                        }
                    }
                }
                if (part.length() > 0 && part.renameTo(file)) {
                    onProgress(100)
                    file
                } else {
                    part.delete()
                    null
                }
            } catch (failed: Throwable) {
                part.delete()
                null
            }
        }

    private suspend fun camerasByName(): Map<String, String> {
        val gate = _gate.value
        return if (gate is Gate.Ready) {
            gate.cameras.associate { it.name to it.title }
        } else {
            emptyMap()
        }
    }

    private suspend fun signIn(server: TvPrefs.Server) {
        val kept = server.token
        if (kept != null) {
            try {
                val fresh = api.checkToken(server.baseUrl, kept)
                val token = fresh ?: kept
                if (fresh != null) prefs.writeServer(server.copy(token = fresh))
                _session.value = Session(server.baseUrl, token)
                _gate.value = Gate.Ready(api.cameras(server.baseUrl, token))
                return
            } catch (refused: TvException) {
                if (refused.status == 401 || refused.status == 403) {
                    // The session has run out. The password is what signs in again.
                } else {
                    _gate.value = Gate.Problem(refused = false)
                    return
                }
            } catch (failed: Throwable) {
                _gate.value = Gate.Problem(refused = false)
                return
            }
        }
        signInWithPassword(server)
    }

    private suspend fun signInWithPassword(server: TvPrefs.Server) {
        try {
            val token = api.login(server.baseUrl, server.username, server.password)
            prefs.writeServer(server.copy(token = token))
            _session.value = Session(server.baseUrl, token)
            _gate.value = Gate.Ready(api.cameras(server.baseUrl, token))
        } catch (refused: TvException) {
            _gate.value = Gate.Problem(refused = refused.status == 401 || refused.status == 403)
        } catch (failed: Throwable) {
            _gate.value = Gate.Problem(refused = false)
        }
    }

    private fun Throwable.isAuthFailure(): Boolean {
        val status = (this as? TvException)?.status ?: return false
        return status == 401 || status == 403
    }

    private companion object {
        const val TWO_DAYS = 2L * 24 * 60 * 60
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
