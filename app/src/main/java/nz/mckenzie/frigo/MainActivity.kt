package nz.mckenzie.frigo

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.frigo.ui.FrigoApp
import nz.mckenzie.frigo.ui.theme.FrigoTheme
import nz.mckenzie.frigo.ui.theme.ThemeMode

/**
 * AppCompatActivity rather than the plain one, because that is what carries the
 * app's own language choice on a phone older than Android 13. Everything on the
 * screen is Compose; the activity itself adds nothing else.
 */
class MainActivity : AppCompatActivity() {

    /**
     * The camera an alert you tapped is about, until the app opens it.
     *
     * Tapping an alert while the app is already open now comes here as a new
     * intent instead of a second copy of the screen, so this is state rather
     * than a value read once at startup.
     */
    private val cameraToWatch = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The list is empty when the screen comes back from a turn of the phone,
        // and the app has already put you where you were.
        if (savedInstanceState == null) cameraToWatch.value = intent?.getStringExtra(EXTRA_CAMERA)

        val container = (application as FrigoApplication).container

        setContent {
            // The colours follow the choice in Settings, which is read here so
            // every screen, the sign-in page included, is under it.
            val theme by container.repository.theme.collectAsStateWithLifecycle(
                initialValue = ThemeMode.System.ordinal,
            )
            FrigoTheme(ThemeMode.entries.getOrElse(theme) { ThemeMode.System }) {
                FrigoApp(
                    container = container,
                    openCamera = cameraToWatch.value,
                    onCameraOpened = { cameraToWatch.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cameraToWatch.value = intent.getStringExtra(EXTRA_CAMERA)
    }

    companion object {
        /** The camera an alert was about, carried by the notification's tap. */
        const val EXTRA_CAMERA = "nz.mckenzie.frigo.extra.CAMERA"
    }
}
