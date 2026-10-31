package nz.mckenzie.frigo.ui.cameras

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.MjpegReader
import nz.mckenzie.frigo.data.RecordingDay
import nz.mckenzie.frigo.data.RecordingSpan
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.components.HideSystemBars
import nz.mckenzie.frigo.ui.components.LiveSound
import nz.mckenzie.frigo.ui.components.StatusBarIcons
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** The heights and rates offered in the player. Bigger means more data. */
private val Heights = listOf(1080, 720, 480)
private val Rates = listOf(15, 10, 5, 2)

/** How far in a pinch can go. Past this the picture is just soft pixels. */
private const val MaxZoom = 5f

/**
 * One camera, live, with its timelines underneath.
 *
 * The bar under the picture is the day. Drag along it and the picture steps aside
 * for the recording at that moment, the other cameras showing their own picture of
 * the same moment, so you can work out what happened before you go back to live.
 *
 * Turning the phone on its side puts the live picture on the whole screen and
 * leaves the bar behind: the stream keeps running there rather than sitting on a
 * still you cannot drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    repository: FrigoRepository,
    cameraName: String,
    onBack: () -> Unit,
) {
    // Which camera is on screen. Swiping changes this rather than navigating, so
    // Back still lands on the grid you came from.
    var watching by rememberSaveable(cameraName) { mutableStateOf(cameraName) }
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var zoomed by remember(watching) { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }

    val title = cameras.firstOrNull { it.name == watching }?.title ?: prettyCameraName(watching)

    var frame by remember(watching) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(watching) { mutableStateOf<String?>(null) }
    var connecting by remember(watching) { mutableStateOf(true) }
    var height by rememberSaveable(watching) { mutableIntStateOf(720) }
    var rate by rememberSaveable(watching) { mutableIntStateOf(5) }
    var menuOpen by remember { mutableStateOf(false) }
    var sound by rememberSaveable(watching) { mutableStateOf(false) }
    var noSound by rememberSaveable(watching) { mutableStateOf(false) }

    // The day on the bar, where along it the marker is, and what Frigate flagged
    // on that day. Live means the picture is the stream and the marker sits at
    // now; touching the bar steps the stream aside for the recording there. None
    // of this is keyed on the camera: a camera you tap into keeps the moment.
    var days by remember { mutableStateOf<List<RecordingDay>>(emptyList()) }
    var spans by remember { mutableStateOf<List<RecordingSpan>>(emptyList()) }
    var spikes by remember { mutableStateOf<List<FrigateReview>>(emptyList()) }
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    var chosen by rememberSaveable { mutableDoubleStateOf(0.0) }
    var live by rememberSaveable { mutableStateOf(true) }
    var barReady by remember { mutableStateOf(false) }
    var barProblem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var subject by remember { mutableStateOf<ClipSubject?>(null) }
    // Set by tapping another camera's picture. The moment on the bar stays where
    // it is, so the same stretch of time is in front of you on the new camera,
    // rather than jumping to that camera's latest recording.
    var keepingMoment by remember { mutableStateOf(false) }
    var keptDay by remember { mutableStateOf<String?>(null) }

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // On its side there is no bar to touch, so the stream keeps running there.
    val streaming = live || landscape

    // The order the grid shows, so a swipe goes wherever the grid would.
    LaunchedEffect(Unit) {
        runCatching { repository.cameras() }.onSuccess { cameras = it }
    }

    // The last camera leads round to the first, so a swipe is never a dead end.
    fun step(by: Int) {
        val names = cameras.map { it.name }
        val at = names.indexOf(watching)
        if (names.size < 2 || at < 0) return
        // A swipe is browsing live, so it goes back to the stream rather than
        // leaving you on another camera's still.
        keepingMoment = false
        live = true
        watching = names[(at + by + names.size) % names.size]
        zoomed = false
    }

    LaunchedEffect(watching, height, rate, streaming) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!streaming) return@repeatOnLifecycle
            connecting = true
            problem = null
            while (isActive) {
                try {
                    repository.openMjpeg(watching, height, rate).use { response ->
                        val body = response.body ?: throw IOException("The server sent no stream")
                        val reader = MjpegReader(body.byteStream())
                        withContext(Dispatchers.IO) {
                            while (isActive) {
                                val bytes = reader.nextFrame() ?: break
                                val bitmap = MjpegReader.decode(bytes) ?: continue
                                withContext(Dispatchers.Main.immediate) {
                                    frame = bitmap.asImageBitmap()
                                    connecting = false
                                }
                            }
                        }
                    }
                    problem = "The stream stopped."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    problem = error.friendlyMessage()
                }
                connecting = true
                delay(2_000)
            }
        }
    }

    // The days this camera kept, and the day to open on.
    LaunchedEffect(watching, attempt) {
        barProblem = null
        runCatching { repository.recordingDays(watching, zone.id) }
            .onSuccess { found ->
                days = found
                // From another camera, or the first look: the newest day it kept.
                if (found.none { it.day == day }) day = found.firstOrNull()?.day
                if (found.isEmpty()) {
                    barReady = false
                    barProblem = "Frigate has kept no recordings of this camera."
                }
            }
            .onFailure {
                barReady = false
                barProblem = it.friendlyMessage()
            }
    }

    // Which stretches of the chosen day were recorded, the moments Frigate flagged
    // on it, and where the marker lands: the last moment recorded, which is
    // usually what you came for.
    LaunchedEffect(watching, day, attempt) {
        val chosenDay = day ?: return@LaunchedEffect
        val start = startOfDay(chosenDay, zone)
        val end = start + SecondsInADay
        // The last camera's stretches stay on the bar until this one's arrive, so
        // a tap between cameras does not flash a gap.
        val found = runCatching { repository.recordingSpans(watching, start, end) }
            .getOrDefault(emptyList())
        val foundSpikes = runCatching { repository.reviewSpikes(watching, start, end) }
            .getOrDefault(emptyList())
        spans = found
        spikes = foundSpikes
        // A camera you tapped into keeps the moment you were looking at, as long
        // as it has that day at all.
        chosen = if (keepingMoment && day == keptDay) {
            chosen
        } else {
            lastRecorded(found, start)
        }
        keepingMoment = false
        barReady = true
    }

    // While the picture is live the playhead belongs at now, so the bar says where
    // in the day you are and Play starts from here. Half minutes, the same step the
    // bar moves in.
    LaunchedEffect(live, day, zone, watching) {
        if (!live || day != LocalDate.now(zone).toString()) return@LaunchedEffect
        while (true) {
            chosen = quantise(Instant.now().epochSecond.toDouble())
            delay(ScrubStep.toLong() * 1_000)
        }
    }

    val dayStart = day?.let { startOfDay(it, zone) } ?: 0.0
    val now = Instant.now().epochSecond.toDouble()
    // A moment after now has no recording behind it yet, however the day's
    // stretches line up, so it counts as a gap like any other.
    val recorded = live || (chosen <= now && spans.any { chosen >= it.startTime && chosen <= it.endTime })
    // The clip Play fetches, and the stretch the line under the time describes, so
    // the two never disagree. Live has nothing after the marker, so there it is the
    // two minutes up to now: what just happened, rather than what did not.
    val clipStart = (if (live) chosen - ClipSeconds else chosen - LeadInSeconds)
        .coerceAtLeast(dayStart)
    val clipEnd = (clipStart + ClipSeconds)
        .coerceAtMost(dayStart + SecondsInADay)
        .coerceAtMost(now)
    val canPlay = day != null && recorded && clipEnd > clipStart + 1

    fun play() {
        if (!canPlay) return
        val key = "$watching-${clipStart.toLong()}-${clipEnd.toLong()}"
        subject = ClipSubject(
            title = title,
            subtitle = "${clock(clipStart, zone)} to ${clock(clipEnd, zone)}",
            clip = { onProgress ->
                repository.clipFile(
                    source = repository.recordingClipSource(watching, clipStart, clipEnd),
                    key = key,
                    onProgress = onProgress,
                )
            },
            stillKey = key,
            still = { repository.recordingStill(watching, clipStart) },
            note = "Frigate has no recording of that stretch.",
        )
    }

    val watched = subject
    if (watched != null) {
        ClipViewer(subject = watched, onDismiss = { subject = null })
    }

    StatusBarIcons(darkIcons = false)
    // On its side, the picture gets the whole screen. No button: turning the
    // phone is the button.
    HideSystemBars(landscape)

    LiveSound(repository = repository, camera = watching, playing = sound) {
        sound = false
        noSound = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (!landscape) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { sound = !sound; noSound = false }) {
                        Icon(
                            imageVector = if (sound) FrigoIcons.Sound else FrigoIcons.SoundOff,
                            contentDescription = if (sound) "Turn the sound off" else "Turn the sound on",
                        )
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Stream quality")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Heights.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option}p") },
                                onClick = { height = option; menuOpen = false },
                            )
                        }
                        Rates.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("$option frames a second") },
                                onClick = { rate = option; menuOpen = false },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                frame?.let { bitmap ->
                    SwipeForNextCamera(
                        enabled = cameras.size > 1 && !zoomed,
                        onSwipe = ::step,
                    ) {
                        ZoomableStill(
                            bitmap = bitmap,
                            camera = watching,
                            onZoom = { zoomed = it },
                        )
                    }
                }

                if (connecting) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = problem ?: "Connecting…",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (problem != null) {
                            Text(
                                text = "Retrying",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (frame != null && !landscape) {
                    Text(
                        text = "MJPEG · ${height}p · $rate fps" + if (sound) " · sound" else "",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                // The recording at the moment on the bar, filling the picture
                // frame whole rather than cropped.
                SwipeForNextCamera(
                    enabled = cameras.size > 1,
                    onSwipe = ::step,
                ) {
                    MomentStill(
                        repository = repository,
                        camera = watching,
                        moment = chosen,
                        height = ScrubLines,
                        enabled = recorded,
                        contentScale = ContentScale.Fit,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // The way back to the stream, on the picture you are looking at.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable { live = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color(0xFF4ADE80), CircleShape),
                    )
                    Text(
                        text = "Live",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (noSound) {
                Text(
                    text = "No sound came through.",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = if (landscape) 12.dp else 46.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // On its side there is no bar, so the sound sits over the picture.
            if (landscape) {
                IconButton(
                    onClick = { sound = !sound; noSound = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (sound) FrigoIcons.Sound else FrigoIcons.SoundOff,
                        contentDescription = if (sound) "Turn the sound off" else "Turn the sound on",
                        tint = Color.White,
                    )
                }
            }
        }

        if (!landscape) {
            // The other three at the same moment, so you can tell which one caught
            // what you are looking for without going back to the grid.
            if (!live && cameras.size > 1) {
                OtherCameras(
                    repository = repository,
                    cameras = cameras.filter { it.name != watching },
                    moment = chosen,
                    onPick = { name ->
                        keepingMoment = true
                        keptDay = day
                        watching = name
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                barReady -> TimelinePanel(
                    days = days,
                    day = day,
                    zone = zone,
                    spans = spans,
                    spikes = spikes,
                    chosen = chosen,
                    moment = clock(chosen, zone),
                    caption = if (recorded) {
                        "${clock(clipStart, zone)} to ${clock(clipEnd, zone)}"
                    } else {
                        "Nothing recorded then."
                    },
                    captionIsWarning = !recorded,
                    canPlay = canPlay,
                    onDay = { next ->
                        // Another day is not now, so the stream steps aside for it.
                        if (next != LocalDate.now(zone).toString()) live = false
                        day = next
                    },
                    onPick = { moment ->
                        live = false
                        chosen = moment
                    },
                    onPlay = ::play,
                )

                barProblem != null -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = barProblem.orEmpty(),
                        color = Color.White.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { attempt++ }) {
                        Text("Try again", color = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * The picture, with a pinch to zoom in, a drag to move around it, and a double
 * tap to go back to the whole frame.
 *
 * A new frame arrives every moment, so the zoom belongs to the screen rather than
 * to any one picture: the frames change underneath while the view stays where you
 * put it.
 */
