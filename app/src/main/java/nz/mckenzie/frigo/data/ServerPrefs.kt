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
import kotlinx.serialization.json.Json

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
     * The app's own switch for watching, and which of the two ways a buzz arrives
     * it is trying.
     *
     * A phone from an older version still has the cameras and objects it picked
     * in this same file. They are left alone and no longer read, so going back a
     * version finds its picks where it left them.
     */
    data class Alerts(
        val on: Boolean = false,
        /** One buzz per happening the server calls an alert, rather than per event. */
        val perHappening: Boolean = false,
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

    suspend fun readAlerts(): Alerts {
        val prefs = context.dataStore.data.first()
        return Alerts(
            on = prefs[Keys.alertsOn] ?: false,
            perHappening = prefs[Keys.alertsPerHappening] ?: false,
        )
    }

    suspend fun writeAlerts(alerts: Alerts) {
        context.dataStore.edit { prefs ->
            prefs[Keys.alertsOn] = alerts.on
            prefs[Keys.alertsPerHappening] = alerts.perHappening
        }
    }

    /**
     * The server's rules as they stood the last time the app looked, so the
     * service can start after a restart with nothing to ask.
     */
    suspend fun readAlertRules(): AlertRules? {
        val stored = context.dataStore.data.first()[Keys.alertRules] ?: return null
        return runCatching { AlertJson.decodeFromString(AlertRules.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeAlertRules(rules: AlertRules) {
        val stored = AlertJson.encodeToString(AlertRules.serializer(), rules)
        context.dataStore.edit { prefs -> prefs[Keys.alertRules] = stored }
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
     * Which colours the app uses: the phone's own choice, always day, or always
     * night. A flow, so the screen changes under you as you pick.
     */
    val theme: Flow<Int> = context.dataStore.data.map { prefs -> prefs[Keys.theme] ?: 0 }

    suspend fun writeTheme(theme: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.theme] = theme }
    }

    /**
     * Whether the walkthrough is done, or null if this phone has never had it
     * decided. Signing out leaves it alone, so the way back in is the plain
     * sign-in page rather than the tour again.
     */
    suspend fun readOnboarded(): Boolean? = context.dataStore.data.first()[Keys.onboarded]

    suspend fun writeOnboarded(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.onboarded] = on }
    }

    /**
     * The picture size and frame rate each camera was last watched at, as camera to
     * (height, rate). Kept per camera, because a camera you watch closely and one you
     * glance at are not the same thing to spend data on.
     */
    suspend fun readStreamChoice(camera: String): Pair<Int, Int>? {
        val entry = context.dataStore.data.first()[Keys.streamChoices]?.firstOrNull {
            it.startsWith("$camera=")
        } ?: return null
        val parts = entry.substringAfter('=').split('x')
        val height = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val rate = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return height to rate
    }

    suspend fun writeStreamChoice(camera: String, height: Int, rate: Int) {
        context.dataStore.edit { prefs ->
            val kept = prefs[Keys.streamChoices].orEmpty().filterNot { it.startsWith("$camera=") }
            prefs[Keys.streamChoices] = (kept + "$camera=${height}x$rate").toSet()        }
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
        val alertsPerHappening = booleanPreferencesKey("alerts_per_happening")
        val alertRules = stringPreferencesKey("alert_rules")
        val showReviewed = booleanPreferencesKey("show_reviewed")
        val cameraLayout = intPreferencesKey("camera_layout")
        val liveTiles = booleanPreferencesKey("live_tiles")
        val theme = intPreferencesKey("theme")
        val onboarded = booleanPreferencesKey("onboarded")
        val streamChoices = stringSetPreferencesKey("stream_choices")
    }

    private companion object {
        /** The rules go in as one block, so a key Frigate adds can't break the read. */
        val AlertJson = Json { ignoreUnknownKeys = true }
    }
}
