package nz.mckenzie.frigo

import android.app.Application
import nz.mckenzie.frigo.data.AppContainer

/**
 * Holds the one copy of everything that talks to Frigate. The activity and the
 * alert service both use it, so they share the session token and sign in and out
 * together.
 */
class FrigoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
