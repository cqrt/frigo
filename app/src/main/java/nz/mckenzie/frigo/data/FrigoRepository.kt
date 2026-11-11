package nz.mckenzie.frigo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import nz.mckenzie.frigo.BuildConfig
import nz.mckenzie.frigo.R
import java.io.File
import java.io.IOException
import java.util.Locale

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
 * Where an event's clip is, and the token the player has to send with it. The
 * player fetches the clip itself, so it needs both rather than a call to make.
 */
data class ClipSource(val url: String, val token: String)

/**
 * Frigate's message feed, and the token it needs. The app stays connected to it
 * while it is watching for alerts, and each new event arrives as a message
 * rather than being asked for.
 */
data class StreamSource(val url: String, val token: String)

/**
 * Everything that talks to Frigate. It holds the session token and hands the
 * screens plain data.
 *
 * Frigate 0.18 has no refresh endpoint: the sign-in issues one JWT that lasts a
 * day, and the server lengthens it only for a token that arrives as a cookie,
 * which a bearer token never is. The clock starts at the sign-in and stops a day
 * later. The way past it is the password this phone keeps: when the session has
 * run out the app signs in again with it, and nothing reaches you.
 */
class FrigoRepository(
    /**
     * The app's own context, which the messages it builds are read through: a
     * failure is said in the app's language, not the phone's.
     */
    val context: Context,
    private val api: FrigateApi,
    private val prefs: ServerPrefs,
    /** Where fetched clips are kept, so watching one twice costs one download. */
    cacheDir: File,
) {

    private val clipDir = File(cacheDir, "clips")

    /** Said when there is no session to send a request with. */
    private val notSignedIn: String
        get() = context.words(R.string.api_not_signed_in)

    /**
     * Said when a page is asked for with nothing to build it from: no server
     * signed in, or one that hasn't been reached and has no copy on the phone.
     */
    private val noSettingsYet: String
        get() = context.words(R.string.api_no_settings_yet)

    private val mutex = Mutex()
    @Volatile private var stored: ServerPrefs.Stored? = null

    /** Every server saved on the phone, in the order they were added. */
    private val _servers = MutableStateFlow<List<ServerPrefs.Stored>>(emptyList())
    val servers: StateFlow<List<ServerPrefs.Stored>> = _servers.asStateFlow()

    /** The last answer on what each camera can give, and on what they are all doing. */
    private val limits = mutableMapOf<String, Pair<Long, StreamLimits>>()
    private var statsAt: Pair<Long, FrigateStats>? = null

    private val _gate = MutableStateFlow<Gate>(Gate.Loading)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    private val _alerts = MutableStateFlow(ServerPrefs.Alerts())
    val alerts: StateFlow<ServerPrefs.Alerts> = _alerts.asStateFlow()

    /**
     * What the server says is worth a buzz, the last time it was asked. The
     * service reads this rather than any choice of its own, and the copy on the
     * phone is what it starts from after a restart with no network.
     */
    private val _alertRules = MutableStateFlow(AlertRules())
    val alertRules: StateFlow<AlertRules> = _alertRules.asStateFlow()

    /**
     * When this phone keeps quiet, and which cameras it keeps quiet. Its own
     * setting rather than the server's: what it silences is this phone's
     * notifications, and nobody else's.
     */
    private val _quietTimes = MutableStateFlow(QuietTimes())
    val quietTimes: StateFlow<QuietTimes> = _quietTimes.asStateFlow()

    /**
     * The objects this phone lets buzz. Its own list rather than the server's:
     * Frigate won't take the change from anyone but an admin, so this narrows
     * what the server already sends, and it can only take an alert away.
     *
     * The alert service reads it too, so a change here is felt without the app
     * being open.
     */
    private val _alertObjects = MutableStateFlow(AlertObjects())
    val alertObjects: StateFlow<AlertObjects> = _alertObjects.asStateFlow()

    /**
     * The people this phone stays quiet about, by the name Frigate knows them by.
     * Its own list, like the objects beside it: Frigate has no way to leave a name
     * out of an alert, and the name is on the alert the phone receives.
     */
    private val _alertPeople = MutableStateFlow(AlertPeople())
    val alertPeople: StateFlow<AlertPeople> = _alertPeople.asStateFlow()

    /**
     * The cameras this phone leaves out, what it calls them, and their order. Its
     * own list rather than the server's: what it changes is this phone's screens
     * and its buzz, and the server and its other phones don't move.
     *
     * The alert service reads it too, so a camera turned off here stops buzzing
     * without the app being open.
     */
    private val _cameraList = MutableStateFlow(CameraList())
    val cameraList: StateFlow<CameraList> = _cameraList.asStateFlow()

    /**
     * Whether the walkthrough is still to come, or null until the phone has been
     * read. The app shows its own spinner for the null, so a new phone never
     * flashes the tabs and an old one never flashes the tour.
     */
    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> = _onboarded.asStateFlow()

    /**
     * Whether this phone asks for a fingerprint before the app opens, or null
     * until the phone has been read.
     *
     * It is read before anything else on launch, so the cover is decided before
     * a screen has been drawn: a phone with the switch on never shows a frame of
     * its cameras on the way in.
     */
    private val _lock = MutableStateFlow<Boolean?>(null)
    val lock: StateFlow<Boolean?> = _lock.asStateFlow()

    /**
     * Whether this account is an admin, as the last settings answer said.
     *
     * Every settings answer carries the role, so this is kept rather than asked
     * for again: the screens that offer the server's own pages read it. It is
     * read off the phone's copy at launch, and null until there is an answer at
     * all, which is a phone that has never reached its server.
     */
    private val _admin = MutableStateFlow<Boolean?>(null)
    val admin: StateFlow<Boolean?> = _admin.asStateFlow()

    /**
     * Whether the alert choices have been read off the phone yet. The service
     * starts by itself after a restart, before any screen has run, and it needs
     * the saved choices rather than a blank slate.
     */
    @Volatile private var alertsLoaded = false

    /** The same, for the server's rules. */
    @Volatile private var alertRulesLoaded = false

    /** The same, for the phone's quiet hours. */
    @Volatile private var quietTimesLoaded = false

    /** The same, for the phone's own list of objects. */
    @Volatile private var alertObjectsLoaded = false

    /** The same, for the phone's own list of people. */
    @Volatile private var alertPeopleLoaded = false

    /** The same, for the phone's own list of cameras. */
    @Volatile private var cameraListLoaded = false

    /**
     * Reads what should set your phone off, once. Safe to call from anywhere;
     * the second caller gets the choices already in memory.
     */
    suspend fun loadAlerts() {
        if (alertsLoaded) return
        val server = activeServer()
        _alerts.value = server?.let { prefs.readAlerts(it) } ?: ServerPrefs.Alerts()
        alertsLoaded = true
    }

    /**
     * Reads the rules the server left on this phone last time. Safe to call from
     * anywhere, and what the service starts from before it has a connection.
     */
    suspend fun loadAlertRules() {
        if (alertRulesLoaded) return
        val server = activeServer()
        _alertRules.value = server?.let { prefs.readAlertRules(it.baseUrl) } ?: AlertRules()
        alertRulesLoaded = true
    }

    /**
     * Reads this phone's quiet hours for the server. Safe to call from anywhere,
     * and what the service starts from after a restart, before any screen has
     * run and before there is a connection to ask anything.
     */
    suspend fun loadQuietTimes() {
        if (quietTimesLoaded) return
        val server = activeServer()
        _quietTimes.value = server?.let { prefs.readQuietTimes(it) } ?: QuietTimes()
        quietTimesLoaded = true
    }

    /**
     * Reads the objects this phone lets buzz. Safe to call from anywhere, and what
     * the service starts from after a restart, so the list still narrows the buzz
     * with no screen open.
     */
    suspend fun loadAlertObjects() {
        if (alertObjectsLoaded) return
        val server = activeServer()
        _alertObjects.value = server?.let { prefs.readAlertObjects(it) } ?: AlertObjects()
        alertObjectsLoaded = true
    }

    /**
     * Reads the people this phone stays quiet about. Safe to call from anywhere, and
     * what the service starts from after a restart, so the list still holds an alert
     * back with no screen open.
     */
    suspend fun loadAlertPeople() {
        if (alertPeopleLoaded) return
        val server = activeServer()
        _alertPeople.value = server?.let { prefs.readAlertPeople(it) } ?: AlertPeople()
        alertPeopleLoaded = true
    }

    /**
     * Reads the cameras this phone leaves out, and its names and order for them.
     * Safe to call from anywhere, and what the service starts from after a restart,
     * so a camera turned off here stays off in the buzz.
     */
    suspend fun loadCameraList() {
        if (cameraListLoaded) return
        val server = activeServer()
        _cameraList.value = server?.let { prefs.readCameraList(it) } ?: CameraList()
        cameraListLoaded = true
    }

    /**
     * Asks the server what is worth a buzz and keeps the answer, on the phone as
     * well as in memory.
     *
     * Called when the app opens, when the connection comes back, and after a
     * change made here. A change made in a browser lands the next time the app
     * looks, which is when you open it. A server that can't be reached leaves the
     * last answer alone rather than going quiet on a guess.
     */
    suspend fun refreshAlertRules() {
        val url = activeServer()?.baseUrl ?: return
        val settings = try {
            fetchSettings().also { prefs.writeSettings(url, it) }
        } catch (failure: Throwable) {
            return
        }
        val rules = settings.config.alertRules()
        prefs.writeAlertRules(url, rules)
        _alertRules.value = rules
        alertRulesLoaded = true
    }

    /**
     * The server the app is on, read off the phone when this process has not
     * loaded it yet. The alert service starts on its own after a phone restart,
     * before any screen has run, and would otherwise find nothing.
     */
    private suspend fun activeServer(): ServerPrefs.Stored? {
        stored?.let { return it }
        val saved = prefs.readServers()
        _servers.value = saved.servers
        return saved.active?.also { stored = it }
    }

    private suspend fun persist(servers: List<ServerPrefs.Stored>, active: String?) {
        _servers.value = servers
        prefs.writeServers(servers, active)
    }

    /** The servers on the phone, read once if this process hasn't looked yet. */
    private suspend fun known(): List<ServerPrefs.Stored> {
        if (_servers.value.isNotEmpty()) return _servers.value
        return prefs.readServers().servers.also { _servers.value = it }
    }

    /** Reads the phone's copy of the active server's choices, over what was there. */
    private suspend fun loadChoices() {
        val url = stored?.baseUrl
        // Settings an older version kept for the server alone come over to this
        // account first, so nobody signs in to find them missing.
        stored?.let { prefs.adopt(it) }
        _alerts.value = stored?.let { prefs.readAlerts(it) } ?: ServerPrefs.Alerts()
        _alertRules.value = url?.let { prefs.readAlertRules(it) } ?: AlertRules()
        _quietTimes.value = stored?.let { prefs.readQuietTimes(it) } ?: QuietTimes()
        _alertObjects.value = stored?.let { prefs.readAlertObjects(it) } ?: AlertObjects()
        _alertPeople.value = stored?.let { prefs.readAlertPeople(it) } ?: AlertPeople()
        _cameraList.value = stored?.let { prefs.readCameraList(it) } ?: CameraList()
        _admin.value = url?.let { prefs.readSettings(it) }?.let { it.profile.role == "admin" }
        alertsLoaded = true
        alertRulesLoaded = true
        quietTimesLoaded = true
        alertObjectsLoaded = true
        alertPeopleLoaded = true
        cameraListLoaded = true
    }

    /** The server's own two answers the settings pages are built from. */
    private suspend fun fetchSettings(): ServerSettings = authorized { baseUrl, token ->
        val profile = api.profile(baseUrl, token)
        val config = api.config(baseUrl, token)
        // Kept here because every answer carries it, and the screens that offer
        // the server's own pages would otherwise ask again to find out.
        _admin.value = profile.role == "admin"
        ServerSettings(config = config, profile = profile, readAt = System.currentTimeMillis())
    }

    /** A settings answer, and how old it is when it came off the phone instead. */
    private data class SettingsAnswer(val settings: ServerSettings, val copiedAt: Long?)

    /**
     * What the server's settings say, or the copy on the phone when it can't be
     * reached at all. Anything the server actually answered - a session that has
     * run out, a certificate the phone won't take - is passed on, because the copy
     * is not the same answer.
     */
    private suspend fun settingsAnswer(): SettingsAnswer? {
        val url = activeServer()?.baseUrl ?: return null
        return try {
            SettingsAnswer(fetchSettings().also { prefs.writeSettings(url, it) }, copiedAt = null)
        } catch (failure: Throwable) {
            if (!failure.isUnreachable()) throw failure
            prefs.readSettings(url)?.let { SettingsAnswer(it, copiedAt = it.readAt) }
        }
    }

    /** Called once on launch. Keeps you signed in if the token still works. */
    suspend fun restore() {
        val saved = activeServer()
        // Settings an older version kept for the server alone come over to this
        // account before anything reads them.
        saved?.let { prefs.adopt(it) }
        // Read first, and before the early return below, so a phone with the lock
        // on is covered on its way to the sign-in page as well.
        _lock.value = prefs.readLock()
        loadAlerts()
        loadAlertRules()
        loadQuietTimes()
        loadAlertObjects()
        loadAlertPeople()
        loadCameraList()
        // The first launch after the walkthrough arrived settles the question once
        // and writes the answer down. A phone with a server already saved is one
        // that was set up before the walkthrough existed, so it is not owed it.
        // A phone with nothing saved is new, and carries on owing it even after it
        // signs in half way through.
        val done = prefs.readOnboarded() ?: run {
            val skip = saved != null
            prefs.writeOnboarded(skip)
            skip
        }
        _onboarded.value = done

        if (saved == null) {
            _gate.value = Gate.SignedOut()
            return
        }

        // The token is offered first, and a session that has run out is signed in
        // again with the password this phone kept, so a night away from the app
        // doesn't end on the sign-in page.
        val signedIn = runCatching { mutex.withLock { renewLocked() } }

        signedIn.onSuccess {
            _gate.value = Gate.SignedIn(saved.baseUrl, saved.username)
            refreshAlertRules()
        }.onFailure { error ->
            // The session ran out and there was no password to sign in with. The
            // gate has already been told why.
            if (error is FrigateException && error.isAuthFailure) return@onFailure
            // A server that cannot be reached is not a sign-out: the phone may be
            // out of signal, and the screens show their own errors for that.
            _gate.value = Gate.SignedIn(saved.baseUrl, saved.username)
        }
    }

    /**
     * Signs in to a server, adding it to the list if it isn't there and making it
     * the one the app is on. Signing in to a server you already have replaces its
     * token and leaves the rest of the list alone.
     *
     * The password is kept, unless this server's Stay signed in switch is off, so
     * this is usually the last one you type.
     */
    suspend fun signIn(address: String, username: String, password: String) {
        val baseUrl = normalizeBaseUrl(address)
        val token = withContext(Dispatchers.IO) { api.login(baseUrl, username, password) }
        mutex.withLock {
            val keep = known().firstOrNull { it.baseUrl == baseUrl }?.keepPassword ?: true
            val saved = ServerPrefs.Stored(
                baseUrl = baseUrl,
                username = username,
                token = token,
                password = password.takeIf { keep },
                keepPassword = keep,
            )
            val servers = known()
                .filterNot { it.baseUrl == baseUrl } + saved
            stored = saved
            persist(servers, baseUrl)
        }
        // The phone's choices for this server are read before the gate moves, so the
        // screens that replace this one never see the last server's.
        loadChoices()
        _gate.value = Gate.SignedIn(baseUrl, username)
        refreshAlertRules()
    }

    /**
     * Whether the phone keeps the password, and keeps it from now on. Off drops
     * the password and the intent, so a session then lasts its day as it did
     * before. On asks Frigate with the password typed, so a wrong one is refused
     * here rather than three in the morning when the session runs out.
     */
    suspend fun setKeepSignedIn(on: Boolean, password: String? = null) {
        val server = stored ?: activeServer() ?: return
        if (!on) {
            mutex.withLock {
                val next = server.copy(password = null, keepPassword = false)
                stored = next
                keep(next)
            }
            return
        }
        val typed = password?.takeIf { it.isNotBlank() } ?: server.password ?: return
        // The sign-in proves the password and starts a fresh day at the same time.
        val token = withContext(Dispatchers.IO) {
            api.login(server.baseUrl, server.username, typed)
        }
        mutex.withLock {
            val next = server.copy(password = typed, keepPassword = true, token = token)
            stored = next
            keep(next)
        }
    }

    /**
     * Signs in again with the password this phone kept, for a session that has run
     * out. Answers whether there is a working session now. The alert service asks
     * this rather than stopping for the night when its socket is turned down.
     */
    suspend fun signInAgain(): Boolean {
        activeServer() ?: return false
        return mutex.withLock {
            val current = stored ?: return@withLock false
            signInLocked(current) != null
        }
    }

    /**
     * Switches to another server on the list. Its sign-in, its alerts switch and
     * its camera layout come back with it, because each server keeps its own.
     *
     * A server whose session has aged out shows the sign-in page with its address
     * filled in, rather than pretending to be signed in to it.
     */
    suspend fun switchTo(baseUrl: String) {
        val next = known().firstOrNull { it.baseUrl == baseUrl } ?: return
        mutex.withLock {
            stored = next
            persist(_servers.value, next.baseUrl)
        }
        // The picture sizes and the census came from the server that was on before.
        limits.clear()
        statsAt = null
        loadChoices()

        // A server whose session ran out is signed in again from the password this
        // phone kept, so moving to it is a tap rather than a sign-in.
        if (next.token.isNullOrBlank() && next.password != null) {
            val renewed = runCatching { mutex.withLock { renewLocked() } }
            val failure = renewed.exceptionOrNull()
            _gate.value = when {
                renewed.isSuccess -> Gate.SignedIn(next.baseUrl, next.username)
                // A server that cannot be reached opens as it is, and its screens say so.
                failure !is FrigateException || !failure.isAuthFailure ->
                    Gate.SignedIn(next.baseUrl, next.username)
                // The sign-in page is already up, with the reason on it.
                else -> return
            }
            return
        }

        _gate.value = if (next.token.isNullOrBlank()) {
            Gate.SignedOut(address = next.baseUrl, username = next.username)
        } else {
            Gate.SignedIn(next.baseUrl, next.username)
        }
    }

    /**
     * Signing out leaves the server you are on, and takes the address off the
     * phone. What you chose on it stays, under your username, so signing back in
     * finds your switches, your quiet hours and your cameras as you left them. If
     * you have another server it takes over; otherwise the sign-in page comes up
     * with the address you left filled in.
     */
    suspend fun signOut() {
        val gone = stored ?: activeServer()
        var leaving: ServerPrefs.Stored? = null
        mutex.withLock {
            val kept = known().filterNot { it.baseUrl == gone?.baseUrl }
            // Whatever is left takes over, preferring one that can still talk to
            // its server so a stale entry doesn't hide a working one.
            leaving = kept.firstOrNull { !it.token.isNullOrBlank() } ?: kept.firstOrNull()
            stored = leaving
            persist(kept, leaving?.baseUrl)
        }
        limits.clear()
        statsAt = null
        loadChoices()

        val next = leaving
        _gate.value = if (next == null || next.token.isNullOrBlank()) {
            Gate.SignedOut(
                address = next?.baseUrl ?: gone?.baseUrl.orEmpty(),
                username = next?.username ?: gone?.username.orEmpty(),
            )
        } else {
            Gate.SignedIn(next.baseUrl, next.username)
        }
    }

    /**
     * The walkthrough is done. Settings also calls this with false, to put the
     * welcome back on the list for anyone who wants to see it again.
     */
    suspend fun setOnboarded(on: Boolean) {
        _onboarded.value = on
        prefs.writeOnboarded(on)
    }

    /**
     * Whether this phone asks for a fingerprint before the app opens. The switch
     * only reaches here once the phone has said it can ask, so nothing is written
     * that the app could not get back through.
     */
    suspend fun setLock(on: Boolean) {
        _lock.value = on
        prefs.writeLock(on)
    }

    /**
     * The cameras to show, and only the ones this user may watch.
     *
     * `/api/config` lists every camera on the server no matter who asks, so a
     * restricted user would be shown cameras that answer 403 when the app asks
     * for a picture. `/api/profile` carries the list the role allows, and that
     * is what filters this. A user who may see everything gets no list, which
     * means all of them.
     */
    suspend fun cameras(): List<Camera> = authorized { baseUrl, token ->
        val allowed = api.profile(baseUrl, token).allowedCameras
        api.config(baseUrl, token).visibleCameras(allowed)
    }

    /**
     * What Frigate flags, the cameras to offer on that page, and whether this
     * account may change any of it. One trip, because a round trip is felt, and
     * the phone's copy when the server doesn't answer.
     */
    suspend fun reviewPage(): ReviewPage {
        val answer = settingsAnswer() ?: throw FrigateException(0, noSettingsYet)
        val settings = answer.settings
        return ReviewPage(
            settings = settings.config.reviewSettings(),
            cameras = settings.config.visibleCameras(settings.profile.allowedCameras),
            admin = settings.profile.role == "admin",
            readAt = answer.copiedAt,
        )
    }

    /** Changes what Frigate flags, for every camera or for one. */
    suspend fun setReview(change: ConfigChange) {
        authorized { baseUrl, token -> api.saveConfig(baseUrl, token, change) }
    }

    /**
     * The cameras to offer on the switches page, what each switch is set to, and
     * whether this account may change any of it. One trip, because a round trip
     * is felt, and the phone's copy when the server doesn't answer.
     */
    suspend fun cameraSwitchPage(): CameraSwitchPage {
        val answer = settingsAnswer() ?: throw FrigateException(0, noSettingsYet)
        val settings = answer.settings
        val config = settings.config
        val cameras = config.visibleCameras(settings.profile.allowedCameras)
        return CameraSwitchPage(
            cameras = cameras,
            switches = cameras.associate { camera ->
                camera.name to (config.cameras[camera.name]?.switches() ?: CameraSwitches())
            },
            faceRecognitionOff = !config.faceRecognition.enabled,
            licensePlateOff = !config.lpr.enabled,
            admin = settings.profile.role == "admin",
            readAt = answer.copiedAt,
        )
    }

    /** Turns one switch on or off on one camera. */
    suspend fun setCameraSwitch(camera: String, switch: CameraSwitch, on: Boolean) {
        val change = cameraSwitchChange(camera, switch, on)
        authorized { baseUrl, token -> api.saveConfig(baseUrl, token, change) }
    }

    /**
     * What the server is set up to describe alerts with, and whether it is on.
     *
     * One trip, and the phone's copy when the server doesn't answer, which is how
     * every settings page here works.
     */
    suspend fun aiSetup(): AiSetup {        val answer = settingsAnswer() ?: throw FrigateException(0, noSettingsYet)
        val settings = answer.settings
        val services = settings.config.genaiServices()
        return AiSetup(
            service = services.firstOrNull { it.describes },
            on = settings.config.review.genai.enabled,
            chats = services.any { it.chats },
            admin = settings.profile.role == "admin",
            readAt = answer.copiedAt,
        )
    }

    /** Turns the server's own description of an alert on or off. */
    suspend fun setDescribeAlerts(on: Boolean) {
        authorized { baseUrl, token -> api.saveConfig(baseUrl, token, describeAlertsChange(on)) }
    }

    /**
     * Points a model service at an address and a model. A null [name] makes a new
     * one, which is what a server with nothing set up for describing needs.
     */
    suspend fun setGenaiModel(name: String?, baseUrl: String, model: String, giveChat: Boolean) {
        authorized { baseUrl, token ->
            api.saveConfig(baseUrl, token, genaiModelChange(name, baseUrl, model, giveChat))
        }
    }

    /**
     * Whether the server is set to describe its alerts, from the copy of its
     * settings this phone already keeps.
     *
     * The alert service asks before it asks for a sentence of its own, so a switch
     * turned off means the pictures stop going anywhere at all. Before the phone
     * has read those settings the answer is yes, which is how it behaved while the
     * setting was the server's alone.
     */
    suspend fun describesAlerts(): Boolean {
        val url = activeServer()?.baseUrl ?: return false
        return prefs.readSettings(url)?.config?.review?.genai?.enabled ?: true
    }

    /**
     * Everything the System page shows, from one reading of the server's stats.
     *
     * The page asks who is looking as it reads, because the restart is only
     * offered to an admin.
     */
    suspend fun systemPage(): SystemPage = authorized { baseUrl, token ->
        val stats = api.stats(baseUrl, token)
        // A server that won't say who you are still gives up its stats, so this
        // failing only ever costs the restart row.
        val admin = runCatching { api.profile(baseUrl, token).role == "admin" }
            .getOrDefault(false)
        stats.systemPage(context, admin = admin)
    }

    /** Restarts the server. Everything stops for about half a minute after it answers. */
    suspend fun restartServer() {
        authorized { baseUrl, token -> api.restart(baseUrl, token) }
    }

    /** Everything a service has written, for the first look at a log. */
    suspend fun logs(service: String): FrigateLogs =
        authorized { baseUrl, token -> api.logs(baseUrl, token, service) }

    /**
     * Opens a log's feed, which the caller closes. A 403 is left as
     * [NotAnAdminException] on the way through, so this never treats an account
     * that isn't an admin as a session to renew.
     */
    suspend fun openLogFeed(service: String): Response =
        authorized { baseUrl, token -> api.openLogFeed(baseUrl, token, service) }

    suspend fun events(
        camera: String? = null,
        labels: List<String> = emptyList(),
        subLabels: List<String> = emptyList(),
        limit: Int = 30,
        after: Double? = null,
        before: Double? = null,
    ): List<FrigateEvent> = authorized { baseUrl, token ->
        api.events(
            baseUrl,
            token,
            camera = camera,
            labels = labels,
            subLabels = subLabels,
            limit = limit,
            after = after,
            before = before,
        )
    }

    /** The moments Frigate thought were worth a look, newest first. */
    suspend fun reviews(
        severity: String,
        limit: Int = 30,
        before: Double? = null,
        after: Double? = null,
        cameras: List<String> = emptyList(),
        labels: List<String> = emptyList(),
        showReviewed: Boolean = false,
    ): List<FrigateReview> = authorized { baseUrl, token ->
        api.reviews(
            baseUrl,
            token,
            severity = severity,
            limit = limit,
            before = before,
            after = after,
            cameras = cameras,
            labels = labels,
            showReviewed = showReviewed,
        )
    }

    /** The moments Frigate flagged on one camera over a stretch of the day. */
    suspend fun reviewSpikes(camera: String, after: Double, before: Double): List<FrigateReview> =
        authorized { baseUrl, token -> api.reviewSpikes(baseUrl, token, camera, after, before) }

    /**
     * One review as the server holds it now. Opening a review asks for it again,
     * because Frigate writes the words about it a minute or two after it ends.
     */
    suspend fun review(id: String): FrigateReview =
        authorized { baseUrl, token -> api.review(baseUrl, token, id) }

    /**
     * Says a review has been watched. Frigate keeps that on the server, so the
     * web page drops it from its list too.
     */
    suspend fun markReviewReviewed(reviewId: String) {
        authorized { baseUrl, token ->
            api.markReviews(baseUrl, token, listOf(reviewId), reviewed = true)
        }
    }

    /** Says a set of reviews has been watched, or puts them back. */
    suspend fun markReviews(ids: List<String>, reviewed: Boolean) {
        authorized { baseUrl, token -> api.markReviews(baseUrl, token, ids, reviewed) }
    }

    /**
     * What each day held and how much of it has been watched, narrowed the way
     * the list is: the camera you picked, and the labels this tab is set to.
     */
    suspend fun reviewDayCounts(
        cameras: List<String> = emptyList(),
        labels: List<String> = emptyList(),
    ): List<ReviewDayCount> = authorized { baseUrl, token ->
        api.reviewDayCounts(baseUrl, token, cameras, labels)
    }

    /**
     * The picture for a review. Frigate keeps a thumbnail for the review itself,
     * but that file sits behind the web server rather than the API, so this uses
     * the thumbnail of the first thing it saw instead, which is the same picture.
     */
    suspend fun reviewThumbnail(review: FrigateReview): ByteArray? =
        review.detections.firstOrNull()?.let { thumbnail(it) }

    /** The objects this server has been set up to look for, which is what Explore filters by. */
    suspend fun labels(): List<String> = authorized { baseUrl, token ->
        // Lower case, the way Frigate names them on an event. The screens
        // capitalise them for display.
        api.labels(baseUrl, token).distinct().sorted()
    }

    /** The names Frigate can recognise, as a sub label on the events it records. */
    suspend fun subLabels(): List<String> = runCatching {
        authorized { baseUrl, token -> api.subLabels(baseUrl, token) }
    }.getOrDefault(emptyList()).distinct().sorted()

    suspend fun setAlerts(next: ServerPrefs.Alerts) {
        val server = activeServer() ?: return
        prefs.writeAlerts(server, next)
        _alerts.value = next
        alertsLoaded = true
    }

    /**
     * Keeps this phone's quiet hours. The service watches the same value, so a
     * change here is felt by the notification line at once.
     */
    suspend fun setQuietTimes(next: QuietTimes) {
        val server = activeServer() ?: return
        prefs.writeQuietTimes(server, next)
        _quietTimes.value = next
        quietTimesLoaded = true
    }

    /**
     * Keeps the objects this phone lets buzz. It is written here and nowhere
     * else: no request goes to the server, and its own pages and other phones
     * don't move. The service watches the same value, so a change is felt by the
     * buzz and the notification line at once.
     */
    suspend fun setAlertObjects(next: AlertObjects) {
        val server = activeServer() ?: return
        prefs.writeAlertObjects(server, next)
        _alertObjects.value = next
        alertObjectsLoaded = true
    }

    /**
     * Keeps the people this phone stays quiet about. Written here and nowhere else:
     * no request goes to the server, and its own pages and other phones don't move.
     */
    suspend fun setAlertPeople(names: Set<String>) {
        val server = activeServer() ?: return
        val next = AlertPeople(names)
        prefs.writeAlertPeople(server, next)
        _alertPeople.value = next
        alertPeopleLoaded = true
    }

    /**
     * The names Frigate has learned to recognise, which is what the phone's people
     * list offers. Null when the server couldn't be asked: the page then lists only
     * the names already left out.
     */
    suspend fun knownPeople(): List<String>? = runCatching {
        authorized { baseUrl, token -> api.subLabels(baseUrl, token) }
    }.getOrNull()
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?.sorted()

    /**
     * What the phone's picker offers: the cameras this account may see, and the
     * objects each one can make an alert from.
     *
     * It is built from the settings answer the phone already keeps, so the page
     * opens with the server away. Null when neither the server nor the phone's
     * copy says anything, which is a phone that has never read them.
     */
    suspend fun alertObjectChoices(): AlertObjectChoices? {
        val answer = runCatching { settingsAnswer() }.getOrNull() ?: return null
        val settings = answer.settings
        val review = settings.config.reviewSettings()
        // Only a camera that makes an alert has anything to narrow: one whose alerts
        // are off in Alerts and detections never reports one.
        val cameras = settings.config.visibleCameras(settings.profile.allowedCameras)
            .filter { review.alertLabelsFor(it.name).isNotEmpty() }
        return AlertObjectChoices(
            cameras = cameras,
            labels = cameras.associate { camera ->
                // Only what the camera can actually report: an alert label for an
                // object nobody tracks can never happen, so it is not offered here.
                camera.name to review.alertLabelsFor(camera.name)
                    .filter { it in settings.config.possibleLabels(camera.name) }
            },
        )
    }

    /**
     * Keeps this phone's own list of cameras: which are off, what it calls them,
     * and their order. The screens and the service watch the same value, so a
     * change in Settings lands on the tiles and on the buzz at once.
     */
    suspend fun setCameraList(next: CameraList) {
        val server = activeServer() ?: return
        prefs.writeCameraList(server, next)
        _cameraList.value = next
        cameraListLoaded = true
    }

    /** Turns one camera off on this phone, or back on. */
    suspend fun setCameraShown(camera: String, on: Boolean) =
        setCameraList(_cameraList.value.withHidden(camera, !on))

    /** Gives one camera a name of this phone's own. A blank name goes back to the server's. */
    suspend fun setCameraName(camera: String, name: String) =
        setCameraList(_cameraList.value.naming(camera, name))

    /** Moves one camera in the order, the way a row dragged up or down leaves it. */
    suspend fun moveCamera(from: String, to: Int, among: List<String>) =
        setCameraList(_cameraList.value.moving(from, to, among))

    /** Whether the reviews list shows the ones you've already watched. */
    suspend fun readShowReviewed(): Boolean = prefs.readShowReviewed()

    suspend fun setShowReviewed(on: Boolean) = prefs.writeShowReviewed(on)

    /** Which way the cameras are laid out, remembered between launches. */
    suspend fun readCameraLayout(): Int {
        val server = activeServer() ?: return 0
        return prefs.readCameraLayout(server)
    }

    suspend fun setCameraLayout(layout: Int) {
        val server = activeServer() ?: return
        prefs.writeCameraLayout(server, layout)
    }

    /** Whether the home page tiles play live. A flow, so Settings is felt at once. */
    val liveTiles: Flow<Boolean> get() = prefs.liveTiles

    suspend fun setLiveTiles(on: Boolean) = prefs.writeLiveTiles(on)

    /** Which colours the app uses. A flow, so Settings changes it at once. */
    val theme: Flow<Int> get() = prefs.theme

    suspend fun setTheme(mode: Int) = prefs.writeTheme(mode)

    /**
     * The picture size and frame rate this camera was last watched at, or null the
     * first time it is opened.
     */
    suspend fun streamChoice(camera: String): Pair<Int, Int>? = prefs.readStreamChoice(camera)

    suspend fun setStreamChoice(camera: String, height: Int, rate: Int) =
        prefs.writeStreamChoice(camera, height, rate)

    /**
     * Everything this account has set on this phone, ready to be written to a file.
     *
     * It is read from the answers the screens already have rather than from the
     * store, so the file says what the app shows: something changed a moment ago
     * is in it.
     */
    suspend fun backup(): SettingsBackup {
        val server = activeServer()
        return SettingsBackup(
            savedAt = System.currentTimeMillis(),
            version = BuildConfig.VERSION_NAME,
            from = Where(
                address = server?.baseUrl.orEmpty(),
                username = server?.username.orEmpty(),
            ),
            alerts = AlertsBlock(
                on = _alerts.value.on,
                // A hush is about the next hour, not about how this phone is set up.
                quietTimes = _quietTimes.value.copy(hushUntil = 0L),
                objects = _alertObjects.value,
                people = _alertPeople.value,
            ),
            cameras = _cameraList.value,
            looks = LooksBlock(
                layout = server?.let { prefs.readCameraLayout(it) } ?: 0,
                theme = prefs.theme.first(),
                language = Language.current().tag,
                liveTiles = prefs.liveTiles.first(),
                showReviewed = prefs.readShowReviewed(),
            ),
            sizes = prefs.readStreamChoices().mapValues { (_, size) ->
                PictureSize(height = size.first, rate = size.second)
            },
        )
    }

    /**
     * Puts [backup] in place of this account's settings, and reads them again.
     *
     * Every group the file carries is written, and one it leaves out is left as it
     * is. The values the screens and the alert service read are then read again,
     * so the cameras, the quiet hours and the watching line move together rather
     * than waiting for a restart.
     *
     * A file with nothing in it for this version writes nothing and says so. The
     * language is handed back rather than picked here: it is Android's own choice,
     * and picking it redraws the app, which is the screen's business.
     */
    suspend fun restoreSettings(backup: SettingsBackup): RestoreResult {
        val server = activeServer() ?: return RestoreResult()

        // The file says who made it. Two people can share one phone, so a file made
        // by the other one is turned down rather than dropping their hidden cameras,
        // their names and their quiet hours onto this account.
        if (!backup.belongsTo(server.username)) {
            return RestoreResult(savedBy = backup.from.username.trim())
        }

        var applied = false

        backup.alerts?.let { block ->
            prefs.writeAlerts(server, ServerPrefs.Alerts(on = block.on))
            // Whatever the file says, the hush it was saved mid-way through stays
            // behind: it is a moment, and the moment has long gone.
            prefs.writeQuietTimes(server, block.quietTimes.copy(hushUntil = 0L))
            prefs.writeAlertObjects(server, block.objects)
            prefs.writeAlertPeople(server, block.people)
            applied = true
        }

        backup.cameras?.let { list ->
            prefs.writeCameraList(server, list)
            applied = true
        }

        backup.looks?.let { looks ->
            prefs.writeCameraLayout(server, looks.layout)
            prefs.writeTheme(looks.theme)
            prefs.writeLiveTiles(looks.liveTiles)
            prefs.writeShowReviewed(looks.showReviewed)
            applied = true
        }

        backup.sizes?.let { sizes ->
            prefs.writeStreamChoices(
                sizes.filterValues { it.height > 0 && it.rate > 0 }
                    .mapValues { (_, size) -> size.height to size.rate },
            )
            applied = true
        }

        if (!applied) return RestoreResult()

        loadChoices()
        return RestoreResult(applied = true, language = backup.looks?.language)
    }

    /** When a backup was last written from this account, and the name it was given. */
    suspend fun lastSave(): LastSave =
        activeServer()?.let { prefs.readLastSave(it) } ?: LastSave()

    /** Notes where a backup went, so the row can say where your settings are. */
    suspend fun noteSaved(name: String) {
        val server = activeServer() ?: return
        prefs.writeLastSave(server, LastSave(at = System.currentTimeMillis(), name = name))
    }

    suspend fun latestFrame(camera: String, height: Int = 360): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.latestFrame(baseUrl, token, camera, height) }
    }.getOrNull()

    /**
     * One sentence about what this camera is seeing right now, from three frames a
     * second apart.
     *
     * Frigate writes its own description of a review once the review has finished,
     * which can be half a minute after the buzz. This asks straight away instead, so
     * the notification fills in while you are still looking at it. The server's own
     * sentence lands on the same notification later, if there is one.
     *
     * [known] is what Frigate has already recognised, such as the name of the person
     * in the picture. Given only pictures, the model would call every visitor a man.
     * When nothing has been recognised yet, [eventId] is asked for, because face
     * recognition names the object a second or two after it first appears.
     *
     * Null means no sentence: the camera sent nothing, or the question got no answer.
     * A buzz never waits on this.
     */
    suspend fun quickDescription(
        camera: String,
        known: List<String> = emptyList(),
        eventId: String? = null,
    ): String? = runCatching {
        val frames = buildList {
            repeat(QuickFrames) { index ->
                if (index > 0) delay(QuickGapMillis)
                latestFrame(camera, height = QuickFrameHeight)?.let { add(it) }
            }
        }
        if (frames.isEmpty()) return@runCatching null

        // Two seconds have gone by while those arrived, which is enough for the
        // server to have matched a face. Asked for after the pictures, so a camera
        // that sent nothing costs one request rather than two.
        val names = known.ifEmpty { recognised(eventId) }
        authorized { baseUrl, token -> api.describe(baseUrl, token, frames, names) }
    }.getOrNull()

    /** The name face recognition gave the thing in that event, if it gave one. */
    private suspend fun recognised(eventId: String?): List<String> {
        if (eventId == null) return emptyList()
        val found = runCatching {
            authorized { baseUrl, token -> api.event(baseUrl, token, eventId) }
        }.getOrNull() ?: return emptyList()
        return listOfNotNull(found.subLabel?.takeIf { it.isNotBlank() })
    }

    /**
     * What this camera can actually give: the size of the picture it sends, and the
     * frames a second it sends them at.
     *
     * A saved choice is only worth honouring inside these. Asking for more is what
     * makes the server scale frames up and the phone fall behind, and the bill is paid
     * by every camera on the box.
     *
     * The answer is kept for a while, because opening a camera should not cost two
     * round trips, and a camera does not change size between one tap and the next.
     */
    suspend fun streamLimits(camera: String): StreamLimits {
        val now = System.currentTimeMillis()
        limits[camera]?.let { (at, found) ->
            if (now - at < LimitsStandMillis) return found
        }

        // Both answers are wanted and neither needs the other, so they are asked for
        // at the same time rather than one after the other.
        val (picture, sending) = coroutineScope {
            val height = async { nativeHeight(camera) }
            val stats = async { stats() }
            height.await() to stats.await()
        }

        val found = StreamLimits(
            height = (picture ?: StreamLimits.MostHeight)
                .coerceAtMost(StreamLimits.MostHeight),
            rate = (sending?.cameras?.get(camera)?.settingRate ?: StreamLimits.MostRate)
                .coerceIn(StreamLimits.LeastRate, StreamLimits.MostRate),
        )
        limits[camera] = now to found
        return found
    }

    /**
     * The height of the picture the camera itself sends, or null when it cannot be
     * asked. The picture is read for its header alone, so this costs one small
     * response rather than a bitmap.
     */
    private suspend fun nativeHeight(camera: String): Int? = runCatching {
        val frame = authorized { baseUrl, token ->
            api.latestFrame(baseUrl, token, camera, height = null)
        }
        MjpegReader.sizeOf(frame).takeIf { it > 0 }
    }.getOrNull()

    /**
     * The server's own census of every camera, kept for a few seconds. The live screen
     * asks it whether its camera is still sending, and asks it again for the rate the
     * camera sends at, which is one answer worth having once rather than twice.
     */
    private suspend fun stats(): FrigateStats? {
        val now = System.currentTimeMillis()
        statsAt?.let { (at, found) -> if (now - at < StatsStandMillis) return found }
        val found = runCatching {
            authorized { baseUrl, token -> api.stats(baseUrl, token) }
        }.getOrNull()
        if (found != null) statsAt = now to found
        return found
    }

    /**
     * Whether a camera is sending frames at all.
     *
     * Frigate counts the frames arriving from each camera over the last few seconds,
     * so a camera whose picture has stopped counts zero. The stream goes on handing
     * back the last frame it sent, which is why this is asked separately: a stale
     * picture on screen looks exactly like a live one.
     *
     * Null means no answer either way, which is what the screens show as nothing
     * unusual: the server has only just started, it has no such camera, or the call
     * failed. Calling a camera offline on a guess is worse than saying nothing.
     */
    suspend fun cameraSending(camera: String): Boolean? {
        val answer = stats() ?: return null
        // For the first couple of minutes Frigate has not counted anything yet, and
        // every camera on a fresh server would read as stopped.
        if (answer.service.uptime?.let { it < WarmUpSeconds } == true) return null
        return answer.cameras[camera]?.let { it.cameraFps > 0.0 }
    }

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
     * The address of an event's clip and the token it needs. Frigate cuts the clip
     * out of your recordings on request, so it is fetched by the player rather than
     * downloaded here.
     */
    fun clipSource(eventId: String): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(url = "${current.baseUrl}/api/events/$eventId/clip.mp4", token = token)
    }

    /**
     * The address of a camera's sound and the token to send with it.
     *
     * Frigate's API carries no sound at all, so this goes to go2rtc through the
     * proxy, under a path of its own. It is a stream rather than a file: it runs
     * until it is closed.
     */
    fun soundSource(camera: String): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(url = "${current.baseUrl}/api/stream.aac?src=$camera", token = token)
    }

    /**
     * The address of a camera's own video and the token to send with it.
     *
     * This is the camera's own H.264, left as the camera sends it, coming from
     * go2rtc through the same proxy path the sound uses. It carries the sound with
     * it. Frigate's own stream is one frame per camera, kept at the size it decodes
     * for detection, so this is the only way to see what the camera actually sees.
     */
    fun liveSource(camera: String): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(url = "${current.baseUrl}/api/stream.mp4?src=$camera", token = token)
    }

    /**
     * A review's clip. Frigate cuts it from your recordings when it is asked for
     * one, so the period it covers is the review's start and end.
     */
    fun reviewClipSource(reviewId: String): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(url = "${current.baseUrl}/api/review/$reviewId/clip.mp4", token = token)
    }

    /**
     * A stretch of a camera's recordings, cut by Frigate as it sends it. The
     * moments are epoch seconds, which is what its own timeline works in, and they
     * are sent to the millisecond: which frame a cut begins on turns on a fraction
     * of a second.
     */
    fun recordingClipSource(camera: String, start: Double, end: Double): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(
            url = "${current.baseUrl}/api/$camera/start/${moment(start)}/end/${moment(end)}/clip.mp4",
            token = token,
        )
    }

    /** A moment written the way a URL is read: digits, then a dot. */
    private fun moment(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds)

    /** Every clip kept on the server, newest first. */
    suspend fun exports(): List<Export> =
        authorized { baseUrl, token -> api.exports(baseUrl, token) }

    /**
     * Asks the server to keep a stretch of a camera's recordings, and answers with
     * the id it is kept under. The cut happens on the server, so the phone can be
     * put down while it runs.
     */
    suspend fun keepClip(camera: String, start: Double, end: Double, name: String): String =
        authorized { baseUrl, token ->
            api.startExport(baseUrl, token, camera, start, end, name)
        }

    /** Gives a kept clip another name. The file on disk keeps its own. */
    suspend fun renameExport(id: String, name: String) {
        authorized { baseUrl, token -> api.renameExport(baseUrl, token, id, name) }
    }

    /** Deletes kept clips from the server. The files go with them. */
    suspend fun deleteExports(ids: List<String>) {
        authorized { baseUrl, token -> api.deleteExports(baseUrl, token, ids) }
    }

    /**
     * The address of a kept clip's file and the token to send with it.
     *
     * Unlike a clip cut on request, this one is a finished file with a length and
     * support for ranges. It is still fetched whole, so the player has all of it and
     * can be scrubbed.
     */
    fun exportSource(export: Export): ClipSource {
        val current = stored ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return ClipSource(url = "${current.baseUrl}${export.videoUrlPath}", token = token)
    }

    /** A kept clip as a file on the phone, fetched if it isn't already there. */
    suspend fun exportFile(export: Export, onProgress: (Long) -> Unit): File =
        clipFile(
            source = exportSource(export),
            key = "kept-${export.id}",
            onProgress = onProgress,
            notThere = context.words(R.string.api_clip_gone),
        )

    /** The picture Frigate made for a kept clip, or null when it made none. */
    suspend fun exportThumbnail(export: Export): ByteArray? = runCatching {
        authorized { baseUrl, token -> api.exportThumbnail(baseUrl, token, export) }
    }.getOrNull()

    /** Which days this camera kept recordings for. */
    suspend fun recordingDays(camera: String, timezone: String): List<RecordingDay> =
        authorized { baseUrl, token -> api.recordingDays(baseUrl, token, camera, timezone) }

    /**
     * The stretches of recording between two moments, file by file, gaps and all.
     *
     * The files Frigate records into are ten seconds each and are what a clip is
     * cut from, so they are handed over as they are. A run of them is joined where
     * it is drawn, by the screen, rather than here.
     */
    suspend fun recordingSpans(camera: String, after: Double, before: Double): List<RecordingSpan> =
        authorized { baseUrl, token ->
            api.recordingSpans(baseUrl, token, camera, after, before)
        }

    /** A picture of the recording at that moment, for while the clip arrives. */
    suspend fun recordingStill(camera: String, moment: Double, height: Int = 720): ByteArray? =
        runCatching {
            authorized { baseUrl, token -> api.recordingStill(baseUrl, token, camera, moment, height) }
        }.getOrNull()

    /**
     * The clip as a file on the phone, fetched if it isn't already there.
     *
     * Frigate cuts a clip while it sends it, with no length and no support for
     * ranges, and a phone on a home connection can see it arrive in dribs. A
     * player reading that stream directly calls it stuck after ten seconds
     * without progress and gives up. Copied to a file first, the player has the
     * whole clip: it starts at once, it can be scrubbed, and a bad minute on the
     * network costs a retry rather than the clip.
     */
    suspend fun clipFile(
        source: ClipSource,
        key: String,
        onProgress: (Long) -> Unit,
        notThere: String? = null,
    ): File = withContext(Dispatchers.IO) {
        val name = key.replace(NOT_IN_A_FILENAME, "_") + ".mp4"
        val clip = File(clipDir, name)
        if (clip.isFile && clip.length() > 0) {
            // A clip kept from an earlier version, or one that arrived sound,
            // gets the same check. It costs a few milliseconds and does nothing
            // at all to a file that is already right.
            ClipRepair.repair(clip)
            return@withContext clip
        }

        clipDir.mkdirs()
        forgetOldClips()

        val part = File(clipDir, "$name.part")
        val job = coroutineContext[Job]
        try {
            api.downloadClip(source, part, onProgress, { job?.isActive != false }, notThere)
            // The clip arrives with the recordings' timing, which can leave a
            // player holding the first frame for hours. Straightening it costs a
            // moment and changes nothing on a clip that is already sound.
            if (ClipRepair.repair(part)) android.util.Log.i("Frigo", "clip timing repaired")
            if (!part.renameTo(clip)) throw IOException(context.words(R.string.api_clip_not_moved))
            clip
        } catch (failure: Throwable) {
            part.delete()
            clip.delete()
            throw failure
        }
    }

    /**
     * Clips are the biggest thing the app keeps. Yesterday's go, because nothing
     * older can be played from the screen, and past a limit the oldest of today's
     * go too: a run through an hour at 16× fetches thirty of them.
     */
    private fun forgetOldClips() {
        val files = clipDir.listFiles()?.filter { it.isFile } ?: return
        val yesterday = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        files.filter { it.lastModified() < yesterday }.forEach { it.delete() }

        var room = files.filter { it.exists() }.sumOf { it.length() }
        files.filter { it.exists() }.sortedBy { it.lastModified() }.forEach { file ->
            if (room > ClipsBytes) {
                room -= file.length()
                file.delete()
            }
        }
    }

    /**
     * Frigate's message feed, for the service that watches for alerts. Null when
     * nobody is signed in, which is also when there is nothing to watch.
     *
     * The saved sign-in is read here when this process has not loaded it yet. The
     * service starts on its own after a phone restart, before any screen has run,
     * and without this it would find nothing and give up.
     */
    suspend fun streamSource(): StreamSource? {
        val current = activeServer() ?: return null
        val token = current.token ?: return null
        return StreamSource(url = "${current.baseUrl}/ws", token = token)
    }

    /**
     * Runs [block] with the session token. A 401 means the token aged out, so the
     * session is renewed before giving up on it.
     */
    private suspend fun <T> authorized(block: suspend (baseUrl: String, token: String) -> T): T {
        val current = activeServer() ?: throw FrigateException(401, notSignedIn)
        val token = current.token ?: throw FrigateException(401, notSignedIn)
        return try {
            withContext(Dispatchers.IO) { block(current.baseUrl, token) }
        } catch (e: FrigateException) {
            if (!e.isAuthFailure) throw e
            val renewed = mutex.withLock { renewLocked() }
            withContext(Dispatchers.IO) { block(current.baseUrl, renewed) }
        }
    }

    /**
     * A session that works, starting from the token in hand.
     *
     * Frigate lengthens a session in place but only while it still has time on it,
     * and only for a token sent as a cookie, which is not how this app sends one.
     * So a token that has run out is answered with a sign-in from the password the
     * phone kept. A phone with no password kept gets the sign-in page instead.
     *
     * Called with the mutex held, so a screenful of failed requests makes one
     * sign-in rather than one each.
     */
    private suspend fun renewLocked(): String {
        val current = stored ?: throw FrigateException(401, notSignedIn)

        current.token?.let { token ->
            val fresh = runCatching {
                withContext(Dispatchers.IO) { api.checkToken(current.baseUrl, token) }
            }.getOrNull()
            if (fresh != null) {
                if (fresh != token) saveToken(fresh)
                return fresh
            }
        }

        signInLocked(current)?.let { return it }

        forgetToken()
        val refused = current.password != null
        val words = context.words(
            if (refused) R.string.api_kept_password_refused else R.string.api_session_expired,
        )
        _gate.value = Gate.SignedOut(
            message = words,
            address = current.baseUrl,
            username = current.username,
        )
        throw FrigateException(401, words)
    }

    /**
     * Signs in with the password kept on the phone, and answers the token it comes
     * back with, or null when there is no password or the server turned it down.
     *
     * A password that is turned down is dropped, along with the intent to keep it:
     * it has been changed on the server, and asking again at every launch would
     * only be told the same thing, while Frigate counts failed sign-ins.
     *
     * Called with the mutex held. A server that cannot be reached is thrown rather
     * than answered, since that says nothing about the password.
     */
    private suspend fun signInLocked(current: ServerPrefs.Stored): String? {
        val password = current.password ?: return null
        val token = try {
            withContext(Dispatchers.IO) {
                api.login(current.baseUrl, current.username, password)
            }
        } catch (refused: FrigateException) {
            if (!refused.isAuthFailure) throw refused
            val next = current.copy(token = null, password = null, keepPassword = false)
            stored = next
            keep(next)
            return null
        }
        val next = current.copy(token = token)
        stored = next
        keep(next)
        return token
    }

    private suspend fun saveToken(token: String) {
        val current = stored ?: return
        val next = current.copy(token = token)
        stored = next
        keep(next)
    }

    /** Keeps the address and username, drops the token that no longer works. */
    private suspend fun forgetToken() {
        val current = stored ?: return
        val next = current.copy(token = null)
        stored = next
        keep(next)
    }

    /** Writes a changed copy of one server back into the list. */
    private suspend fun keep(server: ServerPrefs.Stored) {
        val servers = known().map { if (it.baseUrl == server.baseUrl) server else it }
        persist(servers, stored?.baseUrl)
    }

    /**
     * Takes what people actually type - `frigate.example.com`, a full URL, or
     * one that already ends in /api - and returns a clean base.
     */
    fun normalizeBaseUrl(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) throw FrigateException(0, context.words(R.string.api_enter_address))
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
            ?: throw FrigateException(0, context.words(R.string.api_not_a_web_address))
        return parsed.toString().trimEnd('/')
    }

    companion object {
        /** Clips are named after the review or event they belong to. */
        private val NOT_IN_A_FILENAME = Regex("[^A-Za-z0-9._-]")

        /** How much of the phone the downloaded clips may take: 600 MB. */
        private const val ClipsBytes = 600L * 1024 * 1024

        /** Seconds after a server starts during which its frame counts mean nothing. */
        private const val WarmUpSeconds = 120.0

        /**
         * How long an answer about a camera's own size and rate stands. Long, because
         * a camera is not swapped between one tap and the next, and asking again on
         * every camera change would put two requests in front of the picture.
         */
        private const val LimitsStandMillis = 30L * 60 * 1000

        /** How long the server's census stands. Two calls in a row share one answer. */
        private const val StatsStandMillis = 15_000L

        /** How many frames a quick description looks at, and how far apart they are. */
        private const val QuickFrames = 3
        private const val QuickGapMillis = 1_000L

        /**
         * The height those frames are asked for. A small picture is enough to see
         * what a person is doing, and three of them go up to the server at once.
         */
        private const val QuickFrameHeight = 240
    }
}

/**
 * The cameras to show, and only the ones this user may watch, named the way the
 * app names them. A user who may see everything gets no list, which means all.
 */
private fun FrigateConfig.visibleCameras(allowed: List<String>?): List<Camera> = cameras
    .filterValues { it.enabled }
    .filter { (name, _) -> allowed == null || name in allowed }
    .map { (name, config) ->
        Camera(name = name, title = config.friendlyName ?: prettyCameraName(name))
    }
    .sortedBy { it.title.lowercase() }
