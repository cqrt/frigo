package nz.mckenzie.frigo.ui.cameras

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import nz.mckenzie.frigo.ui.components.HideSystemBars
import nz.mckenzie.frigo.ui.components.LiveSound
import nz.mckenzie.frigo.ui.components.StatusBarIcons
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * The heights and rates offered in the player, biggest first. Bigger means more
 * data, so the top of each list is the camera's own picture at its own frame rate,
 * and Frigate gives what the camera has rather than inventing the rest.
 */
private val Heights = listOf(2160, 1080, 720, 480)
private val Rates = listOf(30, 15, 10, 5, 2)

/** How far in a pinch can go. Past this the picture is just soft pixels. */
private const val MaxZoom = 5f

/**
 * The shape of a camera's picture, used to place what is drawn over the video.
 * Frigate's cameras send 16:9, and the picture is fitted rather than cropped, so a
 * frame of this shape sits exactly where the video is drawn.
 */
private const val PictureAspect = 16f / 9f

/**
 * How often the server is asked whether this camera is still sending. Frigate counts
 * frames per second, so a second apart would flicker on any camera that drops a
 * frame; a few seconds apart is quick enough to notice and rare enough to be free.
 */
private const val StatusEveryMillis = 8_000L

/** Readings in a row with nothing arriving before the picture is called offline. */
private const val SilentReadings = 2

/**
 * How a saved clip's moment is written into its name. Dashes and a dot rather than
 * colons, which a file system will not have, and in an order that sorts by date and
 * time in a folder listing.
 */
