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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "frigo")

/**
 * Remembers the servers you signed in to and keeps each session between
 * launches, so the app opens straight to your cameras.
 *
 * The address and username outlive the token. When Frigate expires the session
 * you sign in again, and those two fields are already filled in.
 *
 * The token sits in DataStore, which lives in this app's private storage and is
 * not readable by other apps. Backup is off in the manifest for the same reason.
 */
class ServerPrefs(private val context: Context) {

    @Serializable
    data class Stored(
        val baseUrl: String,
        val username: String,
        val token: String? = null,
    )

    /** Every server on the phone, and the one the app is on. */
    data class Saved(
        val servers: List<Stored> = emptyList(),
        val active: Stored? = null,
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
    )

    /**
     * The servers saved on the phone, with the active one picked out.
     *
     * A phone from before this list existed has the one server written under keys
     * of its own, and that is turned into a list of one the first time this runs.
     */
    suspend fun readServers(): Saved {
        val prefs = context.dataStore.data.first()
        val servers = prefs[Keys.servers]?.let(::decodeServers).orEmpty()
        if (servers.isNotEmpty()) {
            val active = prefs[Keys.active]
            return Saved(servers, servers.firstOrNull { it.baseUrl == active } ?: servers.first())
        }
        return migrate(prefs) ?: Saved()
    }

