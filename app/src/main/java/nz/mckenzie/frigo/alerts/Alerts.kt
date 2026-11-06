package nz.mckenzie.frigo.alerts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import nz.mckenzie.frigo.MainActivity
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.AlertRules
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.QuietTimes
import nz.mckenzie.frigo.data.clockTime
import nz.mckenzie.frigo.data.counted
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.data.words

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
        quiet: QuietTimes,
        hidden: Set<String> = emptySet(),
        only: AlertObjects = AlertObjects(),
    ): NotificationCompat.Builder {
        val text = state.text(context, rules, quiet, System.currentTimeMillis(), hidden, only)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(context.words(R.string.alerts_watching_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }

    /**
     * One review, with the picture of the first thing it saw. Its title is what
     * the review saw, which can be more than one thing, or the sentence Frigate
     * wrote about it when there is one. What comes back is enough to find the
     * notification again when there is more to say about it.
     */
    fun review(context: Context, review: FrigateReview, picture: ByteArray?): Buzz {
        val metadata = review.data.metadata
        val title = metadata?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: review.data.objects
                .map { it.replace('_', ' ').replaceFirstChar { first -> first.uppercase() } }
                .joinToString(" · ")
                .ifBlank { context.words(R.string.alerts_fallback_title) }
        val sentence = metadata?.shortSummary?.trim()?.takeIf { it.isNotEmpty() }
        return buzz(context, review.camera, title, picture, sentence)
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
     *
     * [sentence] is what Frigate wrote about the review, when an alert has waited
     * for it: the notification then opens with that rather than "tap to watch".
     */
    private fun buzz(
        context: Context,
        cameraName: String,
        title: String,
        picture: ByteArray?,
        sentence: String? = null,
    ): Buzz {
        val id = nextEventId++
        val camera = prettyCameraName(cameraName)
        val text = sentence ?: context.words(R.string.alerts_tap_to_watch, camera)

        NotificationManagerCompat.from(context)
            .notify(
                id,
                build(context, id, cameraName, title, text, picture, full = sentence != null)
                    .setSubText(camera)
                    .build(),
            )
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

        if (bitmap != null && full) {
            // The system's big picture gives the text two lines, which cuts a
            // description off. This lays the two out itself so the sentence arrives
            // whole. The big text style stays behind it, so if a launcher ever
            // ignores the custom view the sentence is still readable.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            builder.setCustomBigContentView(alertView(context, title, text, bitmap))
        } else if (bitmap != null) {
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

    /**
     * The opened layout: the title, the sentence to its end, and the picture under
     * them. Everything here is a view the notification shade can draw, which is a
     * short list: no buttons, no scrolling, and no corners on a picture, so they
     * are drawn onto the bitmap first.
     */
    private fun alertView(
        context: Context,
        title: String,
        text: String,
        picture: Bitmap,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_alert).apply {
        setTextViewText(R.id.alert_title, title)
        setTextViewText(R.id.alert_text, text)
        setImageViewBitmap(R.id.alert_picture, softCorners(context, picture))
    }

    /** The picture with its corners taken off, the way the shade's own pictures are. */
    private fun softCorners(context: Context, picture: Bitmap): Bitmap {
        val rounded = RoundedBitmapDrawableFactory.create(context.resources, picture)
        rounded.cornerRadius = 6 * context.resources.displayMetrics.density
        val cornered = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
        rounded.setBounds(0, 0, cornered.width, cornered.height)
        rounded.draw(Canvas(cornered))
        return cornered
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
     *
     * Cameras turned off on this phone are counted out, since they are what this
     * line is about. With every one of them off, the line says so and names where
     * to turn one back on.
     *
     * [only] is this phone's own list of objects, camera by camera. A camera with
     * nothing left on its list is counted out too, and with every camera left with
     * nothing the line says which list is to blame.
     *
     * With [quiet] set and every camera that would buzz inside it, the sentence
     * starts with the time it comes back. Half a quiet window still buzzes, so
     * the line stays as it was rather than claiming more silence than there is.
     */
    fun text(
        context: Context,
        rules: AlertRules,
        quiet: QuietTimes? = null,
        now: Long = System.currentTimeMillis(),
        hidden: Set<String> = emptySet(),
        only: AlertObjects = AlertObjects(),
    ): String {
        if (this is SignedOut) return context.words(R.string.alerts_session_expired)

        // The first of these that is true is the one worth saying: turning a
        // camera on further down would change nothing until it is dealt with.
        val telling = rules.cameras.filterValues { it.notify }
        if (telling.isEmpty()) {
            return context.words(R.string.alerts_all_cameras_off)
        }
        val alerting = telling.filterValues { it.alertsOn }
        if (alerting.isEmpty()) return context.words(R.string.alerts_no_alerts_on)

        val buzzing = alerting.filterValues { it.labels.isNotEmpty() }
        if (buzzing.isEmpty()) return context.words(R.string.alerts_no_alert_objects)

        // This phone's list, applied camera by camera: what is left of the objects
        // the server would tell you about.
        val allowed = if (only.everything) {
            buzzing
        } else {
            buzzing
                .mapValues { (name, camera) ->
                    camera.copy(labels = camera.labels.filter { only.lets(name, it) })
                }
                .filterValues { it.labels.isNotEmpty() }
        }
        if (allowed.isEmpty()) return context.words(R.string.alerts_only_objects)

        // The ones this phone still tells you about. Turning every one of them off
        // here is the phone's own doing, so the line says so rather than counting
        // cameras that cannot reach it.
        val heard = allowed.filterKeys { it !in hidden }
        if (heard.isEmpty()) return context.words(R.string.alerts_all_cameras_hidden)

        val count = heard.size
        val watching = context.counted(R.plurals.alerts_cameras, count, count)
        val labels = said(context, heard.values.flatMap { it.labels }.distinct())

        val silence = if (quiet != null && heard.keys.all { quiet.quiets(it, now) }) {
            quiet.quietUntil(now)
                ?.let { context.words(R.string.alerts_quiet_until, clockTime(it)) }
                .orEmpty()
        } else {
            ""
        }

        val state = when (this) {
            Connecting -> context.words(R.string.alerts_watching_connecting, watching, labels)
            Connected -> context.words(R.string.alerts_watching_connected, watching, labels)
            is NotReaching ->
                context.words(R.string.alerts_watching_retrying, watching, labels, detail)
            SignedOut -> context.words(R.string.alerts_session_expired)
        }
        return silence + state
    }

    private companion object {
        /** Two at most, then a count, so the line stays a line. */
        fun said(context: Context, labels: List<String>): String = when {
            labels.isEmpty() -> context.words(R.string.alerts_nothing)
            labels.size == 1 -> labels[0]
            labels.size == 2 ->
                context.words(R.string.alerts_two_objects, labels[0], labels[1])
            else -> context.words(
                R.string.alerts_more_objects,
                labels[0],
                labels[1],
                labels.size - 2,
            )
        }
    }
}
