package nz.mckenzie.frigo.ui.recordings

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.RecordingDay
import nz.mckenzie.frigo.data.RecordingSpan
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.friendlyMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much of the recording one press of Play fetches. */
private const val ClipSeconds = 120.0

/** How far before the moment you picked the clip starts, so you see what led up to it. */
private const val LeadInSeconds = 15.0

private const val SecondsInADay = 86_400.0

/**
 * The days a camera kept recordings for, and a bar of the day to drag along.
 *
 * Frigate cuts a clip out of the recordings when it is asked for one, so playback
 * is a clip of the two minutes from where you dropped the marker, shown in the
 * same viewer an event opens in. The marker can sit in a gap where nothing was
 * recorded, and the screen says so rather than playing two minutes of black.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    repository: FrigoRepository,
    camera: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    var days by remember { mutableStateOf<List<RecordingDay>>(emptyList()) }
    var spans by remember { mutableStateOf<List<RecordingSpan>>(emptyList()) }
    var day by rememberSaveable(camera) { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable(camera) { mutableDoubleStateOf(0.0) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }
    var subject by remember { mutableStateOf<ClipSubject?>(null) }
    // Bumped by Try again. The days are fetched again when it changes.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(camera, attempt) {
        loading = true
        problem = null
        runCatching { repository.recordingDays(camera, zone.id) }
            .onSuccess { found ->
                days = found
                if (day == null) day = found.firstOrNull()?.day
                if (found.isEmpty()) problem = "Frigate has kept no recordings of this camera."
            }
            .onFailure { problem = it.friendlyMessage() }
        loading = false
    }

    // Which stretches of the chosen day were recorded, and where the marker lands
    // to begin with: the last moment recorded, which is usually what you came for.
    // Never the very end of the day, which is midnight on the clock and reads as
    // the next day having started.
    LaunchedEffect(camera, day) {
        val chosenDay = day ?: return@LaunchedEffect
        val start = startOfDay(chosenDay, zone)
        // Yesterday's stretches are not this day's, so they go before the fetch.
        spans = emptyList()
        spans = runCatching { repository.recordingSpans(camera, start, start + SecondsInADay) }
            .getOrDefault(emptyList())
        val latest = minOf(
            spans.lastOrNull()?.endTime ?: start,
            start + SecondsInADay - 15,
            Instant.now().epochSecond.toDouble(),
        )
        chosen = (latest / 15.0).toLong() * 15.0
    }

    val dayStart = day?.let { startOfDay(it, zone) } ?: 0.0
    val recorded = spans.any { chosen >= it.startTime && chosen <= it.endTime }

    fun play() {
        val start = (chosen - LeadInSeconds).coerceAtLeast(dayStart)
        val end = (start + ClipSeconds)
            .coerceAtMost(dayStart + SecondsInADay)
            .coerceAtMost(Instant.now().epochSecond.toDouble())
        if (end <= start) return

        val key = "$camera-${start.toLong()}-${end.toLong()}"
        subject = ClipSubject(
            title = title,
            subtitle = "${clock(start, zone)} to ${clock(end, zone)}",
            clip = { onProgress ->
                repository.clipFile(
                    source = repository.recordingClipSource(camera, start, end),
                    key = key,
                    onProgress = onProgress,
                )
            },
            stillKey = key,
            still = { repository.recordingStill(camera, start) },
            note = "Frigate has no recording of that stretch.",
        )
    }

    val watched = subject
    if (watched != null) {
        ClipViewer(subject = watched, onDismiss = { subject = null })
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Recordings")
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )

        when {
            loading && days.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            problem != null && days.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(problem.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { attempt++ }) { Text("Try again") }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    days.forEach { entry ->
                        FilterChip(
                            selected = entry.day == day,
                            onClick = { day = entry.day },
                            label = { Text(dayLabel(entry.day, zone)) },
                        )
                    }
                }

                Timeline(
                    dayStart = dayStart,
                    spans = spans,
                    chosen = chosen,
                    onPick = { chosen = it },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = clock(chosen, zone),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = if (recorded) {
                                "${clock(chosen - LeadInSeconds, zone)} to " +
                                    "${clock(chosen + ClipSeconds, zone)}"
                            } else {
                                "Nothing recorded then."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (recorded) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    Button(onClick = ::play, enabled = recorded) { Text("Play") }
                }

                if (recorded) {
                    MomentStill(repository = repository, camera = camera, moment = chosen)
                }
            }
        }
    }
}

/**
 * A day across the width, with the stretches that were recorded filled in and a
 * marker where you have dragged to.
 *
 * Twenty-four hours in 350 dp is about four minutes a point, so the marker moves
 * in fifteen-second steps and the time underneath says exactly where it is.
 */
