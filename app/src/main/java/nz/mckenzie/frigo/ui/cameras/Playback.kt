package nz.mckenzie.frigo.ui.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.RecordingDay
import nz.mckenzie.frigo.data.RecordingSpan
import nz.mckenzie.frigo.ui.components.toImageBitmapOrNull
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
 * Red for an alert, amber for a detection. The camera screen is black whatever
 * the theme is set to, so these are picked to sit on black rather than taken
 * from the palette.
 */
private val AlertSpike = Color(0xFFF05252)
private val DetectionSpike = Color(0xFFE9A23B)

private val LabelStyle = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.55f))

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
 * The day's bar under the live picture, and what sits around it: the days to
 * choose from, a strip of the whole day to jump with, the hour you are looking
 * at, and Play.
 *
 * Frigate cuts a clip out of the recordings when it is asked for one, so playback
 * is a clip of the two minutes from where the playhead sits, shown in the same
 * viewer an event opens in. The playhead can sit where nothing was recorded, and
 * [canPlay] is false there rather than playing two minutes of black.
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
    caption: String,
    captionIsWarning: Boolean,
    canPlay: Boolean,
    onDay: (String) -> Unit,
    onPick: (Double) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayStart = day?.let { startOfDay(it, zone) } ?: 0.0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
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

        DayStrip(
            dayStart = dayStart,
            spans = spans,
            spikes = spikes,
            chosen = chosen,
            onPick = onPick,
        )

        ScrubBar(
            dayStart = dayStart,
            zone = zone,
            spans = spans,
            spikes = spikes,
            chosen = chosen,
            onPick = onPick,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = moment,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (captionIsWarning) AlertSpike else Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onPlay, enabled = canPlay) { Text("Play") }
        }
    }
}

/** One day to choose, as a pill that lights up when it is the day on the bar. */
@Composable
private fun DayPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

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
            color = Color.White.copy(alpha = 0.1f),
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
                    color = Color.White.copy(alpha = 0.45f),
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
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(from, 0f),
            size = Size(window, size.height),
            cornerRadius = CornerRadius(3f, 3f),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.8f),
            topLeft = Offset(from, 0f),
            size = Size(window, size.height),
            cornerRadius = CornerRadius(3f, 3f),
            style = Stroke(width = 1.5f),
        )
        val head = x(chosen)
        drawLine(
            color = Color.White,
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
    val pick by rememberUpdatedState(onPick)
    val measurer = rememberTextMeasurer()

    // Pixels a second, and how wide the day is at that scale.
    val scale = if (width > 0f) (width / SecondsAcross).toFloat() else 0f
    val dayWidth = SecondsInADay.toFloat() * scale
    val least = -width / 2f
    val most = dayWidth - width / 2f

    fun momentAt(px: Float): Double = dayStart + px / scale

    val scrollable = rememberScrollableState { delta ->
        if (scale <= 0f) return@rememberScrollableState 0f
        // The day moves with your finger: drag left and later comes under the
        // playhead, the way a strip of film would.
        val wanted = (moved - delta).coerceIn(least, most)
        val used = moved - wanted
        moved = wanted
        pick(quantise(momentAt(wanted + width / 2f)))
        used
    }

    // The strip, a day chip, another camera, or the clock while live: the bar
    // moves to wherever that moment is. Under half a step is the bar reporting its
    // own movement, and is left alone.
    LaunchedEffect(chosen, dayStart, width) {
        if (scale <= 0f) return@LaunchedEffect
        val wanted = ((chosen - dayStart).toFloat() * scale - width / 2f).coerceIn(least, most)
        if (abs(wanted - moved) > scale * ScrubStep / 2f) moved = wanted
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .onSizeChanged { width = it.width.toFloat() }
            .scrollable(scrollable, Orientation.Horizontal),
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
                    color = Color.White.copy(alpha = if (major) 0.2f else 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (major) 1.5f else 1f,
                )
                tick += 60
            }

            var mark = (from / 600).toLong() * 600
            while (mark <= to) {
                val laid = measurer.measure(clock(dayStart + mark, zone), LabelStyle)
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
                        color = Color.White.copy(alpha = 0.7f),
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
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(
                color = Color.White,
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
 *
 * A camera with nothing recorded then comes back empty and shows as a blank tile,
 * which is worth knowing in itself.
 */
@Composable
internal fun OtherCameras(
    repository: FrigoRepository,
    cameras: List<Camera>,
    moment: Double,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
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
                Text(
                    text = other.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
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
            .background(Color.White.copy(alpha = 0.06f)),
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
