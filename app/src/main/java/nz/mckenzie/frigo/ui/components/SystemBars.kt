package nz.mckenzie.frigo.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
