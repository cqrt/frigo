package nz.mckenzie.frigo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

/** What the app is showing: a spinner, the login form, or your cameras. */
sealed interface Gate {
    data object Loading : Gate
    data class SignedOut(val message: String? = null) : Gate
    data class SignedIn(val baseUrl: String, val username: String) : Gate
}

/**
 * Everything that talks to Frigate. It holds the token pair, renews it when the
 * server says it has expired, and hands the screens plain data.
 */
class FrigoRepository(
    private val api: FrigateApi,
    private val prefs: ServerPrefs,
) {

    private val mutex = Mutex()
    private var stored: ServerPrefs.Stored? = null

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    /** Called once on launch. Keeps you signed in if the tokens still work. */
    suspend fun restore() {
        val saved = prefs.read()
        if (saved == null || saved.accessToken.isNullOrBlank()) {
            _gate.value = Gate.SignedOut()
            return
        }
        stored = saved
        val healthy = runCatching {
            withContext(Dispatchers.IO) {
                try {
                    api.checkToken(saved.baseUrl, saved.accessToken)
                } catch (e: FrigateException) {
                    if (!e.isAuthFailure) throw e
                    refreshTokens(saved.accessToken, force = true)
                    Unit
                }
            }
        }.isSuccess

        _gate.value = if (healthy) {
            Gate.SignedIn(saved.baseUrl, saved.username)
        } else {
            stored = null
            prefs.clear()
            Gate.SignedOut("Couldn't reach your server. Sign in again.")
        }
    }

    suspend fun signIn(address: String, username: String, password: String) {
        val baseUrl = normalizeBaseUrl(address)
        val tokens = withContext(Dispatchers.IO) { api.login(baseUrl, username, password) }
        val saved = ServerPrefs.Stored(
            baseUrl = baseUrl,
            username = username,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
        )
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
     * Runs [block] with a fresh access token. A 401 means the token aged out, so
     * we renew once and try again rather than signing you out mid-scroll.
     */
    private suspend fun <T> authorized(block: suspend (baseUrl: String, token: String) -> T): T {
        val current = stored ?: throw FrigateException(401, "Not signed in")
        val token = current.accessToken ?: throw FrigateException(401, "Not signed in")
        return try {
            withContext(Dispatchers.IO) { block(current.baseUrl, token) }
        } catch (e: FrigateException) {
            if (!e.isAuthFailure) throw e
            val renewed = refreshTokens(token)
            withContext(Dispatchers.IO) { block(current.baseUrl, renewed) }
        }
    }

    private suspend fun refreshTokens(staleAccessToken: String, force: Boolean = false): String =
        mutex.withLock {
            val current = stored ?: throw FrigateException(401, "Not signed in")
            // Another request may have renewed it while this one was in flight.
            if (!force && current.accessToken != null && current.accessToken != staleAccessToken) {
                return@withLock current.accessToken
            }
            val refresh = current.refreshToken
                ?: throw FrigateException(401, "Your session expired. Sign in again.")
            val tokens = withContext(Dispatchers.IO) { api.refresh(current.baseUrl, refresh) }
            val next = current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
            )
            stored = next
            prefs.write(next)
            tokens.accessToken
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