    suspend fun writeServers(servers: List<Stored>, active: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.servers] = BlockJson.encodeToString(ListSerializer(Stored.serializer()), servers)
            if (active != null) prefs[Keys.active] = active else prefs.remove(Keys.active)
        }
    }

    /**
     * Turns the one server of an older version into the first in the list, and
     * hands its choices over to it. The rest of the phone's settings - the theme,
     * the tile switch, the walkthrough, the per-camera picture sizes - belong to
     * the phone rather than to a server, so they stay where they are.
     */
    private suspend fun migrate(prefs: Preferences): Saved? {
        val baseUrl = prefs[Keys.baseUrl] ?: return null
        val username = prefs[Keys.username] ?: return null
        val server = Stored(baseUrl = baseUrl, username = username, token = prefs[Keys.token])
        val saved = Saved(servers = listOf(server), active = server)

        context.dataStore.edit { next ->
            next[Keys.servers] = BlockJson.encodeToString(ListSerializer(Stored.serializer()), saved.servers)
            next[Keys.active] = baseUrl
            prefs[Keys.alertsOn]?.let { next[alertsOnKey(baseUrl)] = it }
            prefs[Keys.alertRules]?.let { next[alertRulesKey(baseUrl)] = it }
            prefs[Keys.cameraLayout]?.let { next[cameraLayoutKey(baseUrl)] = it }
            // The one server's keys are not read again, so they go.
            next.remove(Keys.baseUrl)
            next.remove(Keys.username)
            next.remove(Keys.token)
            next.remove(Keys.alertsOn)
            next.remove(Keys.alertsPerHappening)
            next.remove(Keys.alertRules)
            next.remove(Keys.cameraLayout)
        }
        return saved
    }

    /** Drops everything this phone kept for a server it no longer holds. */
    suspend fun forget(baseUrl: String) {
        context.dataStore.edit { prefs ->
            prefs.remove(alertsOnKey(baseUrl))
            prefs.remove(alertRulesKey(baseUrl))
            prefs.remove(quietTimesKey(baseUrl))
            prefs.remove(cameraLayoutKey(baseUrl))
            prefs.remove(settingsKey(baseUrl))
        }
    }

    suspend fun readAlerts(baseUrl: String): Alerts {
        val prefs = context.dataStore.data.first()
        return Alerts(
            on = prefs[alertsOnKey(baseUrl)] ?: false,
        )
    }

    suspend fun writeAlerts(baseUrl: String, alerts: Alerts) {
        context.dataStore.edit { prefs ->
            prefs[alertsOnKey(baseUrl)] = alerts.on
        }
    }

    /**
     * The server's rules as they stood the last time the app looked, so the
     * service can start after a restart with nothing to ask.
     */
    suspend fun readAlertRules(baseUrl: String): AlertRules? {
        val stored = context.dataStore.data.first()[alertRulesKey(baseUrl)] ?: return null
        return runCatching { BlockJson.decodeFromString(AlertRules.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeAlertRules(baseUrl: String, rules: AlertRules) {
        val stored = BlockJson.encodeToString(AlertRules.serializer(), rules)
        context.dataStore.edit { prefs -> prefs[alertRulesKey(baseUrl)] = stored }
    }

    /**
     * The quiet hours this phone keeps for a server. One block for the same
     * reason the rules are: a field added later can't break the read.
     */
    suspend fun readQuietTimes(baseUrl: String): QuietTimes? {
        val stored = context.dataStore.data.first()[quietTimesKey(baseUrl)] ?: return null
        return runCatching { BlockJson.decodeFromString(QuietTimes.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeQuietTimes(baseUrl: String, quiet: QuietTimes) {
        val stored = BlockJson.encodeToString(QuietTimes.serializer(), quiet)
        context.dataStore.edit { prefs -> prefs[quietTimesKey(baseUrl)] = stored }
    }

    /**
     * The server's own settings, kept whole so the settings pages open when it
     * doesn't answer. It is one block for the same reason the rules are: Frigate
     * adding a key can't break the read.
     */
    suspend fun readSettings(baseUrl: String): ServerSettings? {
        val stored = context.dataStore.data.first()[settingsKey(baseUrl)] ?: return null
        return runCatching { BlockJson.decodeFromString(ServerSettings.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeSettings(baseUrl: String, settings: ServerSettings) {
        val stored = BlockJson.encodeToString(ServerSettings.serializer(), settings)
        context.dataStore.edit { prefs -> prefs[settingsKey(baseUrl)] = stored }
    }

    /** Whether the reviews list shows the ones you've already watched. */
    suspend fun readShowReviewed(): Boolean = context.dataStore.data.first()[Keys.showReviewed] ?: false

    suspend fun writeShowReviewed(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.showReviewed] = on }
    }

    /** Which way the cameras are laid out: a grid, a list, or one picture to a row. */
    suspend fun readCameraLayout(baseUrl: String): Int =
        context.dataStore.data.first()[cameraLayoutKey(baseUrl)] ?: 0

    suspend fun writeCameraLayout(baseUrl: String, layout: Int) {
        context.dataStore.edit { prefs -> prefs[cameraLayoutKey(baseUrl)] = layout }
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
     * The newest release GitHub named, and when it said so. Kept so the Settings tab
     * can show a dot before GitHub has been asked again, and so a launch on a phone
     * with no signal still knows what it knew last time.
     *
     * It belongs to the app rather than to a server: which version of Frigo is newest
     * has nothing to do with which Frigate you are signed in to.
     */
    suspend fun readUpdateAnswer(): StoredAnswer {
        val stored = context.dataStore.data.first()[Keys.updateAnswer] ?: return StoredAnswer()
        return runCatching { BlockJson.decodeFromString(StoredAnswer.serializer(), stored) }
            .getOrDefault(StoredAnswer())
    }

    suspend fun writeUpdateAnswer(answer: StoredAnswer) {
        context.dataStore.edit { prefs ->
            prefs[Keys.updateAnswer] = BlockJson.encodeToString(StoredAnswer.serializer(), answer)
        }
    }

    /** The newest version whose dot has been cleared by opening Settings. */
    suspend fun readSeenVersion(): String? = context.dataStore.data.first()[Keys.updateSeen]

    suspend fun writeSeenVersion(version: String) {
        context.dataStore.edit { prefs -> prefs[Keys.updateSeen] = version }
    }

    private fun decodeServers(stored: String): List<Stored>? =
        runCatching { BlockJson.decodeFromString(ListSerializer(Stored.serializer()), stored) }
            .getOrNull()

    // Everything that belongs to one server is named after it, so two of them can
    // hold two different answers to the same question.
    private fun alertsOnKey(baseUrl: String) = booleanPreferencesKey("alerts_on:$baseUrl")

    private fun alertRulesKey(baseUrl: String) = stringPreferencesKey("alert_rules:$baseUrl")
    private fun quietTimesKey(baseUrl: String) = stringPreferencesKey("quiet_times:$baseUrl")
    private fun cameraLayoutKey(baseUrl: String) = intPreferencesKey("camera_layout:$baseUrl")
    private fun settingsKey(baseUrl: String) = stringPreferencesKey("settings:$baseUrl")

    private object Keys {
        val servers = stringPreferencesKey("servers")
        val active = stringPreferencesKey("active")

        // What a phone with a single server wrote. Read once, then removed.
        val baseUrl = stringPreferencesKey("base_url")
        val username = stringPreferencesKey("username")
        val token = stringPreferencesKey("token")
        val alertsOn = booleanPreferencesKey("alerts_on")
        val alertsPerHappening = booleanPreferencesKey("alerts_per_happening")
        val alertRules = stringPreferencesKey("alert_rules")
        val cameraLayout = intPreferencesKey("camera_layout")

        val showReviewed = booleanPreferencesKey("show_reviewed")
        val liveTiles = booleanPreferencesKey("live_tiles")
        val theme = intPreferencesKey("theme")
        val onboarded = booleanPreferencesKey("onboarded")
        val streamChoices = stringSetPreferencesKey("stream_choices")

        // The release check, which is about the app rather than any one server.
        val updateAnswer = stringPreferencesKey("update_answer")
        val updateSeen = stringPreferencesKey("update_seen")
    }

    private companion object {
        /**
         * The rules, the server's settings and the list of servers all go in as one
         * block, so a key Frigate adds can't break the read.
         */
        val BlockJson = Json { ignoreUnknownKeys = true }
    }
}
