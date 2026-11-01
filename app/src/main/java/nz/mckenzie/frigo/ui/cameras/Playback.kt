package nz.mckenzie.frigo.ui.cameras

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.RecordingDay
import nz.mckenzie.frigo.data.RecordingSpan
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.megabytes
import nz.mckenzie.frigo.ui.components.toImageBitmapOrNull
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** How much of the recording one press of Play fetches. */
internal const val ClipSeconds = 120.0

/** How far before the moment you picked the clip starts, so you see what led up to it. */
internal const val LeadInSeconds = 15.0

internal const val SecondsInADay = 86_400.0

/**
 * How much of the day the scrolling bar shows at once: an hour. A day is
 * twenty-four screens wide, which is what makes a half minute of recording a
 * spike you can see rather than a hairline.
 */
private const val SecondsAcross = 3600.0

/**
 * How far one step of the bar moves the moment, in seconds. Frigate's own
 * timeline steps in thirty and so does this: a step is three points of bar, and
 * asking for a new picture any more often than that asks the server for something
 * nobody could tell apart from the last one.
 */
internal const val ScrubStep = 30.0

/**
 * The lines the picture is cut at. Frigate runs ffmpeg over the recording for
 * every still, so a shorter picture comes back sooner and costs the server less.
 * 720 is sharp on a phone; 480 is soft but quicker, and it is the knob to turn if
 * a slow server leaves the picture lagging behind your finger.
 */
internal const val ScrubLines = 720

/**
 * How often the big picture may change, at most. Eight a second reads as video;
 * more than that is a server cutting pictures nobody sees.
 */
private const val MainStillGap = 120L

/** The three small ones, at two and a half a second. They are there to be noticed, not to be watched. */
private const val OtherStillGap = 400L

/**
 * How often a small tile of a camera you are not watching is redrawn while you
 * watch the stream. Once every couple of seconds is enough to see someone walk in,
 * and cheap enough that three of them are nothing beside the stream itself.
 */
private const val LiveStillMillis = 2_000L

/**
 * How long the mode pill's dot takes to fade from bright to dim. Paired with its
 * reverse that is a shade under a second and a half for the round trip, which is
 * slow enough to read as a pulse and not as a warning light.
 */
private const val ModePulseMillis = 700

/**
 * Red for an alert, amber for a detection. The camera screen is black whatever
 * the theme is set to, so these are picked to sit on black rather than taken
 * from the palette.
 */
private val AlertSpike = Color(0xFFF05252)
private val DetectionSpike = Color(0xFFE9A23B)

/**
 * Which the picture is: red for the stream, blue for a recording. Red is the one
 * that says something is happening now, and blue is the colour of everything you
 * can go back over, so the bar's own blue is the blue here.
 */
private val LiveRed = Color(0xFFF05252)
private val PlaybackBlue = Color(0xFF3B82F6)

/** The heights inside the scrolling bar, top to bottom. */
private val BarHeight = 58.dp
private val StripHeight = 16.dp
private val LabelTop = 2.dp
private val BandTop = 20.dp
private val BandBottom = 36.dp
private val AlertTop = 36.dp
private val DetectionTop = 44.dp
private val SpikeBottom = 52.dp

/**
 * A clip as a file on the phone, with a running count of the bytes that have
 * arrived as it comes.
 */
internal typealias ClipFetch = suspend (onProgress: (Long) -> Unit) -> File

/**
 * The day's bar under the picture, and what sits around it: the days to choose
 * from, a strip of the whole day to jump with, the hour you are looking at, and
 * whatever the corner holds: nothing on the stream, Save on a recording.
 *
 * The big readout carries the day as well as the time, so dragging the bar tells
 * you when you are without reading the timestamp burned into the picture.
 *
 * Frigate cuts a clip out of the recordings when it is asked for one. [caption] is
 * what the recording of that stretch amounts to, and with it Save; there is none on
 * the stream, where the two minutes up to now lead nowhere. The playhead can sit
 * where nothing was recorded, and [canPlay] is false there rather than saving two
 * minutes of black.
 */
