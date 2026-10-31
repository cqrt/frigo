package nz.mckenzie.frigo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import nz.mckenzie.frigo.ui.FrigoApp
import nz.mckenzie.frigo.ui.theme.FrigoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as FrigoApplication).container

        // An alert you tapped says which camera it was on, so the app opens that
        // one rather than the grid.
        val openCamera = intent?.getStringExtra(EXTRA_CAMERA)

        setContent {
            FrigoTheme {
                FrigoApp(container, openCamera = openCamera)
            }
        }
    }

    companion object {
        /** The camera an alert was about, carried by the notification's tap. */
        const val EXTRA_CAMERA = "nz.mckenzie.frigo.extra.CAMERA"
    }
}
