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
                // The server you were on, and that server's own switch. Each one
                // keeps its own, so a restart doesn't turn on a server you left off.
                val active = prefs.readServers().active
                if (active?.token != null && prefs.readAlerts(active).on) {
                    AlertService.start(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
