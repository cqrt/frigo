package nz.mckenzie.frigo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.ui.FrigoApp
import nz.mckenzie.frigo.ui.theme.FrigoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Rotation and theme changes are handled in the manifest, so this
        // container is built once and lives as long as the activity.
        val container = AppContainer(applicationContext)

        setContent {
            FrigoTheme {
                FrigoApp(container)
            }
        }
    }
}