@Composable
internal fun TimelinePanel(
    days: List<RecordingDay>,
    day: String?,
    zone: ZoneId,
    spans: List<RecordingSpan>,
    spikes: List<FrigateReview>,
    chosen: Double,
    moment: String,
    caption: String? = null,
    captionIsWarning: Boolean = false,
    onDay: (String) -> Unit,
    onPick: (Double) -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    val dayStart = day?.let { startOfDay(it, zone) } ?: 0.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            days.forEach { entry ->
                DayPill(
                    label = dayLabel(entry.day, zone),
                    selected = entry.day == day,
                    onClick = { onDay(entry.day) },
                )
            }
        }

        // The days are one thing to choose and the day's bar is another, so the gap
        // between them is wider than the gaps inside the bar.
        Spacer(Modifier.height(14.dp))

        DayStrip(
            dayStart = dayStart,
            spans = spans,
            spikes = spikes,
            chosen = chosen,
            onPick = onPick,
        )

        Spacer(Modifier.height(7.dp))

        ScrubBar(
            dayStart = dayStart,
            zone = zone,
            spans = spans,
            spikes = spikes,
            chosen = chosen,
            onPick = onPick,
        )

        Spacer(Modifier.height(7.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = moment,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (captionIsWarning) {
                            AlertSpike
                        } else {
                            MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            action()
        }
    }
}

