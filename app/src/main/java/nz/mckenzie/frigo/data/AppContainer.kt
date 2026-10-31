package nz.mckenzie.frigo.data

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-built dependency holder. The app has four screens and one server, so a
 * container beats wiring in a DI library.
 */
class AppContainer(context: Context) {

    private val requestClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // The MJPEG response never ends, so it gets its own client with no read
        // timeout. Everything else keeps a ceiling so a dead server fails fast.
        .build()

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

    private val api = FrigateApi(requestClient, streamClient, clipClient)

    val repository = FrigoRepository(api, ServerPrefs(context), context.cacheDir)
}
