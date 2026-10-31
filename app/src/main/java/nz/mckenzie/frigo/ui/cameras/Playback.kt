package nz.mckenzie.frigo.ui.cameras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.RecordingDay
import nz.mckenzie.frigo.data.RecordingSpan
import nz.mckenzie.frigo.ui.components.toImageBitmapOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much of the recording one press of Play fetches. */
internal const val ClipSeconds = 120.0

/** How far before the moment you picked the clip starts, so you see what led up to it. */
internal const val LeadInSeconds = 15.0

internal const val SecondsInADay = 86_400.0

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

/**
 * The bar of the day under the live picture, and what sits around it: the days
 * to choose from, where the marker is, and Play.
 *
 * Frigate cuts a clip out of the recordings when it is asked for one, so playback
 * is a clip of the two minutes from where you dropped the marker, shown in the
 * same viewer an event opens in. The marker can sit in a gap where nothing was
 * recorded, and [canPlay] is false there rather than playing two minutes of black.
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
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

        TimelineBar(
            dayStart = dayStart,
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
 * A day across the width: the stretches that were recorded filled in, the moments
 * Frigate flagged as spikes along the bottom, and a marker where you have dragged
 * to.
 *
 * Twenty-four hours across the screen is a few minutes a point, so the marker
 * moves in fifteen-second steps and the time above says exactly where it is. A
 * spike is as wide as the review it stands for, and never narrower than a point.
 */
@Composable
private fun TimelineBar(
    dayStart: Double,
    spans: List<RecordingSpan>,
    spikes: List<FrigateReview>,
    chosen: Double,
    onPick: (Double) -> Unit,
) {
    var width by remember { mutableStateOf(0f) }

    fun pick(x: Float) {
        if (width <= 0f) return
        val fraction = (x / width).coerceIn(0f, 1f)
        val seconds = (dayStart + fraction * SecondsInADay) / 15.0
        onPick(seconds.toLong() * 15.0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(dayStart) { detectTapGestures { pick(it.x) } }
                .pointerInput(dayStart) {
                    detectDragGestures(onDragStart = { pick(it.x) }) { change, _ ->
                        pick(change.position.x)
                    }
                },
        ) {
            fun at(moment: Double): Float =
                (((moment - dayStart) / SecondsInADay).toFloat() * size.width)
                    .coerceIn(0f, size.width)

            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(8f, 8f),
            )

            val bandTop = size.height * 0.22f
            val bandBottom = size.height * 0.58f
            spans.forEach { span ->
                val left = at(span.startTime)
                val right = at(span.endTime)
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
            val detections = spikes.filter { it.severity != "alert" }
            val alerts = spikes.filter { it.severity == "alert" }

            fun drawSpikes(of: List<FrigateReview>, top: Float, colour: Color) {
                of.forEach { spike ->
                    val left = at(spike.startTime)
                    val right = at(spike.endTime ?: spike.startTime).coerceAtLeast(left + 2f)
                    drawRect(
                        color = colour,
                        topLeft = Offset(left, size.height * top),
                        size = Size(right - left, size.height * 0.94f - size.height * top),
                    )
                }
            }

            drawSpikes(detections, top = 0.78f, colour = DetectionSpike)
            drawSpikes(alerts, top = 0.64f, colour = AlertSpike)

            val marker = at(chosen)
            drawLine(
                color = Color.White,
                start = Offset(marker, 0f),
                end = Offset(marker, size.height),
                strokeWidth = 3f,
            )
            drawCircle(color = Color.White, radius = 6f, center = Offset(marker, 6f))
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "24").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
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
 * The recording at that moment, redrawn as the marker moves.
 *
 * Dragging changes [moment] many times a second, far more often than a picture of
 * it can be cut and sent. So the requests are conflated: one is in flight at a
 * time, and when it lands the next one asks for wherever the finger is by then. A
 * fast drag through a quiet hour plays as a run of pictures rather than a stall,
 * and the last one you land on is the one that stays.
 *
 * [minGapMillis] keeps one picture from following another too closely. Frigate
 * cuts each one out of the recording with ffmpeg when it is asked, so a server
 * that answers quickly would otherwise be asked thousands of times over a drag.
 *
 * Nothing is cached. A picture here belongs to one moment and nothing else, and
 * the cache is small enough to be wanted by thumbnails instead.
 *
 * A moment with no recording behind it comes back empty, and the picture goes
 * with it: an old frame sitting under a new moment would be a lie about the time.
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
                val bytes = runCatching { repository.recordingStill(camera, at, height) }.getOrNull()
                frame = bytes?.let { withContext(Dispatchers.Default) { it.toImageBitmapOrNull() } }

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

/** Midnight at the start of that day, where the phone is. */
internal fun startOfDay(day: String, zone: ZoneId): Double =
    LocalDate.parse(day).atStartOfDay(zone).toEpochSecond().toDouble()

/** "09:41", where the phone is. */
internal fun clock(moment: Double, zone: ZoneId): String =
    Instant.ofEpochSecond(moment.toLong()).atZone(zone).format(TimeOfDay)

/**
 * The last moment a camera recorded on a day, which is where the marker starts:
 * usually what you came for. Never the end of the day, which is midnight on the
 * clock and reads as tomorrow having begun, and never the future.
 */
internal fun lastRecorded(spans: List<RecordingSpan>, dayStart: Double): Double {
    val latest = minOf(
        spans.lastOrNull()?.endTime ?: dayStart,
        dayStart + SecondsInADay - 15,
        Instant.now().epochSecond.toDouble(),
    )
    return (latest / 15.0).toLong() * 15.0
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