@Composable
private fun Timeline(
    dayStart: Double,
    spans: List<RecordingSpan>,
    chosen: Double,
    onPick: (Double) -> Unit,
) {
    var width by remember { mutableStateOf(0f) }
    val recordedColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    val tickColor = MaterialTheme.colorScheme.outlineVariant

    fun pick(x: Float) {
        if (width <= 0f) return
        val fraction = (x / width).coerceIn(0f, 1f)
        val seconds = (dayStart + fraction * SecondsInADay) / 15.0
        onPick(seconds.toLong() * 15.0)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .onSizeChanged { width = it.width.toFloat() }
                .pointerInput(spans, dayStart) { detectTapGestures { pick(it.x) } }
                .pointerInput(spans, dayStart) {
                    detectDragGestures(onDragStart = { pick(it.x) }) { change, _ ->
                        pick(change.position.x)
                    }
                },
        ) {
            drawRoundRect(
                color = emptyColor,
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
            )
            for (hour in 1 until 24) {
                val x = size.width * hour / 24f
                drawLine(
                    color = tickColor,
                    start = Offset(x, size.height * 0.72f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            spans.forEach { span ->
                val from = ((span.startTime - dayStart) / SecondsInADay).toFloat()
                val to = ((span.endTime - dayStart) / SecondsInADay).toFloat()
                val left = (from * size.width).coerceIn(0f, size.width)
                val right = (to * size.width).coerceIn(0f, size.width)
                if (right > left) {
                    drawRect(
                        color = recordedColor,
                        topLeft = Offset(left, size.height * 0.16f),
                        size = Size(right - left, size.height * 0.54f),
                    )
                }
            }
            val marker = (((chosen - dayStart) / SecondsInADay).toFloat() * size.width)
                .coerceIn(0f, size.width)
            drawLine(
                color = markerColor,
                start = Offset(marker, 0f),
                end = Offset(marker, size.height),
                strokeWidth = 3f,
            )
            drawCircle(color = markerColor, radius = 6f, center = Offset(marker, 6f))
        }

        Row(Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "24").forEachIndexed { index, label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The picture Frigate cuts from the recording at that moment, so you know what
 * you are about to watch before you press Play.
 *
 * Dragging across the day changes [moment] many times a second, so the picture is
 * only asked for once the finger has settled. Each one is kept in the image cache
 * under its own moment, so dragging back over ground you have covered costs
 * nothing.
 */
@Composable
private fun MomentStill(repository: FrigoRepository, camera: String, moment: Double) {
    var settled by remember { mutableStateOf(moment) }

    LaunchedEffect(moment) {
        delay(350)
        settled = moment
    }

    CachedImage(
        cacheKey = "recording-$camera-${settled.toLong()}",
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Crop,
        load = { repository.recordingStill(camera, settled) },
    )
}

/** Midnight at the start of that day, where the phone is. */
private fun startOfDay(day: String, zone: ZoneId): Double =
    LocalDate.parse(day).atStartOfDay(zone).toEpochSecond().toDouble()

/** "09:41", where the phone is. */
private fun clock(moment: Double, zone: ZoneId): String =
    Instant.ofEpochSecond(moment.toLong()).atZone(zone).format(TimeOfDay)

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
