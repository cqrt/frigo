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
}
