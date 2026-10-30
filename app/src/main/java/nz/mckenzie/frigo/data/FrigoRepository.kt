package nz.mckenzie.frigo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

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
) {

    private val mutex = Mutex()
    private var stored: ServerPrefs.Stored? = null

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    /** Called once on launch. Keeps you signed in if the token still works. */
    suspend fun restore() {
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

    suspend fun cameras(): List<Camera> = authorized { baseUrl, token ->
        api.config(baseUrl, token).cameras
            .filterValues { it.enabled }
            .map { (name, config) ->
                Camera(name = name, title = config.friendlyName ?: prettyCameraName(name))
            }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun events(camera: String? = null, limit: Int = 30, before: Double? = null): List<FrigateEvent> =
        authorized { baseUrl, token ->
            api.events(baseUrl, token, camera = camera, labels = null, limit = limit, before = before)
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
