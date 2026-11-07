package nz.mckenzie.frigo.tv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import nz.mckenzie.frigo.tv.data.TvApi
import nz.mckenzie.frigo.tv.data.TvPrefs
import nz.mckenzie.frigo.tv.data.TvRepository
import nz.mckenzie.frigo.tv.ui.FrigoTvTheme
import nz.mckenzie.frigo.tv.ui.TvApp

/**
 * One screen, and it stays lit. An activity with no chrome: a television is
 * already showing the app full screen, and a status bar over a camera picture
 * is a line of nothing.
 */
class TvActivity : ComponentActivity() {

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

        val repository = TvRepository(
            prefs = TvPrefs(applicationContext),
            api = TvApi(),
        )

        setContent {
            FrigoTvTheme {
                TvApp(repository)
            }
        }
    }
}
