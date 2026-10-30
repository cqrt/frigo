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
 * Remembers which server you signed in to and keeps the token pair between
 * launches, so the app opens straight to your cameras.
 *
 * The tokens sit in DataStore, which lives in this app's private storage and is
 * not readable by other apps. Backup is off in the manifest for the same reason.
 */
class ServerPrefs(private val context: Context) {

    data class Stored(
        val baseUrl: String,
        val username: String,
        val accessToken: String?,
        val refreshToken: String?,
    )

    suspend fun read(): Stored? {
        val prefs = context.dataStore.data.first()
        val baseUrl = prefs[Keys.baseUrl] ?: return null
        val username = prefs[Keys.username] ?: return null
        return Stored(
            baseUrl = baseUrl,
            username = username,
            accessToken = prefs[Keys.accessToken],
            refreshToken = prefs[Keys.refreshToken],
        )
    }

    suspend fun write(stored: Stored) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrl] = stored.baseUrl
            prefs[Keys.username] = stored.username
            stored.accessToken?.let { prefs[Keys.accessToken] = it }
            stored.refreshToken?.let { prefs[Keys.refreshToken] = it }
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
    }
}