/** One day to choose, as a pill that lights up when it is the day on the bar. */
@Composable
private fun DayPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val ink = MaterialTheme.colorScheme.onBackground
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) ink else ink.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) ink.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, ink.copy(alpha = if (selected) 0f else 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * What the picture is: the stream, or a recording. In the bottom corner of the
 * picture, red or blue, with a dot that brightens and dims rather than switching
 * hard on and off. A hard blink in a corner wears thin after a few minutes of
 * watching; a slow one still reads as alive.
 *
 * On the picture rather than under it, because a recording on its side fills the
 * screen and there is no bar there to say which you are looking at.
 */
@Composable
internal fun ModePill(live: Boolean, modifier: Modifier = Modifier) {
    val colour = if (live) LiveRed else PlaybackBlue
    val pulse = rememberInfiniteTransition(label = "mode")
    val glow by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ModePulseMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            // Thin enough that the picture shows through: it sits on the video, and
            // a solid pill there hides the corner the camera cares about.
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, colour.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(start = 9.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .alpha(glow)
                .background(colour, CircleShape),
        )
        Text(
            text = if (live) "Live" else "Playback",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Keeps the stretch the caption beside it names as a file on the phone.
 *
 * The phone's own save screen opens first, so where the file lands is its decision
 * and the app asks for no permission and puts nothing in the gallery. Nothing is
 * fetched until a place is chosen, so a tap thought better of costs nothing.
 *
 * [fetch] is what the viewer fetches, under the same name, so a stretch already
 * watched in it is copied off the phone rather than asked for again.
 */
@Composable
internal fun SaveClipButton(
    name: String,
    enabled: Boolean,
    fetch: ClipFetch,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held from the tap until the save screen answers, so the file is the stretch
    // the caption named then, however far the picture has moved on by the time a
    // place is chosen.
    var pending by remember { mutableStateOf<ClipFetch?>(null) }
    var saving by remember { mutableStateOf(false) }
    var received by remember { mutableLongStateOf(0L) }
    var saved by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // The save screen is remembered rather than made afresh each time: a new one
    // every recomposition would register and unregister the launcher while a save
    // is in the air.
    val saveScreen = remember { ActivityResultContracts.CreateDocument("video/mp4") }
    val ask = rememberLauncherForActivityResult(saveScreen) { place ->
        val clip = pending
        pending = null
        if (place == null || clip == null) return@rememberLauncherForActivityResult
        saving = true
        received = 0L
        scope.launch {
            val outcome = runCatching {
                val file = clip { received = it }
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(place)
                        ?: throw IOException("nowhere to write it")
                    out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
                }
            }
            saving = false
            saved = outcome.isSuccess
            failed = outcome.isFailure
            if (outcome.isFailure) received = 0L
        }
    }

    // Saving failure is said in the button rather than in the caption, which is busy
    // naming the stretch, and both go back to Save once they have been read.
    LaunchedEffect(saved, failed) {
        if (!saved && !failed) return@LaunchedEffect
        delay(2_500)
        saved = false
        failed = false
    }

    val ready = enabled && !saving
    val ink = MaterialTheme.colorScheme.onBackground
    val tone = when {
        failed -> LiveRed
        ready -> ink.copy(alpha = 0.9f)
        else -> ink.copy(alpha = 0.35f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(ink.copy(alpha = 0.06f))
            .border(1.dp, tone.copy(alpha = if (ready) 0.45f else 0.15f), RoundedCornerShape(50))
            .clickable(enabled = ready, onClickLabel = "Save this clip") {
                saved = false
                failed = false
                pending = fetch
                ask.launch(name)
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saving) {
            CircularProgressIndicator(
                color = tone,
                strokeWidth = 2.dp,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Icon(
                imageVector = FrigoIcons.Save,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = when {
                saving && received > 0 -> "Saving ${megabytes(received)}"
                saving -> "Saving"
                saved -> "Saved"
                failed -> "Couldn't save"
                else -> "Save"
            },
            color = tone,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * A flick carries the day on after the finger leaves, the way throwing a strip of
 * film would. Below [MinFling] a release was a hand coming to rest, not a throw, so
 * the bar stops where it was let go; above [MaxFling] the speed is capped, so a hard
 * throw cannot cross the day in one go and lose you. [FlingFriction] brings it to a
 * stop inside a second: a hard throw carries about twenty minutes.
 */
private const val MinFling = 260f
private const val MaxFling = 3_600f
private const val FlingFriction = 2.6f

/** How long a hand can be still before letting go and still count as a flick. */
private const val HoldMillis = 140L

/**
 * The whole day in one thin strip: the stretches Frigate kept, a sliver for
 * everything it flagged, and a box around the hour the bar below is showing.
 *
 * Tapping or dragging along it goes anywhere in the day at once, which is how you
 * cross twenty-four hours without twenty-four screens of scrolling.
 */
@Composable
private fun DayStrip(
    dayStart: Double,
    spans: List<RecordingSpan>,
    spikes: List<FrigateReview>,
    chosen: Double,
    onPick: (Double) -> Unit,
) {
    var width by remember { mutableStateOf(0f) }
    val pick by rememberUpdatedState(onPick)
    val ink = MaterialTheme.colorScheme.onBackground

    fun at(x: Float) {
        if (width <= 0f) return
        val fraction = (x / width).coerceIn(0f, 1f)
        pick(quantise(dayStart + fraction * SecondsInADay))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(StripHeight)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(dayStart) { detectTapGestures { at(it.x) } }
            .pointerInput(dayStart) {
                detectDragGestures(onDragStart = { at(it.x) }) { change, _ ->
                    at(change.position.x)
                }
            },
    ) {
        fun x(moment: Double): Float =
            (((moment - dayStart) / SecondsInADay).toFloat() * size.width)
                .coerceIn(0f, size.width)

        drawRoundRect(
            color = ink.copy(alpha = 0.1f),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(4f, 4f),
        )

        val bandTop = size.height * 0.32f
        val bandHeight = size.height * 0.36f

        spans.forEach { span ->
            val left = x(span.startTime)
            val right = x(span.endTime)
            if (right > left) {
                drawRect(
                    color = ink.copy(alpha = 0.45f),
                    topLeft = Offset(left, bandTop),
                    size = Size(right - left, bandHeight),
                )
            }
        }

        spikes.forEach { spike ->
            val left = x(spike.startTime)
            val right = x(spike.endTime ?: spike.startTime).coerceAtLeast(left + 1f)
            drawRect(
                color = if (spike.severity == "alert") AlertSpike else DetectionSpike,
                topLeft = Offset(left, bandTop),
                size = Size(right - left, bandHeight),
            )
        }

        // The hour the bar below has, so the two bars agree about where you are.
        val from = x(chosen - SecondsAcross / 2)
        val to = x(chosen + SecondsAcross / 2)
        val window = (to - from).coerceAtLeast(3f)
        drawRoundRect(
            color = ink.copy(alpha = 0.16f),
            topLeft = Offset(from, 0f),
            size = Size(window, size.height),
            cornerRadius = CornerRadius(3f, 3f),
        )
        drawRoundRect(
            color = ink.copy(alpha = 0.8f),
            topLeft = Offset(from, 0f),
            size = Size(window, size.height),
            cornerRadius = CornerRadius(3f, 3f),
            style = Stroke(width = 1.5f),
        )
        val head = x(chosen)
        drawLine(
            color = ink,
            start = Offset(head, 0f),
            end = Offset(head, size.height),
            strokeWidth = 2f,
        )
    }
}

/**
 * An hour of the day, scrolling under a playhead that stays in the middle.
 *
 * A day will not fit on a phone at a size where anything is legible, so it does
 * not have to: drag the bar and the hour slides past, with half an hour either
 * side of the moment in the picture. The picture follows the drag, in whole
 * thirty second steps.
 *
 * What is drawn is worked out from the scroll position inside the drawing itself,
 * so a drag redraws this one bar and nothing else on the screen.
 */
@Composable
private fun ScrubBar(
    dayStart: Double,
    zone: ZoneId,
    spans: List<RecordingSpan>,
    spikes: List<FrigateReview>,
    chosen: Double,
    onPick: (Double) -> Unit,
) {
    var width by remember { mutableStateOf(0f) }
    // How far the day has moved past the left edge of the bar, in pixels. The
    // playhead is at the middle, so the moment is a screen's half further on.
    var moved by remember { mutableFloatStateOf(0f) }
    // The last moment the bar said it had moved to. Coming back in as chosen is
    // the bar's own voice, and must not be mistaken for somebody moving the bar:
    // the value is rounded to a step, so the round trip lands behind the playhead
    // and a correction would drag it back, over and over, once for every pixel.
    var said by remember { mutableDoubleStateOf(Double.NaN) }
    // When the finger last moved. A hand that has stopped before letting go is not
    // a flick, whatever the last few pixels of it looked like.
    var lastMoved by remember { mutableLongStateOf(0L) }
    var coasting by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val pick by rememberUpdatedState(onPick)
    val measurer = rememberTextMeasurer()
    // The bar is drawn in the page's own ink, so the hours, the ticks and the
    // stretches kept read the same in the night colours and in the day.
    val ink = MaterialTheme.colorScheme.onBackground
    val labelStyle = TextStyle(fontSize = 10.sp, color = ink.copy(alpha = 0.55f))
    // The kept hours are a wash of the page's ink. On the light page the same wash
    // as the night one reads as a solid block, so it goes lighter there.
    val keptWash = if (ink.luminance() > 0.5f) 0.7f else 0.45f

    // Pixels a second, and how wide the day is at that scale.
    val scale = if (width > 0f) (width / SecondsAcross).toFloat() else 0f
    val dayWidth = SecondsInADay.toFloat() * scale
    val least = -width / 2f
    val most = dayWidth - width / 2f

    fun momentAt(px: Float): Double = dayStart + px / scale

    // Move the day to a place on the bar, and say where that landed. The say is
    // what stops the round trip above from being read as somebody else's move.
    fun slideTo(px: Float) {
        if (scale <= 0f) return
        val next = px.coerceIn(least, most)
        moved = next
        val moment = quantise(momentAt(next + width / 2f))
        if (moment != said) {
            said = moment
            pick(moment)
        }
    }

    val drag = rememberDraggableState { delta ->
        coasting?.cancel()
        // The day moves with your finger: drag left and later comes under the
        // playhead, the way a strip of film would.
        lastMoved = System.nanoTime()
        slideTo(moved - delta)
    }

    // The strip, a day chip, another camera, or the clock while live: the bar
    // moves to wherever that moment is. Anything the bar said itself is left
    // alone, and so is anything under half a step away.
    LaunchedEffect(chosen, dayStart, width) {
        if (scale <= 0f || chosen == said) return@LaunchedEffect
        val wanted = ((chosen - dayStart).toFloat() * scale - width / 2f).coerceIn(least, most)
        if (abs(wanted - moved) > scale * ScrubStep / 2f) {
            coasting?.cancel()
            moved = wanted
        }
    }

    // Let go in the middle of a flick and the day carries on a little, the way a
    // strip of film would if you threw it. It stops on its own in well under a
    // second, and the moment a finger comes back down it stops at once.
    fun letGo(velocity: Float) {
        coasting?.cancel()
        val still = (System.nanoTime() - lastMoved) / 1_000_000
        val start = (-velocity).coerceIn(-MaxFling, MaxFling)
        if (still > HoldMillis || abs(start) < MinFling) return
        coasting = scope.launch {
            var previous = 0f
            AnimationState(0f, start).animateDecay(exponentialDecay(FlingFriction)) {
                slideTo(moved + (value - previous))
                previous = value
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(ink.copy(alpha = 0.08f))
            .onSizeChanged { width = it.width.toFloat() }
            // A finger on the bar stops it, before it has moved far enough to
            // count as a drag. Resting a hand on a bar that is still gliding is the
            // one thing that must stop it dead.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    coasting?.cancel()
                }
            }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                onDragStarted = { coasting?.cancel() },
                onDragStopped = { velocity -> letGo(velocity) },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (scale <= 0f) return@Canvas
            val left = moved
            // The seconds of the day on show, which is all that needs drawing.
            val from = (left / scale).coerceAtLeast(0f)
            val to = ((left + size.width) / scale).coerceAtMost(SecondsInADay.toFloat())

            fun at(seconds: Double): Float = (seconds * scale - left).toFloat()

            // A line a minute, so the bar reads as sixty ticks an hour, and a
            // longer one every ten minutes with the time above it.
            var tick = (from / 60).toLong() * 60
            while (tick <= to) {
                val x = at(tick.toDouble())
                val major = tick % 600L == 0L
                drawLine(
                    color = ink.copy(alpha = if (major) 0.2f else 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (major) 1.5f else 1f,
                )
                tick += 60
            }

            var mark = (from / 600).toLong() * 600
            while (mark <= to) {
                val laid = measurer.measure(clock(dayStart + mark, zone), labelStyle)
                drawText(
                    textLayoutResult = laid,
                    topLeft = Offset(at(mark.toDouble()) - laid.size.width / 2f, LabelTop.toPx()),
                )
                mark += 600
            }

            val bandTop = BandTop.toPx()
            val bandBottom = BandBottom.toPx()
            val spikeBottom = SpikeBottom.toPx()

            spans.forEach { span ->
                val start = span.startTime - dayStart
                val end = span.endTime - dayStart
                if (end < from || start > to) return@forEach
                val left = at(start)
                val right = at(end)
                if (right > left) {
                    drawRect(
                        color = ink.copy(alpha = keptWash),
                        topLeft = Offset(left, bandTop),
                        size = Size(right - left, bandBottom - bandTop),
                    )
                }
            }

            // Detections sit low and alerts stand tall over them, drawn after so an
            // alert is not lost in a busy stretch of detections.
            fun drawSpikes(of: List<FrigateReview>, top: Float, colour: Color) {
                of.forEach { spike ->
                    val start = spike.startTime - dayStart
                    val end = (spike.endTime ?: spike.startTime) - dayStart
                    if (end < from || start > to) return@forEach
                    val left = at(start)
                    val right = at(end).coerceAtLeast(left + 2f)
                    drawRect(
                        color = colour,
                        topLeft = Offset(left, top),
                        size = Size(right - left, spikeBottom - top),
                    )
                }
            }

            drawSpikes(spikes.filter { it.severity != "alert" }, DetectionTop.toPx(), DetectionSpike)
            drawSpikes(spikes.filter { it.severity == "alert" }, AlertTop.toPx(), AlertSpike)
        }

        // The playhead stays in the middle: what the picture is showing.
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            drawLine(
                color = ink,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = ink,
                radius = 3.dp.toPx(),
                center = Offset(x, 3.dp.toPx()),
            )
        }
    }
}

/**
 * The other cameras at the moment on the bar, side by side under the picture, so
 * you can see which one caught what you are looking for. Tapping one brings it up
 * in the picture at the same moment, which is quicker than going back to the grid.
 * While a recording plays the moment follows the picture, and so does the run when
 * you tap. Each picture is still of that camera's own recording, so a camera with
 * nothing recorded then comes back empty and shows as a blank tile, which is worth
 * knowing in itself.
 */
@Composable
internal fun OtherCameras(
    repository: FrigoRepository,
    cameras: List<Camera>,
    moment: Double,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraStrip(cameras = cameras, onPick = onPick, modifier = modifier) { other ->
        MomentStill(
            repository = repository,
            camera = other.name,
            moment = moment,
            height = 360,
            minGapMillis = OtherStillGap,
            contentDescription = other.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}

/**
 * The other cameras under the picture while you are watching the stream, as they
 * look now. Tapping one brings it up in the picture, live still.
 *
 * A still redrawn every couple of seconds rather than another stream running beside
 * this one: the stream you are watching is most of what the connection carries
 * already, and a picture that changes that often is enough to see someone walk in.
 */
@Composable
internal fun LiveCameras(
    repository: FrigoRepository,
    cameras: List<Camera>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraStrip(cameras = cameras, onPick = onPick, modifier = modifier) { other ->
        LiveStill(
            repository = repository,
            camera = other.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
    }
}

/** A tile per camera, the picture asking for its own, with the camera's name under it. */
@Composable
private fun CameraStrip(
    cameras: List<Camera>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    picture: @Composable (Camera) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cameras.forEach { other ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Show ${other.title}") { onPick(other.name) },
            ) {
                picture(other)
                Text(
                    text = other.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * A camera as it looks now, redrawn every couple of seconds off the still the
 * server keeps of it. A picture that does not arrive leaves the last one up, so a
 * slow answer does not blank the tile and fill it again.
 */
@Composable
private fun LiveStill(
    repository: FrigoRepository,
    camera: String,
    modifier: Modifier = Modifier,
) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(camera) {
        while (true) {
            val started = System.nanoTime()
            val bytes = runCatching { repository.latestFrame(camera, 360) }.getOrNull()
            if (bytes != null) {
                val picture = withContext(Dispatchers.Default) { bytes.toImageBitmapOrNull() }
                if (picture != null) frame = picture
            }
            val spent = (System.nanoTime() - started) / 1_000_000
            if (spent < LiveStillMillis) delay(LiveStillMillis - spent)
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)),
    ) {
        frame?.let { picture ->
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The last few pictures cut out of a recording, so going back over ground you have
 * just covered is instant instead of another run of ffmpeg on the server. Held as
 * the bytes Frigate sent: a dozen of them is a couple of megabytes, where the same
 * dozen decoded would be tens.
 *
 * Only this file touches it, and only from the main thread: the coroutine that
 * reads and writes it is the one the picture is drawn from.
 */
private object StillCache {
    private const val Size = 12
    private val entries = object : LinkedHashMap<String, ByteArray>(Size, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) =
            size > Size
    }

    fun get(key: String): ByteArray? = entries[key]
    fun put(key: String, bytes: ByteArray) {
        entries[key] = bytes
    }
}

/**
 * The recording at that moment, redrawn as the marker moves.
 *
 * Dragging changes [moment] many times a second, far more often than a picture of
 * it can be cut and sent. So the requests are conflated: one is in flight at a
 * time, and when it lands the next one asks for wherever the finger is by then. A
 * fast drag through a quiet hour plays as a run of pictures rather than a stall,
 * and the last one you land on is the one that stays.
 *
 * A picture that does not arrive leaves the last one up. Frigate cuts each one out
 * of the recording as it is asked, and a server busy enough to miss this instant
 * has not unsaid the last one: blanking the picture and filling it again a moment
 * later is the flash you see, where holding it is not.
 *
 * [minGapMillis] keeps one picture from following another too closely, so a server
 * that answers quickly is not asked thousands of times over a drag.
 *
 * A moment with no recording behind it comes back empty, and the picture goes with
 * it: an old frame sitting under a new moment would be a lie about the time.
 * [enabled] on false does the same without asking, for a camera whose stretches
 * you already know it has nothing in.
 *
 * [backdrop] is the faint block that shows where a picture will appear. It belongs
 * on a tile, where the block is the tile, and not on the full screen picture, where
 * the picture is fitted and the block would stand out above and below it as a slab
 * of nothing.
 */
@Composable
internal fun MomentStill(
    repository: FrigoRepository,
    camera: String,
    moment: Double,
    modifier: Modifier = Modifier,
    height: Int = 720,
    minGapMillis: Long = MainStillGap,
    enabled: Boolean = true,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    backdrop: Boolean = true,
) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }
    // The moment as it stands now. Held in a state so the loop below wakes when
    // the finger moves, without being tied to the moment it started on and
    // without re-emitting a moment that has not changed.
    val latest by rememberUpdatedState(moment)

    LaunchedEffect(camera, height, minGapMillis, enabled) {
        if (!enabled) {
            frame = null
            return@LaunchedEffect
        }
        snapshotFlow { latest }
            .conflate()
            .collect { at ->
                val started = System.nanoTime()
                val key = "$camera|${at.toLong()}|$height"
                val bytes = StillCache.get(key)
                    ?: runCatching { repository.recordingStill(camera, at, height) }
                        .getOrNull()
                        ?.also { StillCache.put(key, it) }

                if (bytes != null) {
                    val picture = withContext(Dispatchers.Default) { bytes.toImageBitmapOrNull() }
                    if (picture != null) frame = picture
                }

                val spent = (System.nanoTime() - started) / 1_000_000
                if (spent < minGapMillis) delay(minGapMillis - spent)
            }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (backdrop) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
                } else {
                    Color.Transparent
                }
            ),
    ) {
        frame?.let { picture ->
            Image(
                bitmap = picture,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Rounds a moment to the step the bar moves in. */
internal fun quantise(moment: Double): Double = (moment / ScrubStep).toLong() * ScrubStep

/** Midnight at the start of that day, where the phone is. */
internal fun startOfDay(day: String, zone: ZoneId): Double =
    LocalDate.parse(day).atStartOfDay(zone).toEpochSecond().toDouble()

/** "09:41", where the phone is. */
internal fun clock(moment: Double, zone: ZoneId): String =
    Instant.ofEpochSecond(moment.toLong()).atZone(zone).format(TimeOfDay)

/**
 * "Sun 1 Nov 09:41", where the phone is. The big readout above the bar, so the day
 * is there while you drag across one and the burned-in timestamp in the picture is
 * not the only thing telling you when you are.
 */
internal fun stamp(moment: Double, zone: ZoneId): String =
    Instant.ofEpochSecond(moment.toLong()).atZone(zone).let { at ->
        "${DayAndMonth.format(at)} ${TimeOfDay.format(at)}"
    }

/**
 * The last moment a camera recorded on a day, which is where the playhead starts:
 * usually what you came for. Never the end of the day, which is midnight on the
 * clock and reads as tomorrow having begun, and never the future.
 */
internal fun lastRecorded(spans: List<RecordingSpan>, dayStart: Double): Double {
    val latest = minOf(
        spans.lastOrNull()?.endTime ?: dayStart,
        dayStart + SecondsInADay - ScrubStep,
        Instant.now().epochSecond.toDouble(),
    )
    return quantise(latest)
}

/** "Today", "Yesterday", or the day and month. */
private fun dayLabel(day: String, zone: ZoneId): String {
    val date = LocalDate.parse(day)
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DayAndMonth)
    }
}

private val TimeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayAndMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
