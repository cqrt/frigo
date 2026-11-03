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
import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigateReview
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
        rules: AlertRules,
    ): NotificationCompat.Builder {
        val text = state.text(rules)
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
        val title = listOfNotNull(event.label.replaceFirstChar { it.uppercase() }, event.subLabel)
            .joinToString(" · ")
        buzz(context, event.camera, title, picture)
    }

    /**
     * One review, with the picture of the first thing it saw. Its title is what
     * the review saw, which can be more than one thing.
     */
    fun review(context: Context, review: FrigateReview, picture: ByteArray?) {
        val title = review.data.objects
            .map { it.replace('_', ' ').replaceFirstChar { first -> first.uppercase() } }
            .joinToString(" · ")
            .ifBlank { "Alert" }
        buzz(context, review.camera, title, picture)
    }

    /**
     * Posts one buzz, and every buzz takes the next id, so two in the shade do
     * not become one.
     */
    private fun buzz(context: Context, cameraName: String, title: String, picture: ByteArray?) {
        val id = nextEventId++
        val camera = prettyCameraName(cameraName)

        // One request code per alert: Android treats two intents with the same
        // action and component as the same pending intent, extras and all. Sharing
        // one would make every alert in the shade open the newest camera.
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA, cameraName)
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
     * What the quiet notification says. When nothing will buzz it names the one
     * thing to change, because going quiet without saying why looks broken. When
     * something will, it counts the cameras the server has turned on and names
     * what they buzz for.
     */
    fun text(rules: AlertRules): String {
        if (this is SignedOut) return Expired

        // The first of these that is true is the one worth saying: turning a
        // camera on below it would change nothing until this one is dealt with.
        if (!rules.notificationsOn) {
            return "Notifications are off on your server, so nothing will buzz."
        }
        val telling = rules.cameras.values.filter { it.notify }
        if (telling.isEmpty()) {
            return "Every camera is off on your server's notifications page, so nothing will buzz."
        }
        val alerting = telling.filter { it.alertsOn }
        if (alerting.isEmpty()) return "No camera has alerts on, so nothing will buzz."

        val buzzing = alerting.filter { it.labels.isNotEmpty() }
        if (buzzing.isEmpty()) return "No camera has any alert objects, so nothing will buzz."

        val count = buzzing.size
        val watching = if (count == 1) "One camera" else "$count cameras"
        val labels = said(buzzing.flatMap { it.labels }.distinct())
        return when (this) {
            Connecting -> "$watching for $labels. Connecting to your server."
            Connected -> "$watching for $labels. New events arrive as they happen."
            is NotReaching -> "$watching for $labels. $detail Trying again."
            SignedOut -> Expired
        }
    }

    private companion object {
        const val Expired = "Your session has expired. Open Frigo and sign in again."

        /** Two at most, then a count, so the line stays a line. */
        fun said(labels: List<String>): String = when {
            labels.isEmpty() -> "nothing"
            labels.size <= 2 -> labels.joinToString(" and ")
            else -> "${labels[0]}, ${labels[1]} and ${labels.size - 2} more"
        }
    }
}
