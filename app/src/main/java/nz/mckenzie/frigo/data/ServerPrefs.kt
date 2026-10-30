package nz.mckenzie.frigo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "frigo")

/**
 * Remembers which server you signed in to and keeps the session between
 * launches, so the app opens straight to your cameras.
 *
 * The address and username outlive the token. When Frigate expires the session
 * you sign in again, and those two fields are already filled in.
 *
 * The token sits in DataStore, which lives in this app's private storage and is
 * not readable by other apps. Backup is off in the manifest for the same reason.
 */
class ServerPrefs(private val context: Context) {

    data class Stored(
        val baseUrl: String,
        val username: String,
        val token: String?,
    )

    suspend fun read(): Stored? {
        val prefs = context.dataStore.data.first()
        val baseUrl = prefs[Keys.baseUrl] ?: return null
        val username = prefs[Keys.username] ?: return null
        return Stored(
            baseUrl = baseUrl,
            username = username,
            token = prefs[Keys.token],
        )
    }

    suspend fun write(stored: Stored) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrl] = stored.baseUrl
            prefs[Keys.username] = stored.username
            if (stored.token != null) prefs[Keys.token] = stored.token else prefs.remove(Keys.token)
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val token = stringPreferencesKey("token")
    }
}
