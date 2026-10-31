package nz.mckenzie.frigo.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.ServerPrefs

/**
 * Starts watching again after the phone restarts, if you had it switched on.
 * Without this you would have to open the app once after every reboot, and
 * nothing would buzz until you did.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // The receiver has to stay alive while the saved settings are read.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = ServerPrefs(appContext)
                val signedIn = prefs.read()?.token != null
                if (prefs.readAlerts().on && signedIn) AlertService.start(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
