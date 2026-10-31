package nz.mckenzie.frigo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

    /**
     * What should set your phone off: nothing at all, or the cameras and the
     * objects you picked.
     */
    data class Alerts(
        val on: Boolean = false,
        val cameras: Set<String> = emptySet(),
        val labels: Set<String> = emptySet(),
    ) {
        /** True when this event is one of the ones you asked to hear about. */
        fun wants(camera: String, label: String): Boolean =
            on && camera in cameras && label in labels
    }

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

    suspend fun readAlerts(): Alerts {
        val prefs = context.dataStore.data.first()
        return Alerts(
            on = prefs[Keys.alertsOn] ?: false,
            cameras = prefs[Keys.alertCameras] ?: emptySet(),
            labels = prefs[Keys.alertLabels] ?: emptySet(),
        )
    }

    suspend fun writeAlerts(alerts: Alerts) {
        context.dataStore.edit { prefs ->
            prefs[Keys.alertsOn] = alerts.on
            prefs[Keys.alertCameras] = alerts.cameras
            prefs[Keys.alertLabels] = alerts.labels
        }
    }

    /** Whether the reviews list shows the ones you've already watched. */
    suspend fun readShowReviewed(): Boolean = context.dataStore.data.first()[Keys.showReviewed] ?: false

    suspend fun writeShowReviewed(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.showReviewed] = on }
    }

    /** Which way the cameras are laid out: a grid, a list, or one picture to a row. */
    suspend fun readCameraLayout(): Int = context.dataStore.data.first()[Keys.cameraLayout] ?: 0

    suspend fun writeCameraLayout(layout: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.cameraLayout] = layout }
    }

    /**
     * Whether the home page plays each camera live rather than taking a picture
     * every second. A flow, so changing it in Settings reaches the tiles without
     * the app being restarted.
     */
    val liveTiles: Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[Keys.liveTiles] ?: false }

    suspend fun writeLiveTiles(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.liveTiles] = on }
    }

    /**
     * Signing out drops the server and the token. The alert choices stay, so
     * signing back in to the same server does not mean ticking everything again.
     */
    suspend fun clear() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.baseUrl)
            prefs.remove(Keys.username)
            prefs.remove(Keys.token)
        }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val token = stringPreferencesKey("token")
        val alertsOn = booleanPreferencesKey("alerts_on")
        val alertCameras = stringSetPreferencesKey("alert_cameras")
        val alertLabels = stringSetPreferencesKey("alert_labels")
        val showReviewed = booleanPreferencesKey("show_reviewed")
        val cameraLayout = intPreferencesKey("camera_layout")
        val liveTiles = booleanPreferencesKey("live_tiles")
    }
}
