package nz.mckenzie.frigo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
 * The password is what the app signs in with on its own, so a session that has
 * run out never reaches you. It is kept under the same terms as the token, in
 * this app's own storage, and the switch on the Servers card is what decides.
 *
 * What you chose for yourself on a server - the alerts switch, quiet hours, the
 * lists, the cameras and how they are laid out - is kept under the server and
 * your username together, so signing out and back in finds it again, and a phone
 * shared with someone else keeps their answers apart from yours.
 *
 * The token sits in DataStore, which lives in this app's private storage and is
 * not readable by other apps. Backup is off in the manifest for the same reason.
 * The password sits beside it, on the same terms.
 */
class ServerPrefs(private val context: Context) {

    @Serializable
    data class Stored(
        val baseUrl: String,
        val username: String,
        val token: String? = null,
        /**
         * Kept so the app can sign itself back in. Null until it has one: a server
         * saved by an earlier version has a token and nothing else.
         */
        val password: String? = null,
        /**
         * Whether the password is to be kept. True for a server saved now, so the
         * next sign-in keeps it. Turning the switch off writes false, and a
         * sign-in by hand after that leaves it false.
         */
        val keepPassword: Boolean = true,
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
            prefs[Keys.alertsOn]?.let { next[alertsOnKey(server)] = it }
            prefs[Keys.alertRules]?.let { next[alertRulesKey(baseUrl)] = it }
            prefs[Keys.cameraLayout]?.let { next[cameraLayoutKey(server)] = it }
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

    /**
     * Hands the settings an older version kept for a server alone over to the
     * account signing in, and takes the old copy away, so the next person to sign
     * in to the same server starts with their own. It runs on every sign-in and
     * does nothing once there is nothing older left.
     */
    suspend fun adopt(server: Stored) {
        val older = run {
            val prefs = context.dataStore.data.first()
            listOf(
                booleanPreferencesKey("alerts_on:${server.baseUrl}"),
                stringPreferencesKey("quiet_times:${server.baseUrl}"),
                stringPreferencesKey("alert_objects:${server.baseUrl}"),
                stringPreferencesKey("alert_people:${server.baseUrl}"),
                stringPreferencesKey("camera_list:${server.baseUrl}"),
                intPreferencesKey("camera_layout:${server.baseUrl}"),
            ).filter { it in prefs.asMap().keys }
        }
        if (older.isEmpty()) return

        context.dataStore.edit { prefs ->
            prefs.adopt(booleanPreferencesKey("alerts_on:${server.baseUrl}"), alertsOnKey(server))
            prefs.adopt(stringPreferencesKey("quiet_times:${server.baseUrl}"), quietTimesKey(server))
            prefs.adopt(stringPreferencesKey("alert_objects:${server.baseUrl}"), alertObjectsKey(server))
            prefs.adopt(stringPreferencesKey("alert_people:${server.baseUrl}"), alertPeopleKey(server))
            prefs.adopt(stringPreferencesKey("camera_list:${server.baseUrl}"), cameraListKey(server))
            prefs.adopt(intPreferencesKey("camera_layout:${server.baseUrl}"), cameraLayoutKey(server))
        }
    }

    /** Moves one kept value to the account's name for it, unless it is there already. */
    private fun <T> MutablePreferences.adopt(from: Preferences.Key<T>, to: Preferences.Key<T>) {
        if (this[to] != null) return
        val value = this[from] ?: return
        this[to] = value
        remove(from)
    }

    suspend fun readAlerts(server: Stored): Alerts {
        val prefs = context.dataStore.data.first()
        return Alerts(
            on = prefs[alertsOnKey(server)] ?: false,
        )
    }

    suspend fun writeAlerts(server: Stored, alerts: Alerts) {
        context.dataStore.edit { prefs ->
            prefs[alertsOnKey(server)] = alerts.on
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
     * The cameras this phone leaves out, what it calls them, and their order. One
     * block for the same reason the rules are: a field added later can't break the
     * read.
     */
    suspend fun readCameraList(server: Stored): CameraList? {
        val stored = context.dataStore.data.first()[cameraListKey(server)] ?: return null
        return runCatching { BlockJson.decodeFromString(CameraList.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeCameraList(server: Stored, list: CameraList) {
        val stored = BlockJson.encodeToString(CameraList.serializer(), list)
        context.dataStore.edit { prefs -> prefs[cameraListKey(server)] = stored }
    }

    /**
     * The quiet hours this phone keeps for a server. One block for the same
     * reason the rules are: a field added later can't break the read.
     */
    suspend fun readQuietTimes(server: Stored): QuietTimes? {
        val stored = context.dataStore.data.first()[quietTimesKey(server)] ?: return null
        return runCatching { BlockJson.decodeFromString(QuietTimes.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeQuietTimes(server: Stored, quiet: QuietTimes) {
        val stored = BlockJson.encodeToString(QuietTimes.serializer(), quiet)
        context.dataStore.edit { prefs -> prefs[quietTimesKey(server)] = stored }
    }

    /**
     * The objects this phone lets buzz, or null when it has no list of its own.
     * One block for the same reason the rules are: a field added later can't
     * break the read.
     */
    suspend fun readAlertObjects(server: Stored): AlertObjects? {
        val stored = context.dataStore.data.first()[alertObjectsKey(server)] ?: return null
        return runCatching { BlockJson.decodeFromString(AlertObjects.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeAlertObjects(server: Stored, objects: AlertObjects) {
        val stored = BlockJson.encodeToString(AlertObjects.serializer(), objects)
        context.dataStore.edit { prefs -> prefs[alertObjectsKey(server)] = stored }
    }

    /**
     * The people this phone stays quiet about, or null when it has no list of its
     * own. One block, like the objects list beside it.
     */
    suspend fun readAlertPeople(server: Stored): AlertPeople? {
        val stored = context.dataStore.data.first()[alertPeopleKey(server)] ?: return null
        return runCatching { BlockJson.decodeFromString(AlertPeople.serializer(), stored) }
            .getOrNull()
    }

    suspend fun writeAlertPeople(server: Stored, people: AlertPeople) {
        val stored = BlockJson.encodeToString(AlertPeople.serializer(), people)
        context.dataStore.edit { prefs -> prefs[alertPeopleKey(server)] = stored }
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
    suspend fun readCameraLayout(server: Stored): Int =
        context.dataStore.data.first()[cameraLayoutKey(server)] ?: 0

    suspend fun writeCameraLayout(server: Stored, layout: Int) {
        context.dataStore.edit { prefs -> prefs[cameraLayoutKey(server)] = layout }
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
     * Whether this phone asks for a fingerprint before Frigo opens.
     *
     * It belongs to the phone rather than to a server, like the colours: it is
     * about who is holding this phone, not about which box it is looking at.
     * Signing out leaves it alone, so a phone left on the sign-in page is still
     * one somebody has to unlock.
     */
    suspend fun readLock(): Boolean = context.dataStore.data.first()[Keys.lock] ?: false

    suspend fun writeLock(on: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.lock] = on }
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
     * Every camera's picture size at once, as camera to (height, rate).
     *
     * A backup reads and writes the lot in one go, where the player only ever
     * asks about the one camera it is showing.
     */
    suspend fun readStreamChoices(): Map<String, Pair<Int, Int>> =
        context.dataStore.data.first()[Keys.streamChoices].orEmpty().mapNotNull { entry ->
            val camera = entry.substringBefore('=', "")
            val parts = entry.substringAfter('=', "").split('x')
            val height = parts.getOrNull(0)?.toIntOrNull()
            val rate = parts.getOrNull(1)?.toIntOrNull()
            if (camera.isEmpty() || height == null || rate == null) {
                null
            } else {
                camera to (height to rate)
            }
        }.toMap()

    suspend fun writeStreamChoices(choices: Map<String, Pair<Int, Int>>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.streamChoices] =
                choices.map { (camera, size) -> "$camera=${size.first}x${size.second}" }.toSet()
        }
    }

    /**
     * The last backup written from this account, or an empty answer when it has
     * never written one.
     *
     * It sits under the account rather than under the phone for one reason: two
     * people who share a phone keep their own settings there, and the line saying
     * where your file went should not be the other person's save.
     */
    suspend fun readLastSave(server: Stored): LastSave {
        val stored = context.dataStore.data.first()[lastSaveKey(server)] ?: return LastSave()
        return runCatching { BlockJson.decodeFromString(LastSave.serializer(), stored) }
            .getOrDefault(LastSave())
    }

    suspend fun writeLastSave(server: Stored, save: LastSave) {
        context.dataStore.edit { prefs ->
            prefs[lastSaveKey(server)] = BlockJson.encodeToString(LastSave.serializer(), save)
        }
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

    // Everything one person keeps on one server is named after both, so two accounts
    // on the same box keep two answers to the same question and signing out leaves
    // yours behind for you. What the server itself said - its rules and its settings
    // - is named after the server alone, since it is the same answer for everyone.
    private fun who(server: Stored) = "${server.baseUrl}|${server.username}"

    private fun alertsOnKey(server: Stored) = booleanPreferencesKey("alerts_on:${who(server)}")
    private fun quietTimesKey(server: Stored) = stringPreferencesKey("quiet_times:${who(server)}")
    private fun alertObjectsKey(server: Stored) = stringPreferencesKey("alert_objects:${who(server)}")
    private fun alertPeopleKey(server: Stored) = stringPreferencesKey("alert_people:${who(server)}")
    private fun cameraListKey(server: Stored) = stringPreferencesKey("camera_list:${who(server)}")
    private fun cameraLayoutKey(server: Stored) = intPreferencesKey("camera_layout:${who(server)}")
    private fun lastSaveKey(server: Stored) = stringPreferencesKey("last_save:${who(server)}")

    private fun alertRulesKey(baseUrl: String) = stringPreferencesKey("alert_rules:$baseUrl")
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
        val lock = booleanPreferencesKey("lock")
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
