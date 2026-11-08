package nz.mckenzie.frigo.tv.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.tvStore by preferencesDataStore(name = "frigo_tv")

/**
 * What the television keeps between being turned off and on again.
 *
 * It is one server, because a television shows the cameras of the house it is
 * in. The password is kept with the address and the username: Frigate's session
 * lasts a day, and a television that asks for a password with the remote every
 * morning is a television nobody uses.
 *
 * The file lives in this app's own storage, which no other app can read, and
 * backup is off in the manifest for the same reason. It is not encrypted on top
 * of that, which is a decision recorded in docs/decisions.md: the write goes in
 * as one block, so a Frigate that adds a field cannot break the read.
 */
class TvPrefs(private val context: Context) {

    @Serializable
    data class Server(
        val baseUrl: String,
        val username: String,
        val password: String,
        val token: String? = null,
    )

    suspend fun readServer(): Server? {
        val stored = context.tvStore.data.first()[Keys.server] ?: return null
        return runCatching { Json.decodeFromString(Server.serializer(), stored) }.getOrNull()
    }

    suspend fun writeServer(server: Server) {
        val stored = Json.encodeToString(Server.serializer(), server)
        context.tvStore.edit { prefs -> prefs[Keys.server] = stored }
    }

    suspend fun clear() {
        context.tvStore.edit { prefs -> prefs.remove(Keys.server) }
    }

    /**
     * When the alerts page was last opened, in epoch seconds, or null when it
     * never has been. It is what the dot beside Alerts is worked out from: an
     * alert younger than this is one you have not looked at.
     */
    suspend fun readAlertsSeen(): Long? = context.tvStore.data.first()[Keys.alertsSeen]

    suspend fun writeAlertsSeen(at: Long) {
        context.tvStore.edit { prefs -> prefs[Keys.alertsSeen] = at }
    }

    /**
     * Whether a camera that sees a person or a car takes over the big picture.
     * It belongs to the television rather than to the server: it is about what
     * this screen does in this room.
     */
    val takeover: Flow<Boolean> =
        context.tvStore.data.map { prefs -> prefs[Keys.takeover] ?: true }

    suspend fun writeTakeover(on: Boolean) {
        context.tvStore.edit { prefs -> prefs[Keys.takeover] = on }
    }

    /**
     * The camera the television opens on, by Frigate's own name, or null when it
     * opens on the first one in the list. It is the television's own choice: the
     * phone's order of cameras does not travel.
     */
    suspend fun readDefaultCamera(): String? = context.tvStore.data.first()[Keys.defaultCamera]

    suspend fun writeDefaultCamera(name: String?) {
        context.tvStore.edit { prefs ->
            if (name == null) prefs.remove(Keys.defaultCamera) else prefs[Keys.defaultCamera] = name
        }
    }

    /** Whether the live picture's sound is on. The remote's volume decides how loud. */
    val liveSound: Flow<Boolean> =
        context.tvStore.data.map { prefs -> prefs[Keys.liveSound] ?: true }

    suspend fun writeLiveSound(on: Boolean) {
        context.tvStore.edit { prefs -> prefs[Keys.liveSound] = on }
    }

    /**
     * Whether the cameras page is one big picture with the rest beside it, or a
     * wall of all of them. The television's own choice, like the sound and the
     * camera it opens on.
     */
    suspend fun readScreen(): String = context.tvStore.data.first()[Keys.screen] ?: ScreenOne

    suspend fun writeScreen(screen: String) {
        context.tvStore.edit { prefs -> prefs[Keys.screen] = screen }
    }

    /**
     * The key in the doorbell address, made once and kept. It is what keeps a
     * neighbour's automation on the same Wi-Fi from ringing this television.
     */
    suspend fun ringKey(): String {
        val kept = context.tvStore.data.first()[Keys.ringKey]
        if (kept != null) return kept
        val random = java.security.SecureRandom()
        val made = ByteArray(5).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        context.tvStore.edit { prefs -> prefs[Keys.ringKey] = made }
        return made
    }

    private object Keys {
        val server = stringPreferencesKey("server")
        val alertsSeen = longPreferencesKey("alerts_seen")
        val takeover = booleanPreferencesKey("takeover")
        val defaultCamera = stringPreferencesKey("default_camera")
        val liveSound = booleanPreferencesKey("live_sound")
        val screen = stringPreferencesKey("screen")
        val ringKey = stringPreferencesKey("ring_key")
    }
}

/** The cameras page as it has always been: one big picture, the rest in a strip. */
const val ScreenOne = "one"

/** Every camera on the screen at once, in as square a wall as the count allows. */
const val ScreenGrid = "grid"
