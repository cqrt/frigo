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

    private val api = FrigateApi(requestClient, streamClient)

    val repository = FrigoRepository(api, ServerPrefs(context))
}
