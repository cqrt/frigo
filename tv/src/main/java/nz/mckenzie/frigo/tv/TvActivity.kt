package nz.mckenzie.frigo.tv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.tv.data.TvApi
import nz.mckenzie.frigo.tv.data.TvPrefs
import nz.mckenzie.frigo.tv.data.TvRepository
import nz.mckenzie.frigo.tv.ring.RingServer
import nz.mckenzie.frigo.tv.ui.FrigoTvTheme
import nz.mckenzie.frigo.tv.ui.TvApp

/**
 * One screen, and it stays lit. An activity with no chrome: a television is
 * already showing the app full screen, and a status bar over a camera picture
 * is a line of nothing.
 */
class TvActivity : ComponentActivity() {

    private var rings: RingServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A camera on a wall is watched for minutes at a time. Android's own
        // sleep would black the screen in the middle of it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val prefs = TvPrefs(applicationContext)
        val repository = TvRepository(
            prefs = prefs,
            api = TvApi(),
            cacheDir = cacheDir,
        )

        // The door the doorbell knocks on. It stays open for as long as the app
        // does, so an automation can ring it without the television being asked
        // to do anything first.
        lifecycleScope.launch {
            val key = prefs.ringKey()
            val server = RingServer(key) { camera ->
                repository.ring(camera)
                // A bell that arrives while the television is showing something
                // else asks for the picture back. Android may refuse this, which
                // is why the ring is kept either way: the next look shows it.
                runCatching {
                    startActivity(
                        Intent(this@TvActivity, TvActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                            ),
                    )
                }
            }
            rings = server
            val address = withContext(Dispatchers.IO) { server.open() }
            repository.setRingUrl(address)
            withContext(Dispatchers.IO) { server.serve() }
        }

        setContent {
            FrigoTvTheme {
                TvApp(repository)
            }
        }
    }

    override fun onDestroy() {
        rings?.close()
        rings = null
        super.onDestroy()
    }
}
