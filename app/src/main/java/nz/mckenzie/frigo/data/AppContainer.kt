package nz.mckenzie.frigo.data

import android.content.Context
import okhttp3.OkHttpClient
import nz.mckenzie.frigo.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Hand-built dependency holder. The app has five screens and a handful of servers,
 * so a container beats wiring in a DI library.
 */
class AppContainer(context: Context) {

    /**
     * The certificates this phone was told to trust, which every client here shares.
     * A server at home makes its own, and trusting one is a thing the owner said once.
     */
    private val trust = TrustedCertificates(context)

    private val requestClient = trust.apply(
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS),
    ).build()

    private val streamClient = requestClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // A clip is cut from recordings as it is sent, and can arrive slowly over a
    // home connection, so the read timeout is long. A response that goes quiet
    // for longer than this ends the copy with what arrived rather than failing.
    private val clipClient = requestClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api = FrigateApi(context, requestClient, streamClient, clipClient)

    /**
     * The Home Assistant in the house, which knows about a camera's light where
     * Frigate doesn't.
     */
    private val homeAssistant = HomeAssistantApi(context, requestClient)

    /** What the phone keeps, shared between the repository and the release check. */
    private val prefs = ServerPrefs(context)

    val repository = FrigoRepository(context, api, homeAssistant, prefs, trust, context.cacheDir)

    /** Asks GitHub for a newer Frigo, and fetches it. */
    val updater = Updater(requestClient, context.cacheDir)

    /**
     * The answer on whether a newer Frigo is out, kept for the whole app: the
     * Settings tab draws a dot from it, and the Updates card offers the download.
     */
    val updateWatch = UpdateWatch(updater, prefs, BuildConfig.VERSION_NAME)

    /**
     * Whether this launch is still behind the cover. It lives here rather than in
     * the activity because the activity comes and goes with the screen, and being
     * locked is about the launch.
     */
    val lock = AppLock()

    /** Hands a television the server and sign-in it needs, over the home network. */
    val tvPairing = TvPairingClient()

    /**
     * A client for a feed that never ends, with a ping to notice a connection that
     * died quietly. The alert service keeps one open, and it needs the same
     * certificate trust as everything else.
     */
    fun feedClient(): OkHttpClient = requestClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}
