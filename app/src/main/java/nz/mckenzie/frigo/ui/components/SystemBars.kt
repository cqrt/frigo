package nz.mckenzie.frigo.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Flips the status bar icons light or dark for as long as the screen calling
 * this is on display, then puts them back. The player needs light icons because
 * it sits on black.
 */
@Composable
fun StatusBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return

    DisposableEffect(darkIcons) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = darkIcons
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Takes the status and navigation bars away while [hidden] is true, so a picture
 * can have the whole screen. Swiping in from an edge brings them back for a
 * moment without leaving fullscreen, and they come back properly on the way out.
 *
 * The controller comes from the view rather than the activity, because a clip
 * plays in a dialog with a window of its own.
 */
// Core 1.17 deprecates `ViewCompat.getWindowInsetsController` in favour of a call
// that wants the window, and a clip plays in a dialog whose window belongs to the
// dialog rather than the activity. This is the one call that finds the right
// window from the view alone. Below API 30 it falls back to the activity's bars,
// which leaves a dialog's bars up rather than breaking anything, and no phone
// running this app is that old.
@Suppress("DEPRECATION")
@Composable
fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current

    DisposableEffect(hidden, view) {
        val controller = ViewCompat.getWindowInsetsController(view)
        if (hidden) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
