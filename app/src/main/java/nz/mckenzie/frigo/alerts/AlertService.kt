package nz.mckenzie.frigo.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import nz.mckenzie.frigo.FrigoApplication
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.StreamSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Stays connected to your Frigate server and buzzes when it sees one of the
 * things you asked about.
 *
 * Frigate pushes each new event down a websocket at `/ws`. Keeping that socket
 * open is the only way the app hears about an event while it is closed, and
 * Android only lets an app do that from a service with a notification on
 * screen, which is the quiet "Watching for alerts" one.
 *
 * Everything about this is optional. With the switch off in Settings the service
 * never starts, and the app behaves as it always did.
 */
class AlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var container: AppContainer

    private var job: Job? = null
    private var state: WatchState = WatchState.Connecting

    /** True once Android has the foreground notification, so updates land on it. */
    private var shown = false

    /** When each camera last buzzed, so one visitor does not set the phone off twice. */
    private val lastBuzzed = mutableMapOf<String, Long>()

    /**
     * A client of its own: the feed stays open with no read timeout, and a ping
     * every half minute both keeps the phone's network path open and notices a
     * connection that has quietly died.
     */
    private val socketClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onCreate() {
        super.onCreate()
        container = (application as FrigoApplication).container
        createChannels()

        // The quiet notification counts the cameras you ticked, so it follows the
        // Settings screen: tick a camera and it says one camera, not none. The
        // saved choices are read first, because after a phone restart this service
        // runs before any screen has, and it would otherwise start with nothing.
        scope.launch {
            container.repository.loadAlerts()
            container.repository.alerts.collect { showWatching() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android wants the notification up within a few seconds of the start, so
        // it goes first and the connection work happens after it.
        showWatching()
        // A finished job means the last attempt gave up on an expired session. If
        // you sign in again the app starts this service again, and it tries once more.
        if (job?.isActive != true) job = scope.launch { listen() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Stays connected, and reconnects for as long as the phone keeps the service. */
    private suspend fun listen() {
        var wait = RECONNECT_FIRST
        while (currentCoroutineContext().isActive) {
            val source = container.repository.streamSource()
            if (source == null) {
                setState(WatchState.SignedOut)
                return
            }

            when (val outcome = runSession(source)) {
                Session.Closed -> wait = RECONNECT_FIRST
                Session.Refused -> {
                    // No point knocking: Frigate will keep saying no until you sign in.
                    setState(WatchState.SignedOut)
                    return
                }
                is Session.Failed -> {
                    wait = (wait * 2).coerceAtMost(RECONNECT_LONGEST)
                    setState(WatchState.NotReaching(outcome.detail))
                }
            }

            delay(wait)
        }
    }

    /**
     * One connection, start to end. Returns when the socket closes or breaks, so
     * the caller can decide whether to try again.
     */
    private suspend fun runSession(source: StreamSource): Session {
        val request = Request.Builder()
            .url(source.url)
            .header("Authorization", "Bearer ${source.token}")
            .build()

        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(outcome: Session) {
                if (done.compareAndSet(false, true)) cont.resume(outcome)
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!cont.isActive) {
                        webSocket.close(NORMAL_CLOSURE, null)
                        return
                    }
                    setState(WatchState.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(Session.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.code
                    android.util.Log.w(TAG, "socket failed: ${t.javaClass.simpleName} status=$status ${t.message}")
                    finish(
                        when {
                            status == 401 || status == 403 -> Session.Refused
                            else -> Session.Failed("Can't reach your server.")
                        }
                    )
                }
            }

            val socket = runCatching { socketClient.newWebSocket(request, listener) }
                .getOrElse {
                    finish(Session.Failed("Can't reach your server."))
                    return@suspendCancellableCoroutine
                }

            cont.invokeOnCancellation { socket.close(NORMAL_CLOSURE, null) }
        }
    }

    /**
     * Frigate sends every message it publishes, each as `{"topic": ..., "payload":
     * ...}`. The ones that matter here are the new events, and only the ones you
     * ticked.
     *
     * The topic is `events`. Frigate's own page listens for that name, and the
     * server strips the `frigate/` prefix its MQTT brokers use before it goes out
     * on the socket. Some builds keep the prefix, so both names are taken.
     */
    private fun onMessage(text: String) {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val topic = root.text("topic") ?: return
        if (topic.removePrefix("frigate/") != "events") return

        // The payload is the event as a JSON string, but a plain object arrives
        // the same way in some builds.
        val raw = root["payload"] ?: return
        val payload = when (raw) {
            is JsonPrimitive -> raw.content
            else -> raw.toString()
        }
        val message = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return

        // `new` is the moment an object first appears. `update` follows every time
        // its details change, and `end` when it leaves, so only `new` buzzes.
        if (message.text("type") != "new") return
        val event = (message["after"] as? JsonObject) ?: return

        val id = event.text("id") ?: return
        val camera = event.text("camera") ?: return
        val label = event.text("label") ?: return

        if (!container.repository.alerts.value.wants(camera, label)) return

        val now = System.currentTimeMillis()
        val last = lastBuzzed[camera] ?: 0L
        if (now - last < BUZZ_GAP_MS) return
        lastBuzzed[camera] = now

        scope.launch {
            val picture = container.repository.thumbnail(id)
            Alerts.event(
                context = this@AlertService,
                event = FrigateEvent(
                    id = id,
                    camera = camera,
                    label = label,
                    subLabel = event.text("sub_label"),
                ),
                picture = picture,
            )
        }
    }

    private fun showWatching() {
        val notification = watchingNotification()
        if (shown) {
            // Already up: replace the text rather than take the service down and
            // put it back, which would flicker in the shade.
            NotificationManagerCompat.from(this).notify(Alerts.WATCHING_ID, notification)
            return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Alerts.WATCHING_ID, notification, type)
        shown = true
    }

    /** Keeps the quiet notification in step with the connection. */
    private fun setState(next: WatchState) {
        if (state == next) return
        state = next
        showWatching()
    }

    private fun watchingNotification(): Notification =
        Alerts.watching(this, state, container.repository.alerts.value).build()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // The watching one is quiet: it is there for Android, not for you.
        manager.createNotificationChannel(
            NotificationChannel(
                Alerts.CHANNEL_WATCHING,
                "Watching",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Says Frigo is connected and waiting for alerts."
                setShowBadge(false)
            }
        )

        // The event one makes a sound, because that is the whole point of it.
        manager.createNotificationChannel(
            NotificationChannel(
                Alerts.CHANNEL_EVENTS,
                "Alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tells you when Frigate sees one of the things you picked."
            }
        )
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private sealed interface Session {
        /** The socket closed from either end. Reconnect straight away. */
        data object Closed : Session

        /** The server would not let us in, which means the sign-in has gone. */
        data object Refused : Session

        /** The socket broke. Wait a little and try again. */
        data class Failed(val detail: String) : Session
    }

    companion object {
        private const val ACTION_START = "nz.mckenzie.frigo.START_WATCHING"
        private const val ACTION_STOP = "nz.mckenzie.frigo.STOP_WATCHING"
        private const val NORMAL_CLOSURE = 1000
        private const val TAG = "AlertService"

        private const val RECONNECT_FIRST = 2_000L
        private const val RECONNECT_LONGEST = 60_000L

        /** One visitor should be one buzz, even if Frigate starts a new event for them. */
        private const val BUZZ_GAP_MS = 20_000L

        /** Turns the service on. Safe to call when it is already running. */
        fun start(context: Context) {
            val intent = Intent(context, AlertService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        /** Turns it off and takes the watching notification away. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AlertService::class.java))
        }
    }
}
