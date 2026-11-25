package nz.mckenzie.frigo.tv.data

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** Which certificates the phone handed over when it paired. */
    private val trust: TvTrust,
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

    private val _capabilities = MutableStateFlow(TvCapabilities())

    /**
     * What the server can do: its version, and whether it has reviews at all. An old
     * server still shows its cameras, and this is what takes the alerts page away
     * rather than leaving a button that opens nothing.
     */
    val capabilities: StateFlow<TvCapabilities> = _capabilities.asStateFlow()

    /** One sign-in at a time: six tiles failing together should not mean six logins. */
    private val signingIn = Mutex()

    /** Called once, when the app opens. */
    suspend fun start() = withContext(Dispatchers.IO) {
        _hidden.value = prefs.readHidden()
        _defaultCamera.value = prefs.readDefaultCamera()
        _screen.value = prefs.readScreen()
        _rotation.value = prefs.readRotation()
        _rotationLength.value = prefs.readRotationLength()
        val server = prefs.readServer()
        if (server == null) {
            _gate.value = Gate.Pairing
            return@withContext
        }
        _gate.value = Gate.Loading
        signIn(server)
    }

    /**
     * The phone has sent the address, a sign-in, and whether this server has one at
     * all. Frigate is asked before anything is kept, so a wrong password is refused
     * at the television and the phone can try again. Answers null when the TV is
     * paired, or the sentence to show when it is not.
     *
     * [fingerprint] is the certificate the phone had already trusted, so a
     * television never has to ask about one with a remote. [noAccount] is a server
     * whose sign-in is turned off: no token is asked for, kept, or sent.
     */
    suspend fun accept(
        baseUrl: String,
        username: String,
        password: String,
        fingerprint: String? = null,
        noAccount: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        signingIn.withLock {
            val address = baseUrl.trim().trimEnd('/')
            fingerprint?.let { trust.remember(address, it) }
            try {
                val token = if (noAccount) {
                    if (!api.answersWithoutAccount(address)) return@withLock "That server needs a sign-in."
                    null
                } else {
                    api.login(address, username, password)
                }
                val cameras = api.cameras(address, token.orEmpty())
                prefs.writeServer(
                    TvPrefs.Server(
                        baseUrl = address,
                        username = if (noAccount) "" else username,
                        password = if (noAccount) "" else password,
                        token = token,
                        noAccount = noAccount,
                        fingerprint = fingerprint,
                    ),
                )
                _session.value = Session(address, token.orEmpty())
                _gate.value = Gate.Ready(cameras)
                readCapabilities(address, token.orEmpty())
                null
            } catch (refused: TvException) {
                refused.message
            } catch (failed: Throwable) {
                "The TV could not reach $address."
            }
        }
    }

    /** What the server can do, asked once and kept, so a slow screen isn't answered twice. */
    private fun readCapabilities(baseUrl: String, token: String) {
        runCatching { api.capabilities(baseUrl, token) }
            .onSuccess { _capabilities.value = it }
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
            // A server with no sign-in has nothing to renew, and no password to
            // renew it with. It is asked something small instead, so a server that
            // is away reads as away rather than as signed out.
            if (server.noAccount || server.username.isBlank()) {
                return@withLock runCatching {
                    api.probe(server.baseUrl)
                    Session(server.baseUrl, "").also { _session.value = it }
                }.getOrNull()
            }
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
        // The certificate came from the phone for that one server, so it goes with it.
        prefs.readServer()?.let { server ->
            if (server.fingerprint != null) trust.forget(server.baseUrl)
        }
        prefs.clear()
        _session.value = null
        _hidden.value = emptySet()
        _capabilities.value = TvCapabilities()
        _gate.value = Gate.Pairing
    }

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The cameras this television leaves out, by Frigate's own name. The server's
     * list stays as it is: a hidden camera is still in [Gate.Ready], because the
     * alerts page names every camera Frigate flagged and the two lists in
     * Settings still have to reach the one you turned off.
     */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** Turns a camera off this television, or back on. */
    suspend fun setCameraHidden(camera: String, off: Boolean) {
        val next = if (off) _hidden.value + camera else _hidden.value - camera
        prefs.writeHidden(next)
        _hidden.value = next
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

    private val _rotation = MutableStateFlow(0)

    /** Seconds each camera keeps the whole screen, or 0 when the picture holds still. */
    val rotation: StateFlow<Int> = _rotation.asStateFlow()

    private val _rotationLength = MutableStateFlow(3)

    /** The length the loop ran at last, for the symbol to come back on at. */
    val rotationLength: StateFlow<Int> = _rotationLength.asStateFlow()

    /**
     * Sets the loop's length, or stops it with 0. Stopping keeps the length, so
     * the symbol on the picture and the row in Settings come back to it.
     */
    suspend fun setRotation(seconds: Int) {
        prefs.writeRotation(seconds)
        if (seconds > 0) {
            prefs.writeRotationLength(seconds)
            _rotationLength.value = seconds
        }
        _rotation.value = seconds
    }

    /** The symbol on the picture: on at the length it last ran at, or off. */
    suspend fun toggleRotation() {
        setRotation(if (_rotation.value > 0) 0 else _rotationLength.value)
    }

    private val _ringUrl = MutableStateFlow<String?>(null)

    /**
     * The address Home Assistant posts to when the bell rings, or null while the
     * app has no door open for it. It is shown on the television so it can be
     * typed into an automation once.
     */
    val ringUrl: StateFlow<String?> = _ringUrl.asStateFlow()

    fun setRingUrl(url: String?) {
        _ringUrl.value = url
    }

    private val _rings = MutableSharedFlow<Ring>(extraBufferCapacity = 4)

    /** A doorbell that has been pressed, waiting for a screen to answer it. */
    val rings: SharedFlow<Ring> = _rings.asSharedFlow()

    fun ring(camera: String?) {
        _rings.tryEmit(Ring(camera))
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
        if (server.noAccount || server.username.isBlank()) {
            val reached = runCatching { api.probe(server.baseUrl) }.isSuccess
            if (!reached) {
                _gate.value = Gate.Problem(refused = false)
                return
            }
            _session.value = Session(server.baseUrl, "")
            _gate.value = Gate.Ready(api.cameras(server.baseUrl, ""))
            readCapabilities(server.baseUrl, "")
            return
        }

        val kept = server.token
        if (kept != null) {
            try {
                val fresh = api.checkToken(server.baseUrl, kept)
                val token = fresh ?: kept
                if (fresh != null) prefs.writeServer(server.copy(token = fresh))
                _session.value = Session(server.baseUrl, token)
                _gate.value = Gate.Ready(api.cameras(server.baseUrl, token))
                readCapabilities(server.baseUrl, token)
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
            readCapabilities(server.baseUrl, token)
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

/**
 * A doorbell, pressed. The camera is whichever one the address named, or null
 * when it named none and the television decides.
 */
data class Ring(val camera: String?, val at: Long = System.currentTimeMillis())
