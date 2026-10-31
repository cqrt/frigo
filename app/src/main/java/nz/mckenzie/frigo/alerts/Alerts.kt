package nz.mckenzie.frigo.alerts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nz.mckenzie.frigo.MainActivity
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.ServerPrefs
import nz.mckenzie.frigo.data.prettyCameraName

/**
 * The two notifications this app posts: the quiet one that says it is watching,
 * and the one that buzzes when something new turns up.
 */
object Alerts {

    const val CHANNEL_WATCHING = "watching"
    const val CHANNEL_EVENTS = "events"

    /** The quiet notification that keeps the service alive. Its id never changes. */
    const val WATCHING_ID = 1

    /** Event notifications start here, one id each so they stack. */
    private var nextEventId = 100

    /**
     * The notification that says whether the app is watching, and how the
     * connection is doing. It sits in the shade the whole time the service runs,
     * so it is worth it saying something true about what is going on.
     */
    fun watching(
        context: Context,
        state: WatchState,
        alerts: ServerPrefs.Alerts,
    ): NotificationCompat.Builder {
        val text = state.text(alerts)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Watching for alerts")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    /**
     * One event, with its picture. Tapping it opens the camera it happened on,
     * which is what you want to look at next anyway.
     */
    fun event(context: Context, event: FrigateEvent, picture: ByteArray?) {
        val id = nextEventId++
        val camera = prettyCameraName(event.camera)
        val title = listOfNotNull(event.label.replaceFirstChar { it.uppercase() }, event.subLabel)
            .joinToString(" · ")

        // One request code per alert: Android treats two intents with the same
        // action and component as the same pending intent, extras and all. Sharing
        // one would make every alert in the shade open the newest camera.
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA, event.camera)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(title)
            .setContentText("$camera. Tap to watch it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val bitmap = picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap != null) {
            builder.setLargeIcon(bitmap)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    .setSummaryText(camera)
            )
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}

/** How the connection is doing, in words you can act on. */
sealed interface WatchState {
    data object Connecting : WatchState
    data object Connected : WatchState
    data class NotReaching(val detail: String) : WatchState
    data object SignedOut : WatchState

    /**
     * What the quiet notification says. It counts the cameras you ticked, and it
     * says so when a list is empty, because that is the reason nothing buzzes.
     */
    fun text(alerts: ServerPrefs.Alerts): String {
        if (this is SignedOut) return "Your session has expired. Open Frigo and sign in again."
        if (alerts.cameras.isEmpty()) {
            return "No cameras ticked, so nothing will buzz. Tick some in Settings."
        }
        if (alerts.labels.isEmpty()) {
            return "No objects ticked, so nothing will buzz. Tick some in Settings."
        }
        val count = alerts.cameras.size
        val watching = if (count == 1) "One camera" else "$count cameras"
        return when (this) {
            Connecting -> "$watching. Connecting to your server."
            Connected -> "$watching. New events arrive as they happen."
            is NotReaching -> "$watching. $detail Trying again."
            SignedOut -> "Your session has expired. Open Frigo and sign in again."
        }
    }
}