@Composable
private fun ZoomableStill(
    bitmap: ImageBitmap,
    camera: String,
    onZoom: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scale by remember(camera) { mutableFloatStateOf(1f) }
    var offset by remember(camera) { mutableStateOf(Offset.Zero) }
    var viewport by remember(camera) { mutableStateOf(IntSize.Zero) }
    var fitted by remember(camera) { mutableStateOf(IntSize.Zero) }

    // Zooming keeps the point between your fingers where it is, which is what
    // makes a pinch feel like it is grabbing the picture.
    fun zoomTo(next: Float, around: Offset, drag: Offset = Offset.Zero) {
        val factor = next / scale
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val fromCentre = around - centre
        val moved = fromCentre - (fromCentre - offset) * factor + drag
        scale = next
        offset = keepInside(moved, next, fitted, viewport)
        onZoom(next > 1f)
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(camera) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoom(false)
                        } else {
                            zoomTo(2.5f, point)
                        }
                    },
                )
            }
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MaxZoom)
                    zoomTo(next, centroid, pan)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .onSizeChanged { fitted = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/**
 * Stops the picture being dragged away from the screen: it can move until its
 * edge meets the edge of the view, and no further.
 */
private fun keepInside(offset: Offset, scale: Float, fitted: IntSize, viewport: IntSize): Offset {
    val roomX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val roomY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-roomX, roomX),
        y = offset.y.coerceIn(-roomY, roomY),
    )
}

/** How far a finger has to travel sideways before it counts as a swipe. */
private val SwipeDistance = 90.dp

/**
 * Watches for a sideways swipe and says which way it went: 1 for the next camera,
 * -1 for the one before.
 *
 * The picture underneath takes traffic first unless [enabled] is true, in which
 * case this gets the events before it does. When you are zoomed in, a sideways
 * drag is you moving around the picture, so [enabled] is false and this keeps out
 * of the way entirely.
 *
 * Only a drag that is mostly sideways and long enough counts, and only a drag
 * consumes anything, so a tap and a double tap still reach the picture.
 */
@Composable
private fun SwipeForNextCamera(
    enabled: Boolean,
    onSwipe: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val swipe by rememberUpdatedState(onSwipe)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val far = SwipeDistance.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var across = 0f
                    var downTheScreen = 0f
                    var swiping = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        across += change.positionChange().x
                        downTheScreen += change.positionChange().y
                        if (!swiping && abs(across) > slop && abs(across) > abs(downTheScreen)) {
                            swiping = true
                        }
                        if (swiping) change.consume()
                        if (!change.pressed) break
                    }
                    if (swiping && abs(across) >= far) swipe(if (across < 0f) 1 else -1)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