private val SaveStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm")

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
    // Which camera is on screen. Tapping one of the other cameras underneath
    // changes this rather than navigating, so Back still lands on the grid you came
    // from.
    var watching by rememberSaveable(cameraName) { mutableStateOf(cameraName) }
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
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
    // Whether the camera has stopped sending. The stream goes on handing back the
    // last frame it sent, so the picture itself never says so.
    var silent by remember(watching) { mutableStateOf(false) }

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
    // Set by tapping another camera's picture. The moment on the bar stays where
    // it is, so the same stretch of time is in front of you on the new camera,
    // rather than jumping to that camera's latest recording.
    var keepingMoment by remember { mutableStateOf(false) }
    var keptDay by remember { mutableStateOf<String?>(null) }
    // Set by picking another camera while a recording is playing: the moment it had
    // reached, and the day it was on, so the run carries on from there on the camera
    // picked instead of going back to where it started.
    var resumeAt by remember { mutableStateOf<Double?>(null) }
    var resumeDay by remember { mutableStateOf<String?>(null) }
    // Playback: the bar comes to rest and the recording from that moment plays in
    // the picture. [playFrom] is where the run started, [played] is where the
    // picture has got to, and the bar shows the one that is live.
    var playFrom by remember(watching) { mutableStateOf<Double?>(null) }
    var played by remember(watching) { mutableDoubleStateOf(0.0) }
    var wantsPlay by remember(watching) { mutableStateOf(false) }
    var playProblem by remember(watching) { mutableStateOf<String?>(null) }
    // The run whose first frame has been drawn, so the still that stands in for
    // the player can go away at that moment rather than before it.
    var shownFrom by remember(watching) { mutableStateOf<Double?>(null) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    val showing = if (playFrom != null) played else chosen
    // The other cameras' tiles follow the picture while a run plays, in whole
    // minutes. Each tile is a still Frigate cuts when it is asked for, so a moment
    // moving four times a second would be asked for far more often than it is
    // worth; a minute apart keeps the tiles within a minute of the picture for one
    // still a minute from each of them.
    val tileMoment = if (playFrom != null) floor(played / 60.0) * 60.0 else chosen

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // On its side there is no bar to touch, so the stream keeps running there.
    val streaming = live || landscape

    // The order the grid shows, so a swipe goes wherever the grid would.
    LaunchedEffect(Unit) {
        runCatching { repository.cameras() }.onSuccess { cameras = it }
    }

    // Live is now, so the day the stream belongs to comes back with it: the bar
    // reads today's clock rather than the afternoon you were looking through,
    // and whatever was playing stops.
    fun watchLive() {
        day = LocalDate.now(zone).toString()
        chosen = quantise(Instant.now().epochSecond.toDouble())
        played = chosen
        playFrom = null
        wantsPlay = false
        playProblem = null
        live = true
    }

    // A camera you tap underneath takes the picture. Live goes on being live, and a
    // recording that is playing carries on there from where it had got to; the
    // moment comes over on the bar either way.
    fun showCamera(name: String) {
        if (name == watching) return
        if (live) {
            watching = name
            return
        }
        val at = if (playFrom != null) played else resumeAt
        if (at != null) chosen = at
        resumeAt = at
        resumeDay = day
        keepingMoment = true
        keptDay = day
        watching = name
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

    // A camera that has gone quiet looks exactly like one that is working: the
    // stream keeps handing over the last frame it sent. Frigate counts the frames
    // each camera produces, so that count is asked every few seconds while the
    // stream is up. Two readings in a row, because a camera can miss a second of
    // frames and carry on as if nothing happened.
    LaunchedEffect(watching, streaming) {
        silent = false
        if (!streaming) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var quiet = 0
            while (isActive) {
                when (repository.cameraSending(watching)) {
                    true -> {
                        quiet = 0
                        silent = false
                    }

                    false -> if (++quiet >= SilentReadings) silent = true
                    // No answer is about the server rather than the camera, so the
                    // pill is left saying what it said before.
                    null -> Unit
                }
                delay(StatusEveryMillis)
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
        val kept = keepingMoment && day == keptDay
        chosen = if (kept) chosen else lastRecorded(found, start)
        if (!kept) {
            // Not the moment you were on, so not the run that was on it either.
            playFrom = null
            playProblem = null
        }
        keepingMoment = false
        // A run that was playing when a camera was picked carries on here, from the
        // moment the picture had reached. It waits for this camera's stretches
        // because those are what its pieces are cut from, and a stretch it has
        // nothing in is a still rather than a run that dies at once.
        val carry = resumeAt
        val carryDay = resumeDay
        if (carry != null) {
            resumeAt = null
            resumeDay = null
            if (kept && day == carryDay &&
                found.any { carry >= it.startTime && carry < it.endTime }
            ) {
                played = carry
                playFrom = carry
            }
        }
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
    val recorded =
        live || (showing <= now && spans.any { showing >= it.startTime && showing <= it.endTime })
    // The clip Play fetches, and the stretch the line under the time describes, so
    // the two never disagree. Live has nothing after the marker, so there it is the
    // two minutes up to now: what just happened, rather than what did not.
    val clipStart = (if (live) showing - ClipSeconds else showing - LeadInSeconds)
        .coerceAtLeast(dayStart)
    val clipEnd = (clipStart + ClipSeconds)
        .coerceAtMost(dayStart + SecondsInADay)
        .coerceAtMost(now)
    val canPlay = day != null && recorded && clipEnd > clipStart + 1

    // The bar has come to rest on a moment you moved it to, so the recording from
    // there plays. Waiting for the bar to be still is what keeps a drag on its way
    // somewhere from starting a download it will not use, and only a moment you
    // moved to counts: opening a camera does not start playing by itself.
    LaunchedEffect(chosen, wantsPlay, live, recorded) {
        if (!wantsPlay || live || !recorded) return@LaunchedEffect
        delay(SettleMillis)
        if (!live && recorded) {
            played = chosen
            playFrom = chosen
            playProblem = null
            wantsPlay = false
        }
    }

    // Live is the stream, and the stream has nothing to do with playback.
    LaunchedEffect(live) {
        if (live) {
            playFrom = null
            wantsPlay = false
            playProblem = null
        }
    }

    // The name a fetched clip is kept under. Play and Save ask for the same one, so
    // a stretch just watched in the viewer is copied off the phone rather than asked
    // for again.
    fun clipKey() = "$watching-${clipStart.toLong()}-${clipEnd.toLong()}"

    // The name a saved clip is offered under: the camera and the moment it starts,
    // so a folder of them sorts itself by date and time.
    fun saveName(): String {
        val at = LocalDateTime.ofInstant(Instant.ofEpochSecond(clipStart.toLong()), zone)
        return "frigo-$watching-${at.format(SaveStamp)}.mp4"
    }

    // The status bar's own icons sit on the page's background, so they follow it:
    // light icons on the night colours, dark ones in the day.
    val dayColours = MaterialTheme.colorScheme.background.luminance() > 0.5f
    StatusBarIcons(darkIcons = dayColours)
    // On its side, the picture gets the whole screen. No button: turning the
    // phone is the button.
    HideSystemBars(landscape)

    // The sound follows the speaker, so it comes from the stream while you are
    // watching the stream and from the recording while you are watching one.
    LiveSound(repository = repository, camera = watching, playing = sound && live) {
        sound = false
        noSound = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    // Size and frame rate are the stream's, so they are not offered
                    // on a recording, where nothing here changes it.
                    if (live) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Stream quality")
                        }
                        StreamMenu(
                            expanded = menuOpen,
                            height = height,
                            rate = rate,
                            onHeight = { height = it; menuOpen = false },
                            onRate = { rate = it; menuOpen = false },
                            onDismiss = { menuOpen = false },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
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
                    ZoomableStill(
                        bitmap = bitmap,
                        camera = watching,
                    )
                }

                if (connecting) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = problem ?: "Connecting…",
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (problem != null) {
                            Text(
                                text = "Retrying",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else {
                // The recording at the moment on the bar, filling the picture
                // frame whole rather than cropped. While it plays, the still of
                // where the run started covers the player until the first frame
                // is drawn, so the picture is never black while a piece is on its
                // way.
                val run = playFrom
                Box(Modifier.fillMaxSize()) {
                    if (run != null) {
                        RecordingRun(
                            repository = repository,
                            camera = watching,
                            from = run,
                            spans = spans,
                            until = minOf(dayStart + SecondsInADay, now),
                            speed = speed,
                            withSound = sound,
                            onPosition = { played = it },
                            onEnd = { last ->
                                chosen = last
                                played = last
                                playFrom = null
                            },
                            onProblem = { reason ->
                                playProblem = reason
                                playFrom = null
                            },
                            onFirstFrame = { shownFrom = run },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (run == null || shownFrom != run) {
                        MomentStill(
                            repository = repository,
                            camera = watching,
                            moment = run ?: chosen,
                            height = ScrubLines,
                            enabled = recorded,
                            contentScale = ContentScale.Fit,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            // The full screen picture sits on the page, so it gets
                            // no block behind it to stand out against.
                            backdrop = false,
                        )
                    }
                }
            }

            // The mode pill is the only thing on the picture. It sits in a frame
            // the shape of a camera's own, centred inside this box, so it lands on
            // the video's corner rather than in the empty space below it: the
            // picture is fitted, so a tall box leaves a band above and below.
            Box(
                modifier = Modifier
                    .aspectRatio(PictureAspect)
                    .align(Alignment.Center),
            ) {
                // What the picture is, said on the picture. A recording on its
                // side fills the screen, and there is no bar there to say where
                // you are.
                ModePill(
                    live = live,
                    offline = silent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                )
            }

            // The way back to the stream sits on the page beside the picture rather
            // than on it. It is an action rather than a signpost: the mode pill
            // already says where you are, and two dots saying different things is
            // one too many. On the stream there is nowhere to go back to, so it is
            // not there.
            if (!live) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .clickable(onClickLabel = "Go back to the stream") { watchLive() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Return to Live",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (playFrom != null) {
                SpeedButton(
                    speed = speed,
                    onClick = {
                        val at = PlaySpeeds.indexOf(speed)
                        speed = PlaySpeeds[(at + 1) % PlaySpeeds.size]
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                )
            }

            if (noSound) {
                Text(
                    text = "No sound came through.",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 44.dp)
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
            // The other cameras underneath, so you can see what they have without
            // going back to the grid: the same moment while you are on a recording,
            // and their stream a moment ago while you are watching one.
            if (cameras.size > 1) {
                val others = cameras.filter { it.name != watching }
                if (live) {
                    LiveCameras(
                        repository = repository,
                        cameras = others,
                        onPick = ::showCamera,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    OtherCameras(
                        repository = repository,
                        cameras = others,
                        moment = tileMoment,
                        onPick = ::showCamera,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            when {
                barReady -> TimelinePanel(
                    days = days,
                    day = day,
                    zone = zone,
                    spans = spans,
                    spikes = spikes,
                    chosen = showing,
                    moment = stamp(showing, zone),
                    // On the stream the line would name the two minutes up to now,
                    // which now lead nowhere, so it is only there on a recording,
                    // where it says what Save will write.
                    caption = if (live) {
                        null
                    } else {
                        playProblem ?: if (recorded) {
                            "${clock(clipStart, zone)} to ${clock(clipEnd, zone)}"
                        } else {
                            "Nothing recorded then."
                        }
                    },
                    captionIsWarning = playProblem != null || !recorded,
                    onDay = { next ->
                        // Another day is not now, so the stream steps aside for it.
                        if (next != LocalDate.now(zone).toString()) live = false
                        playFrom = null
                        wantsPlay = false
                        playProblem = null
                        day = next
                    },
                    onPick = { moment ->
                        // Touching the bar stops the picture and moves it, and the
                        // recording from where you leave it plays once it is still.
                        live = false
                        playFrom = null
                        playProblem = null
                        played = moment
                        chosen = moment
                        wantsPlay = true
                    },
                ) {
                    // Nothing in the corner on the stream: the two minutes up to now
                    // were what Play opened, and dragging the bar now plays a
                    // recording where it stands. On a recording the corner keeps the
                    // stretch instead, which is the thing a recording is for.
                    if (!live) {
                        SaveClipButton(
                            name = saveName(),
                            enabled = canPlay,
                            fetch = { onProgress ->
                                repository.clipFile(
                                    source = repository.recordingClipSource(
                                        watching,
                                        clipStart,
                                        clipEnd,
                                    ),
                                    key = clipKey(),
                                    onProgress = onProgress,
                                )
                            },
                        )
                    }
                }

                barProblem != null -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = barProblem.orEmpty(),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { attempt++ }) {
                        Text("Try again", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * What the stream is set to: its size and its frame rate, in two groups, with the
 * choice in use ticked. Opening the menu used to be seven identical lines with no
 * way to tell which two were on.
 */
@Composable
private fun StreamMenu(
    expanded: Boolean,
    height: Int,
    rate: Int,
    onHeight: (Int) -> Unit,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.width(228.dp).padding(vertical = 6.dp)) {
            MenuHeading("Resolution")
            Heights.forEach { option ->
                MenuChoice(text = "${option}p", chosen = option == height) { onHeight(option) }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            )
            MenuHeading("Frames per second")
            Rates.forEach { option ->
                MenuChoice(text = "$option", chosen = option == rate) { onRate(option) }
            }
        }
    }
}

/** The name over a group of choices in the stream menu. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
    )
}

/**
 * One choice in the stream menu. The one in use is tinted and ticked, so the menu
 * says what the stream is set to without being closed first.
 */
@Composable
private fun MenuChoice(text: String, chosen: Boolean, onClick: () -> Unit) {
    val tint = if (chosen) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (chosen) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClickLabel = text, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (chosen) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
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
