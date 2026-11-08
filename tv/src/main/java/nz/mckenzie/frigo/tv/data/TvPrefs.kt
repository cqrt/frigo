package nz.mckenzie.frigo.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
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

    private object Keys {
        val server = stringPreferencesKey("server")
        val alertsSeen = longPreferencesKey("alerts_seen")
    }
}
