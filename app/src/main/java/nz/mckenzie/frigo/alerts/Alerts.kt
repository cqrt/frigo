package nz.mckenzie.frigo.alerts

import android.app.NotificationManager
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
     * which is what you want to look at next anyway. What comes back is enough to
     * find the notification again when there is more to say about it.
     */
    fun event(context: Context, event: FrigateEvent, picture: ByteArray?): Buzz {
        val title = listOfNotNull(event.label.replaceFirstChar { it.uppercase() }, event.subLabel)
            .joinToString(" · ")
        return buzz(context, event.camera, title, picture)
    }

    /**
     * One review, with the picture of the first thing it saw. Its title is what
     * the review saw, which can be more than one thing.
     */
    fun review(context: Context, review: FrigateReview, picture: ByteArray?): Buzz {
        val title = review.data.objects
            .map { it.replace('_', ' ').replaceFirstChar { first -> first.uppercase() } }
            .joinToString(" · ")
            .ifBlank { "Alert" }
        return buzz(context, review.camera, title, picture)
    }

    /**
     * Fills a buzz in with a sentence about what is happening, while it is still
     * fresh. It is the same notification, so it takes the first one's place rather
     * than stacking a second, and it stays quiet: the buzz has been and gone.
     *
     * The title stays as it was, because that names what the server saw. The
     * sentence goes into the line under it, so the same words are there whether the
     * notification is open or closed.
     */
    fun say(context: Context, buzz: Buzz, sentence: String, picture: ByteArray?) {
        val said = sentence.trim()
        if (said.isEmpty()) return
        if (!showing(context, buzz.id)) return

        NotificationManagerCompat.from(context)
            .notify(
                buzz.id,
                build(context, buzz.id, buzz.camera, buzz.title, said, picture, full = true)
                    .setSubText(buzz.name)
                    .build(),
            )
    }

    /**
     * Rewrites a notification with what the server made of the clip once it had
     * looked at it, in place of the bare "something was seen".
     *
     * It is the same notification, so it takes the first one's place rather than
     * stacking a second, and it stays quiet: the buzz has been and gone by the
     * time the description is written.
     */
    fun describe(context: Context, id: Int, review: FrigateReview, picture: ByteArray?) {
        val metadata = review.data.metadata ?: return
        val title = metadata.title.trim()
        val said = metadata.shortSummary.trim()
        if (title.isEmpty() || said.isEmpty()) return
        if (!showing(context, id)) return

        NotificationManagerCompat.from(context)
            .notify(
                id,
                build(context, id, review.camera, title, said, picture, full = true)
                    // The sentence takes the line the camera name had, so the camera
                    // moves up beside the time rather than going missing.
                    .setSubText(prettyCameraName(review.camera))
                    .build(),
            )
    }

    /**
     * Whether that notification is still in the shade. If it has been swiped
     * away, putting it back would buzz a second time over something you have
     * already dealt with.
     */
    private fun showing(context: Context, id: Int): Boolean =
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications
                ?.any { it.id == id }
        }.getOrNull() ?: true

    /**
     * Posts one buzz, and every buzz takes the next id, so two in the shade do
     * not become one.
     */
    private fun buzz(context: Context, cameraName: String, title: String, picture: ByteArray?): Buzz {
        val id = nextEventId++
        val camera = prettyCameraName(cameraName)

        NotificationManagerCompat.from(context)
            .notify(id, build(context, id, cameraName, title, "$camera. Tap to watch it.", picture).build())
        return Buzz(id, cameraName, title, camera)
    }

    /**
     * A buzz that went out, kept so the notification can be found and filled in.
     *
     * [camera] is the server's name for the camera, for asking it questions, and
     * [name] is how that camera is said out loud.
     */
    data class Buzz(
        val id: Int,
        val camera: String,
        val title: String,
        val name: String,
    )

    /** The one shape a buzz takes, whether it is new or being filled in. */
    private fun build(
        context: Context,
        id: Int,
        cameraName: String,
        title: String,
        text: String,
        picture: ByteArray?,
        /** True once there is a sentence to read: it then gets the room, not the picture. */
        full: Boolean = false,
    ): NotificationCompat.Builder {
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
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            // The first one buzzes. Anything after it, such as the description
            // filling in the same notification, only changes what it says.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val bitmap = picture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap != null) builder.setLargeIcon(bitmap)

        if (bitmap != null && !full) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    // The picture takes the room here, so the line under the title is
                    // all the text there is. It is short on a buzz: no sentence yet.
                    .setSummaryText(text)
            )
        } else {
            // A big picture cuts the text to one line, and a sentence about what
            // happened is worth more than the picture at that size. The picture
            // stays as the icon beside it, and the sentence is shown to the end.
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text)
                    .setBigContentTitle(title)
            )
        }
        return builder
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
        // camera on further down would change nothing until it is dealt with.
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
