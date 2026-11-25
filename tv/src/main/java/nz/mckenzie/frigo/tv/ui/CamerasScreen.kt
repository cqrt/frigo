package nz.mckenzie.frigo.tv.ui

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.tv.R
import nz.mckenzie.frigo.tv.data.Camera
import nz.mckenzie.frigo.tv.data.Ring
import nz.mckenzie.frigo.tv.data.ScreenGrid
import nz.mckenzie.frigo.tv.data.ScreenOne
import nz.mckenzie.frigo.tv.data.TvRelease
import nz.mckenzie.frigo.tv.data.TvRepository
import nz.mckenzie.frigo.tv.data.TvUpdater
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.cos
import kotlin.math.sin

/** A small live picture on the right: enough to see somebody walk in. */
private const val TileHeight = 360
private const val TileFps = 2

/** How much of the stream the player keeps, and how much it waits for before it starts. */
private const val LiveBufferMillis = 3_000
private const val LiveStartMillis = 500

/** The strip's width. Three cameras fit, and the fourth is a press of the arrow away. */
private val RailWidth = 260.dp

/** How long a camera that saw a person or a car keeps the big picture. */
private const val TakeoverMillis = 30_000L

/** How often the server is asked whether something has just been seen. */
private const val TakeoverPollMillis = 8_000L

/** How long a picture takes to slide aside for the next one. */
private const val SlideMillis = 700

/** The few seconds asked for twice, so an alert arriving between two asks is not missed. */
private const val OverlapSeconds = 5L

/**
 * The screen the television exists for: one camera big, the rest down the right.
 *
 * The chosen camera is the one thing on the screen that is not a tile. The strip
 * is the sorted list with that camera left out, so a camera moved into the big
 * picture goes back into the strip in the place its name gives it, not at the end.
 */
@Composable
fun CamerasScreen(
    repository: TvRepository,
    cameras: List<Camera>,
    newAlerts: Boolean = false,
    onOpenAlerts: () -> Unit = {},
    ring: Ring? = null,
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val settingsFocus = remember { FocusRequester() }
    val pictureFocus = remember { FocusRequester() }
    val symbolFocus = remember { FocusRequester() }

    // The cameras this television is set to show. The server's list stays whole
    // behind it: the alerts page still names every camera Frigate flagged, and
    // both lists in Settings still reach the ones that are turned off.
    val hidden by repository.hidden.collectAsStateWithLifecycle(initialValue = emptySet())
    val shown = remember(cameras, hidden) { cameras.filterNot { it.name in hidden } }
    val current = shown.firstOrNull { it.name == chosen } ?: shown.firstOrNull()

    // A wall is every camera at once, so there is nothing to open on unless a
    // press asks for one, and that press puts the one-camera layout up instead.
    val screen by repository.screen.collectAsStateWithLifecycle(initialValue = ScreenOne)
    var wallOpen by rememberSaveable { mutableStateOf(false) }
    // The tile the arrows were on, so coming back to the wall finds them there.
    var wallAt by rememberSaveable { mutableStateOf<String?>(null) }

    // The picture that took over, and when it gives the screen back. A press on
    // a camera by hand ends the takeover: the viewer has chosen.
    var takeoverFrom by remember { mutableStateOf<String?>(null) }
    var takeoverUntil by remember { mutableLongStateOf(0L) }
    // A picture that changes by itself should not move the arrows.
    var skipFocus by remember { mutableStateOf(false) }
    // Whether this screen was full screen before a bell filled it, so the room
    // gets back what it had rather than a picture it did not ask for.
    var bellFullScreen by remember { mutableStateOf(false) }
    val takeoverOn by repository.takeover.collectAsStateWithLifecycle(initialValue = true)
    val soundOn by repository.liveSound.collectAsStateWithLifecycle(initialValue = true)
    val defaultCamera by repository.defaultCamera.collectAsStateWithLifecycle()
    val rotation by repository.rotation.collectAsStateWithLifecycle(initialValue = 0)
    val scope = rememberCoroutineScope()

    // An old server has no reviews, so there is nothing behind the Alerts page.
    // The pill goes rather than opening an empty screen.
    val capabilities by repository.capabilities.collectAsStateWithLifecycle()
    val alertsAction = onOpenAlerts.takeIf { capabilities.reviews }

    fun takeOver(cameraName: String) {
        val now = System.currentTimeMillis()
        val shown = current?.name ?: return
        if (cameraName == shown) {
            if (takeoverFrom != null) takeoverUntil = now + TakeoverMillis
            return
        }
        if (takeoverFrom == null) takeoverFrom = shown
        skipFocus = true
        chosen = cameraName
        takeoverUntil = now + TakeoverMillis
    }

    // One camera along, the way an arrow press asks. A press is a choice, so it
    // ends a takeover and stops the loop where it is.
    fun stepBy(delta: Int) {
        val at = shown.indexOfFirst { it.name == chosen }
        if (at < 0) return
        val next = shown[Math.floorMod(at + delta, shown.size)]
        takeoverFrom = null
        takeoverUntil = 0L
        skipFocus = true
        chosen = next.name
        if (rotation > 0) scope.launch { repository.setRotation(0) }
    }

    LaunchedEffect(shown, defaultCamera) {
        val wanted = chosen
        if (wanted == null || shown.none { it.name == wanted }) {
            // The television opens on the camera Settings names, or on the first.
            chosen = shown.firstOrNull { it.name == defaultCamera }?.name
                ?: shown.firstOrNull()?.name
        }
    }

    // The television is paired once, so the way back to the code has to be a
    // press away rather than a reinstall. Back closes what is on top of the
    // screen: the Settings card, the camera opened over the wall, or the picture
    // filling it.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = screen == ScreenGrid && wallOpen) {
        // In a wall, a camera opened from it is a visit: Back goes to the wall
        // with the arrows on the tile that was pressed, not to a layout with a
        // strip nobody asked for.
        wallAt = current?.name
        fullScreen = false
        wallOpen = false
    }
    BackHandler(enabled = fullScreen && !(screen == ScreenGrid && wallOpen)) {
        fullScreen = false
    }

    if (current == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 36.dp),
        ) {
            Header(
                newAlerts = newAlerts,
                soundOn = soundOn,
                onToggleSound = { scope.launch { repository.setLiveSound(!soundOn) } },
                onAlerts = alertsAction,
                onSettings = { settingsOpen = true },
                settingsFocus = settingsFocus,
            )
            Spacer(Modifier.weight(1f))
            Text(
                // Every camera turned off is not a server with no cameras, and a
                // screen that says so with the way back is the difference.
                text = if (cameras.isNotEmpty()) {
                    stringResource(R.string.tv_cameras_all_off)
                } else {
                    stringResource(R.string.tv_no_cameras)
                },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
        if (settingsOpen) {
            SettingsPanel(
                repository = repository,
                cameras = cameras,
                shown = shown,
                hidden = hidden,
                onClose = { settingsOpen = false },
            )
        }
        return
    }

    val others = shown.filter { it.name != current.name }
    // Whether the one-camera layout is the one up: always unless the wall is on
    // and nothing has been opened from it.
    val stripShown = screen != ScreenGrid || wallOpen
    // Whether the picture is filling the screen and moving round by itself.
    val looping = fullScreen && stripShown && rotation > 0 && shown.size > 1

    // The press that fills the screen, and the press that gives it back. On a
    // wall it puts the wall back rather than a layout nobody asked for.
    fun togglePicture() {
        val name = current?.name ?: return
        if (screen == ScreenGrid && wallOpen) {
            wallAt = name
            fullScreen = false
            wallOpen = false
        } else {
            // Filling the screen is a choice; it ends a takeover.
            if (!fullScreen) {
                takeoverFrom = null
                takeoverUntil = 0L
                // The loop always opens on the camera Settings names as the
                // default, the way the television itself opens on it.
                if (rotation > 0 && shown.size > 1) {
                    val first = shown.firstOrNull { it.name == defaultCamera }?.name
                    if (first != null) {
                        skipFocus = true
                        chosen = first
                    }
                }
            }
            fullScreen = !fullScreen
        }
    }

    // While the picture fills the screen the arrows belong to it: left and right
    // walk the cameras, and up reaches the symbol that turns the loop on and off.
    fun fullScreenKey(event: KeyEvent): Boolean {
        val arrow = event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
            event.key == Key.DirectionUp || event.key == Key.DirectionDown
        // Both halves of a press are taken, so an arrow moves the cameras without
        // the focus walking off to the corner on its own.
        if (event.type != KeyEventType.KeyDown) return arrow
        return when (event.key) {
            Key.DirectionLeft -> {
                stepBy(-1)
                true
            }

            Key.DirectionRight -> {
                stepBy(1)
                true
            }

            Key.DirectionUp -> {
                scope.launch { runCatching { symbolFocus.requestFocus() } }
                true
            }

            Key.DirectionDown -> {
                scope.launch { runCatching { pictureFocus.requestFocus() } }
                true
            }

            else -> false
        }
    }
    var focusAt by remember { mutableIntStateOf(0) }
    // The place a press asked for, kept apart from where the focus happened to
    // be when the picture changed: a tile taken out of the list hands its focus
    // to the first one before anything can be asked for, and that is not where
    // the viewer was.
    var landing by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val wallRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()

    // A tile only exists once it has been laid out, and a request made before
    // then does nothing. Ask again until it lands, so the strip or the wall has
    // the arrows when it opens rather than a press later.
    suspend fun focusTile(tiles: Map<String, FocusRequester>, name: String?) {
        if (name == null) return
        repeat(8) {
            val requester = tiles[name]
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) {
                return
            }
            delay(60)
        }
    }

    suspend fun focusCamera(name: String) = focusTile(requesters, name)

    // The strip takes the focus when the screen opens, and keeps its place after
    // one camera replaces another: the press that moved a picture left the
    // arrows where they were. A picture that changed by itself leaves them be.
    LaunchedEffect(current.name, stripShown) {
        // The card has the arrows while it is up, so it is not for the strip to
        // take them, and a switch of layout would otherwise pull them behind it.
        if (settingsOpen || !stripShown || fullScreen) return@LaunchedEffect
        if (skipFocus) {
            skipFocus = false
            return@LaunchedEffect
        }
        val wanted = landing ?: focusAt
        landing = null
        val target = others.getOrNull(wanted) ?: others.firstOrNull() ?: return@LaunchedEffect
        val at = others.indexOf(target)
        if (at >= 0) runCatching { listState.animateScrollToItem(at) }
        focusCamera(target.name)
        focusAt = at
    }

    // Coming back to the wall puts the arrows back on the tile they left, and a
    // wall that has just appeared on the television opens on the camera Settings
    // names.
    LaunchedEffect(stripShown, shown.size) {
        if (settingsOpen || stripShown) return@LaunchedEffect
        delay(60)
        val wanted = wallAt?.takeIf { name -> shown.any { it.name == name } } ?: current.name
        focusTile(wallRequesters, wanted)
    }

    // A person or a car on a camera this television shows brings that camera up
    // for half a minute, then the screen goes back to what it was showing. Ask
    // every few seconds rather than hold a message feed open: a press that waits
    // eight seconds is not much of a wait for a picture, and the feed is a whole
    // connection to keep alive for it.
    LaunchedEffect(takeoverOn) {
        if (!takeoverOn) return@LaunchedEffect
        var since = System.currentTimeMillis() / 1_000 - OverlapSeconds
        val seen = mutableSetOf<String>()
        while (isActive) {
            delay(TakeoverPollMillis)
            val found = repository.alertTakeovers(since)
            since = System.currentTimeMillis() / 1_000 - OverlapSeconds
            val hit = found.firstOrNull { alert ->
                val camera = shown.firstOrNull { it.name == alert.cameraName }
                camera != null && camera.follows(alert.objects) && seen.add(alert.id)
            }
            if (hit != null && !settingsOpen && stripShown && (!fullScreen || rotation > 0)) {
                takeOver(hit.cameraName)
            }
        }
    }

    // The half minute is measured from the last thing seen, so a second visitor
    // keeps the picture up rather than letting it fall back mid-way.
    LaunchedEffect(takeoverUntil) {
        if (takeoverUntil <= 0L || takeoverFrom == null) return@LaunchedEffect
        delay(TakeoverMillis)
        val back = takeoverFrom ?: return@LaunchedEffect
        skipFocus = true
        chosen = back
        // A bell filled the screen; the room gets its layout back with it.
        if (bellFullScreen) {
            fullScreen = false
            bellFullScreen = false
        }
        takeoverFrom = null
        takeoverUntil = 0L
    }

    // The loop: every camera's stream is held open, so the picture that slides in
    // is already playing. A takeover, a bell or Settings holds the loop where it
    // is, and it carries on from that camera afterwards.
    LaunchedEffect(looping, rotation, takeoverFrom, shown) {
        if (!looping || takeoverFrom != null) return@LaunchedEffect
        while (isActive) {
            delay(rotation * 1_000L + SlideMillis)
            val at = shown.indexOfFirst { it.name == chosen }
            if (at < 0) return@LaunchedEffect
            skipFocus = true
            chosen = shown[(at + 1) % shown.size].name
        }
    }

    // A bell is the takeover's louder cousin: it fills the screen for the same
    // half minute, because a visitor is the one thing worth covering a wall for.
    // It is not the Camera auto switch and does not obey it: the automation that
    // rings the television was written on purpose, one press at a time.
    //
    // A bell that names a camera this television has turned off leaves the
    // screen alone: it asked for a picture the room has decided not to have.
    LaunchedEffect(ring) {
        val pressed = ring ?: return@LaunchedEffect
        val named = pressed.camera
        val target = if (named == null) {
            shown.firstOrNull { it.name == defaultCamera } ?: shown.firstOrNull()
        } else {
            shown.firstOrNull { it.name == named }
        } ?: return@LaunchedEffect
        val showing = current?.name
        if (takeoverFrom == null) takeoverFrom = showing
        bellFullScreen = !fullScreen
        chosen = target.name
        fullScreen = true
        takeoverUntil = System.currentTimeMillis() + TakeoverMillis
    }

    // A change of layout starts the page again: a wall is a wall, not the camera
    // that happened to be open before the setting was touched.
    LaunchedEffect(screen) {
        wallOpen = false
        fullScreen = false
    }

    // Closing Settings puts the arrows back where they were, rather than leaving
    // nothing focused for the next press to fall into.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) return@LaunchedEffect
        delay(80)
        if (stripShown) {
            val target = others.getOrNull(focusAt) ?: others.firstOrNull()
            target?.let { focusCamera(it.name) }
        } else {
            val wanted = wallAt?.takeIf { name -> shown.any { it.name == name } } ?: current.name
            focusTile(wallRequesters, wanted)
        }
    }

    // The picture takes the focus when it fills the screen, and gives it back to
    // the strip when the strip comes back.
    LaunchedEffect(fullScreen) {
        if (settingsOpen) return@LaunchedEffect
        delay(80)
        if (fullScreen) {
            runCatching { pictureFocus.requestFocus() }
        } else if (stripShown) {
            val target = others.getOrNull(focusAt) ?: others.firstOrNull()
            target?.let { focusCamera(it.name) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (fullScreen && stripShown && !settingsOpen) {
                    Modifier.onPreviewKeyEvent { event -> fullScreenKey(event) }
                } else {
                    Modifier
                },
            ),
    ) {
        if (!stripShown) {
            CameraWall(
                repository = repository,
                cameras = shown,
                newAlerts = newAlerts,
                soundOn = soundOn,
                onToggleSound = { scope.launch { repository.setLiveSound(!soundOn) } },
                onAlerts = alertsAction,
                onSettings = { settingsOpen = true },
                settingsFocus = settingsFocus,
                requesters = wallRequesters,
                onFocused = { wallAt = it },
                onOpen = { camera ->
                    // The press asks for one camera, so it fills the screen. Back
                    // brings the wall back rather than a layout nobody asked for.
                    chosen = camera.name
                    wallAt = camera.name
                    wallOpen = true
                    fullScreen = true
                },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = if (fullScreen) 0.dp else 24.dp,
                        vertical = if (fullScreen) 0.dp else 36.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (fullScreen) 0.dp else 24.dp),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!fullScreen) {
                        Header(
                            newAlerts = newAlerts,
                            soundOn = soundOn,
                            onToggleSound = { scope.launch { repository.setLiveSound(!soundOn) } },
                            onAlerts = alertsAction,
                            onSettings = { settingsOpen = true },
                            settingsFocus = settingsFocus,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    if (looping) {
                        RotationBand(
                            repository = repository,
                            cameras = shown,
                            chosen = current.name,
                            focusRequester = pictureFocus,
                            onToggle = { togglePicture() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        BigPicture(
                            repository = repository,
                            camera = current,
                            fullScreen = fullScreen,
                            muted = !soundOn,
                            focusRequester = pictureFocus,
                            onToggle = { togglePicture() },
                            modifier = if (fullScreen) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            },
                        )
                    }
                    if (!fullScreen) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (!fullScreen) {
                    LazyColumn(
                        modifier = Modifier.width(RailWidth).fillMaxHeight(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(others, key = { _, camera -> camera.name }) { index, camera ->
                            val requester = requesters.getOrPut(camera.name) { FocusRequester() }
                            CameraTile(
                                repository = repository,
                                camera = camera,
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .focusProperties {
                                        // Left is the picture, from anywhere in the
                                        // strip. Up from the first camera is the
                                        // button that leads back to the pairing code.
                                        left = pictureFocus
                                        if (index == 0) up = settingsFocus
                                    },
                                onFocused = { focusAt = index },
                                onClick = {
                                    // A press picks for the room, so the takeover ends here.
                                    takeoverFrom = null
                                    takeoverUntil = 0L
                                    landing = index
                                    chosen = camera.name
                                },
                            )
                        }
                    }
                }
            }
        }

        if (fullScreen && stripShown && shown.size > 1) {
            RotationSymbol(
                running = rotation > 0,
                focusRequester = symbolFocus,
                onClick = { scope.launch { repository.toggleRotation() } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
            )
        }

        if (settingsOpen) {
            SettingsPanel(
                repository = repository,
                cameras = cameras,
                shown = shown,
                hidden = hidden,
                onClose = { settingsOpen = false },
            )
        }
    }
}

/** Settings: the server, the cameras it shows, and what a newcomer does. */
@Composable
private fun SettingsPanel(
    repository: TvRepository,
    /** Every camera the server has, turned off or not: both lists reach them all. */
    cameras: List<Camera>,
    /** The ones this television shows. */
    shown: List<Camera>,
    hidden: Set<String>,
    onClose: () -> Unit,
) {
    val session by repository.session.collectAsStateWithLifecycle()
    val takeoverOn by repository.takeover.collectAsStateWithLifecycle(initialValue = true)
    val defaultCamera by repository.defaultCamera.collectAsStateWithLifecycle()
    val screen by repository.screen.collectAsStateWithLifecycle(initialValue = ScreenOne)
    val rotation by repository.rotation.collectAsStateWithLifecycle(initialValue = 0)
    val capabilities by repository.capabilities.collectAsStateWithLifecycle()
    val ringUrl by repository.ringUrl.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // A newer Frigo TV, looked for once when the card opens. GitHub counts
    // anonymous asks by the hour, so opening and closing the card is one ask.
    val updater = remember { TvUpdater(context) }
    var update by remember { mutableStateOf<TvUpdate>(TvUpdate.Looking) }
    LaunchedEffect(Unit) {
        update = runCatching { updater.latest() }
            .fold(
                onSuccess = { found ->
                    if (found == null || !found.isNewerThan(updater.current)) {
                        TvUpdate.Newest
                    } else {
                        TvUpdate.Waiting(found)
                    }
                },
                onFailure = { TvUpdate.Unreachable },
            )
    }

    // The camera the big picture opens on: the one that was picked, or the first
    // in Frigate's list on a television that has never picked one. A camera that
    // has been turned off does not open, so the row says the one that will.
    val opening = shown.firstOrNull { it.name == defaultCamera }
        ?: shown.firstOrNull()
        ?: cameras.firstOrNull()

    // The card is a page of rows, and a page taller than the glass scrolls. The
    // arrows follow what has the focus, so a row below the fold comes up to meet
    // them rather than being pressed at blind.
    val scrolling = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val rowAt = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    // Which short list is up, and nothing when the rows are.
    var picking by remember { mutableStateOf<CardPicker?>(null) }
    // The card is the whole screen while it is up, so the arrows move between
    // its rows and nowhere else. They come back round, which on eight rows is
    // kinder than stopping at an edge.
    var at by remember { mutableIntStateOf(DefaultCameraAt) }
    val rows = remember { List(CardRows) { FocusRequester() } }
    // A short list holds the arrows the same way. Without this a press beside it
    // finds nothing to land on and the focus walks out into the cameras behind
    // the card, where the arrows then move the cameras page instead.
    var pickerAt by remember { mutableIntStateOf(0) }
    var pickerCount by remember { mutableIntStateOf(2) }
    val pickerRows = remember { mutableStateMapOf<Int, FocusRequester>() }

    // A row answers only once it has been laid out, and changing the layout lays
    // the page out again, so ask again until the request lands.
    suspend fun focusRow(index: Int) {
        repeat(10) {
            if (runCatching { rows[index].requestFocus() }.isSuccess) return
            delay(60)
        }
    }

    // A row, where it sits in the card, and the arrows on it. The place is kept
    // so a press that lands on a row below the fold can bring the card up to it:
    // the arrows move the focus, and the card follows.
    fun placed(index: Int): Modifier = Modifier
        .focusRequester(rows[index])
        .onGloballyPositioned { place ->
            rowAt[index] = place.positionInParent().y to place.size.height.toFloat()
        }

    fun reveal(index: Int) {
        val (top, height) = rowAt[index] ?: return
        val view = viewport.toFloat()
        if (view <= 0f) return
        val wanted = when {
            top < scrolling.value.toFloat() -> top - 10f
            top + height > scrolling.value + view -> top + height - view + 10f
            else -> return
        }
        scope.launch {
            scrolling.animateScrollTo(wanted.toInt().coerceIn(0, scrolling.maxValue))
        }
    }

    suspend fun focusChoice(index: Int) {
        repeat(10) {
            val requester = pickerRows[index]
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) return
            delay(60)
        }
    }

    // The list goes when a choice is made, so the arrows come back to the row
    // that opened it rather than to nothing.
    fun closePicker(returnTo: Int) {
        picking = null
        at = returnTo
        scope.launch {
            // Let the list go first: a focused row taken away moves the arrows to
            // whichever row had them before, which is not the one that opened it.
            delay(90)
            focusRow(returnTo)
        }
    }

    fun openPicker(which: CardPicker, startAt: Int, count: Int) {
        pickerAt = startAt
        pickerCount = count
        picking = which
    }

    LaunchedEffect(picking, pickerCount) {
        if (picking == null) return@LaunchedEffect
        delay(60)
        focusChoice(pickerAt)
    }

    LaunchedEffect(Unit) {
        focusRow(DefaultCameraAt)
    }

    // A list is not the page, so opening one starts it at the top with the row
    // that opened it as the way back.
    LaunchedEffect(picking) {
        if (picking != null) scrolling.scrollTo(0)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (picking != null) {
                    // Up and down walk the list and come round; left and right
                    // have nothing beside them and are taken so nothing behind
                    // the card can have them.
                    when (event.key) {
                        Key.DirectionUp -> {
                            pickerAt = (pickerAt + pickerCount - 1) % pickerCount
                            scope.launch { focusChoice(pickerAt) }
                            true
                        }

                        Key.DirectionDown -> {
                            pickerAt = (pickerAt + 1) % pickerCount
                            scope.launch { focusChoice(pickerAt) }
                            true
                        }

                        Key.DirectionLeft,
                        Key.DirectionRight,
                        -> true

                        else -> false
                    }
                } else {
                    when (event.key) {
                        Key.DirectionLeft,
                        Key.DirectionUp,
                        -> {
                            at = (at + rows.size - 1) % rows.size
                            scope.launch { focusRow(at) }
                            true
                        }

                        Key.DirectionRight,
                        Key.DirectionDown,
                        -> {
                            at = (at + 1) % rows.size
                            scope.launch { focusRow(at) }
                            true
                        }

                        else -> false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val tallest = (maxHeight - 32.dp).coerceAtLeast(240.dp)

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        ) {
            val bar = MaterialTheme.colorScheme.primary
            Column(
                // A card that keeps the glass's height less a margin for itself,
                // so a row added next year scrolls rather than sitting off the
                // bottom. The bar on its edge is what says there are more rows.
                modifier = Modifier
                    .width(640.dp)
                    .heightIn(max = tallest)
                    .onSizeChanged { viewport = it.height }
                    // Drawn after the content, so the bar holds still while the
                    // rows scroll under it.
                    .drawWithContent {
                        drawContent()
                        cardScrollbar(scrolling.value, scrolling.maxValue, viewport, bar)
                    }
                    .verticalScroll(scrolling)
                    .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                when (picking) {
                    CardPicker.DefaultCamera -> DefaultCameraPicker(
                        cameras = cameras,
                        chosen = opening?.name,
                        requesters = { pickerRows.getOrPut(it) { FocusRequester() } },
                        onCancel = { closePicker(DefaultCameraAt) },
                        onPick = { name ->
                            scope.launch { repository.setDefaultCamera(name) }
                            closePicker(DefaultCameraAt)
                        },
                    )

                    CardPicker.Cameras -> CamerasPicker(
                        cameras = cameras,
                        hidden = hidden,
                        requesters = { pickerRows.getOrPut(it) { FocusRequester() } },
                        onCancel = { closePicker(CamerasAt) },
                        // A press on a switch does not close the list: the card
                        // goes back when Back is pressed, and the page behind it
                        // has already lost the camera.
                        onToggle = { name, off ->
                            scope.launch { repository.setCameraHidden(name, off) }
                        },
                    )

                    CardPicker.Screen -> ScreenPicker(
                        screen = screen,
                        requesters = { pickerRows.getOrPut(it) { FocusRequester() } },
                        onCancel = { closePicker(ScreenAt) },
                        onPick = { value ->
                            scope.launch { repository.setScreen(value) }
                            closePicker(ScreenAt)
                        },
                    )

                    CardPicker.Doorbell -> DoorbellAddress(
                        url = ringUrl,
                        onCancel = { closePicker(DoorbellAt) },
                    )

                    CardPicker.Rotation -> RotationPicker(
                        seconds = rotation,
                        requesters = { pickerRows.getOrPut(it) { FocusRequester() } },
                        onCancel = { closePicker(RotationAt) },
                        onPick = { value ->
                            scope.launch { repository.setRotation(value) }
                            closePicker(RotationAt)
                        },
                    )

                    null -> {
                        Text(
                            text = stringResource(R.string.tv_settings).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                R.string.tv_signed_in_note,
                                session?.baseUrl?.shortName().orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // An old server has no reviews, so the alerts page has
                        // nothing behind its button and the pill is gone. This is
                        // the line that says why, rather than leaving a gap.
                        if (capabilities.old || !capabilities.reviews) {
                            Text(
                                text = stringResource(R.string.tv_server_limited),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_default_camera),
                            supporting = stringResource(R.string.tv_default_camera_note),
                            // The row says the camera that will open, which on a
                            // television that has never picked one is the first in
                            // Frigate's list.
                            value = opening?.title.orEmpty(),
                            chevron = true,
                            modifier = placed(DefaultCameraAt),
                            onFocused = {
                                at = DefaultCameraAt
                                reveal(DefaultCameraAt)
                            },
                            onClick = {
                                // The list opens on the camera that is ticked.
                                val startAt = cameras
                                    .indexOfFirst { it.name == opening?.name }
                                    .coerceAtLeast(0)
                                openPicker(CardPicker.DefaultCamera, startAt, cameras.size)
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_cameras),
                            supporting = stringResource(R.string.tv_cameras_note),
                            // How much of the server's list this screen shows,
                            // which is the one thing the row can say without
                            // naming a camera.
                            value = stringResource(
                                R.string.tv_cameras_shown,
                                shown.size,
                                cameras.size,
                            ),
                            chevron = true,
                            modifier = placed(CamerasAt),
                            onFocused = {
                                at = CamerasAt
                                reveal(CamerasAt)
                            },
                            onClick = {
                                // The list stays open while switches are pressed:
                                // turning two cameras off is one visit.
                                openPicker(CardPicker.Cameras, 0, cameras.size)
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_screen),
                            supporting = stringResource(R.string.tv_screen_note),
                            value = stringResource(
                                if (screen == ScreenGrid) R.string.tv_screen_grid
                                else R.string.tv_screen_default,
                            ),
                            chevron = true,
                            modifier = placed(ScreenAt),
                            onFocused = {
                                at = ScreenAt
                                reveal(ScreenAt)
                            },
                            onClick = {
                                openPicker(
                                    CardPicker.Screen,
                                    if (screen == ScreenGrid) 1 else 0,
                                    count = 2,
                                )
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_doorbell),
                            supporting = stringResource(R.string.tv_doorbell_note),
                            chevron = true,
                            modifier = placed(DoorbellAt),
                            onFocused = {
                                at = DoorbellAt
                                reveal(DoorbellAt)
                            },
                            onClick = {
                                // One row rather than a list, so the arrows have
                                // somewhere to be while the address is read.
                                openPicker(CardPicker.Doorbell, 0, count = 1)
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_takeover),
                            supporting = stringResource(R.string.tv_takeover_note),
                            modifier = placed(TakeoverAt),
                            onFocused = {
                                at = TakeoverAt
                                reveal(TakeoverAt)
                            },
                            onClick = { scope.launch { repository.setTakeover(!takeoverOn) } },
                            trailing = { Switch(checked = takeoverOn, onCheckedChange = null) },
                        )
                        // One plain line: the card has no room for a sentence under
                        // every row, so the list that opens says what it does.
                        SettingRow(
                            title = stringResource(R.string.tv_rotation),
                            supporting = null,
                            value = if (rotation > 0) {
                                stringResource(R.string.tv_rotation_seconds, rotation)
                            } else {
                                stringResource(R.string.tv_rotation_off)
                            },
                            chevron = true,
                            modifier = placed(RotationAt),
                            onFocused = {
                                at = RotationAt
                                reveal(RotationAt)
                            },
                            onClick = {
                                // The list opens on the length that is set.
                                openPicker(
                                    CardPicker.Rotation,
                                    RotationLengths.indexOf(rotation).coerceAtLeast(0),
                                    RotationLengths.size,
                                )
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_updates),
                            supporting = when (val state = update) {
                                TvUpdate.Looking -> stringResource(R.string.tv_updates_looking)
                                TvUpdate.Newest -> stringResource(
                                    R.string.tv_updates_newest,
                                    updater.current,
                                )

                                TvUpdate.Unreachable -> stringResource(R.string.tv_updates_trouble)
                                is TvUpdate.Waiting -> stringResource(
                                    R.string.tv_updates_download,
                                    state.release.version,
                                )

                                is TvUpdate.Downloading -> stringResource(
                                    R.string.tv_updates_downloading,
                                    state.percent,
                                )

                                is TvUpdate.Ready -> stringResource(
                                    R.string.tv_updates_install,
                                    state.release.version,
                                )
                            },
                            modifier = placed(UpdatesAt),
                            onFocused = {
                                at = UpdatesAt
                                reveal(UpdatesAt)
                            },
                            onClick = {
                                when (val state = update) {
                                    is TvUpdate.Waiting -> scope.launch {
                                        val release = state.release
                                        update = TvUpdate.Downloading(release, 0)
                                        val file = runCatching {
                                            updater.download(release) { percent ->
                                                update = TvUpdate.Downloading(release, percent)
                                            }
                                        }.getOrNull()
                                        update = if (file == null) {
                                            TvUpdate.Unreachable
                                        } else {
                                            TvUpdate.Ready(release, file)
                                        }
                                        // A television that can't install yet says where
                                        // the switch is rather than failing quietly.
                                        if (file != null && !updater.canInstall()) updater.askToInstall()
                                    }

                                    is TvUpdate.Ready -> if (updater.canInstall()) {
                                        runCatching { updater.install(state.file) }
                                    } else {
                                        updater.askToInstall()
                                    }

                                    else -> Unit
                                }
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_pair_again),
                            supporting = stringResource(R.string.tv_pair_again_note),
                            modifier = placed(PairAt),
                            onFocused = {
                                at = PairAt
                                reveal(PairAt)
                            },
                            onClick = {
                                onClose()
                                scope.launch { repository.forget() }
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_close),
                            supporting = null,
                            modifier = placed(CloseAt),
                            onFocused = {
                                at = CloseAt
                                reveal(CloseAt)
                            },
                            onClick = onClose,
                        )
                    }
                }
            }
        }
    }
}

/** The row order on the Settings card, and where the arrows start. */
private const val DefaultCameraAt = 0
private const val CamerasAt = 1
private const val ScreenAt = 2
private const val DoorbellAt = 3
private const val TakeoverAt = 4
private const val RotationAt = 5
private const val UpdatesAt = 6
private const val PairAt = 7
private const val CloseAt = 8
private const val CardRows = 9

/** The lengths the rotation row offers, and Off. */
private val RotationLengths = listOf(0, 3, 5, 10)

/** Something that takes the card's place while it is up. */
private enum class CardPicker { DefaultCamera, Cameras, Screen, Doorbell, Rotation }

/** Where the television's own update stands, which its row says in one line. */
private sealed interface TvUpdate {
    data object Looking : TvUpdate
    data object Newest : TvUpdate
    data object Unreachable : TvUpdate
    data class Waiting(val release: TvRelease) : TvUpdate
    data class Downloading(val release: TvRelease, val percent: Int) : TvUpdate
    data class Ready(val release: TvRelease, val file: File) : TvUpdate
}

/** One big picture, or a wall of every camera. Both hold the arrows while up. */
@Composable
private fun ScreenPicker(
    screen: String,
    requesters: (Int) -> FocusRequester,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    Text(
        text = stringResource(R.string.tv_screen).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.tv_screen_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            ScreenOne to R.string.tv_screen_default,
            ScreenGrid to R.string.tv_screen_grid,
        ).forEachIndexed { index, (value, label) ->
            PickerRow(
                label = stringResource(label),
                ticked = screen == value,
                modifier = Modifier.focusRequester(requesters(index)),
                onClick = { onPick(value) },
            )
        }
    }
}

/** How long each camera keeps the screen, or Off. */
@Composable
private fun RotationPicker(
    seconds: Int,
    requesters: (Int) -> FocusRequester,
    onCancel: () -> Unit,
    onPick: (Int) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    Text(
        text = stringResource(R.string.tv_rotation).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.tv_rotation_what),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RotationLengths.forEachIndexed { index, value ->
            PickerRow(
                label = if (value == 0) {
                    stringResource(R.string.tv_rotation_off)
                } else {
                    stringResource(R.string.tv_rotation_seconds, value)
                },
                ticked = seconds == value,
                modifier = Modifier.focusRequester(requesters(index)),
                onClick = { onPick(value) },
            )
        }
    }
}

/** The address to give Home Assistant, and what posting to it does. */
@Composable
private fun DoorbellAddress(url: String?, onCancel: () -> Unit) {    BackHandler(enabled = true, onBack = onCancel)

    Text(
        text = stringResource(R.string.tv_doorbell).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.tv_doorbell_what),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (url == null) {
        Text(
            text = stringResource(R.string.tv_doorbell_shut),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text(
            text = url,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The camera list behind Default camera, with the one that opens ticked. */
@Composable
private fun DefaultCameraPicker(
    cameras: List<Camera>,
    chosen: String?,
    requesters: (Int) -> FocusRequester,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    Text(
        text = stringResource(R.string.tv_default_camera).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.tv_default_camera_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
        modifier = Modifier.heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(cameras, key = { _, camera -> camera.name }) { index, camera ->
            val opens = if (chosen == null) index == 0 else camera.name == chosen
            PickerRow(
                label = camera.title,
                ticked = opens,
                modifier = Modifier.focusRequester(requesters(index)),
                onClick = { onPick(camera.name) },
            )
        }
    }
}

/**
 * The cameras this television shows, a switch on each. Off takes the camera off
 * every page this television draws: the big picture, the strip and the wall.
 *
 * It is the television's own list, so the phone's hidden cameras do not arrive
 * here and these do not travel back. The server is untouched, and it still
 * records what the camera sees.
 */
@Composable
private fun CamerasPicker(
    cameras: List<Camera>,
    hidden: Set<String>,
    requesters: (Int) -> FocusRequester,
    onCancel: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    val ink = MaterialTheme.colorScheme
    Text(
        text = stringResource(R.string.tv_cameras).uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = ink.primary,
    )
    Text(
        text = stringResource(R.string.tv_cameras_what),
        style = MaterialTheme.typography.bodyMedium,
        color = ink.onSurfaceVariant,
    )
    LazyColumn(
        modifier = Modifier.heightIn(max = 340.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(cameras, key = { _, camera -> camera.name }) { index, camera ->
            val off = camera.name in hidden
            PickerRow(
                label = camera.title,
                ticked = false,
                dimmed = off,
                // What this camera answers, read from Frigate, so the row says
                // which object brings it up rather than repeating one list.
                supporting = if (camera.objects.isEmpty()) {
                    stringResource(R.string.tv_camera_no_alerts)
                } else {
                    stringResource(
                        R.string.tv_camera_follows,
                        camera.objects.joinToString(", "),
                    )
                },
                modifier = Modifier.focusRequester(requesters(index)),
                onClick = { onToggle(camera.name, !off) },
                // The focused row is the primary colour, and a switch in the
                // primary colour on it is a switch you cannot read. Lit, it goes
                // the other way round: a pale track with a blue knob.
                trailing = { lit ->
                    Switch(
                        checked = !off,
                        onCheckedChange = null,
                        colors = if (lit) {
                            SwitchDefaults.colors(
                                checkedThumbColor = ink.primary,
                                checkedTrackColor = ink.onPrimary,
                                uncheckedThumbColor = ink.onPrimary,
                                uncheckedTrackColor = ink.onPrimary.copy(alpha = 0.4f),
                            )
                        } else {
                            SwitchDefaults.colors()
                        },
                    )
                },
            )
        }
    }
}

/**
 * The bar down the card's right edge, for a card with more rows than the glass
 * has room for.
 *
 * A television has no finger to flick and no scrollbar of its own. Without this
 * the last rows of Settings would be a page nobody knows is there, so the track
 * is drawn the whole height of the card and the thumb says how much is below.
 */
private fun DrawScope.cardScrollbar(scrolled: Int, most: Int, viewport: Int, tint: Color) {
    if (most <= 0 || viewport <= 0) return
    val content = viewport + most
    val width = 5.dp.toPx()
    val top = 16.dp.toPx()
    val height = size.height - top * 2
    if (height <= 0f) return
    val x = size.width - width - 7.dp.toPx()
    val corner = CornerRadius(width / 2, width / 2)
    drawRoundRect(
        color = tint.copy(alpha = 0.18f),
        topLeft = Offset(x, top),
        size = Size(width, height),
        cornerRadius = corner,
    )
    val thumb = (height * viewport / content).coerceAtLeast(36.dp.toPx())
    val travel = height - thumb
    val y = top + travel * scrolled / most
    drawRoundRect(
        color = tint.copy(alpha = 0.75f),
        topLeft = Offset(x, y),
        size = Size(width, thumb),
        cornerRadius = corner,
    )
}

/** One row of the Settings card: a name, a sentence, and sometimes a value or a switch. */
@Composable
private fun SettingRow(
    title: String,
    supporting: String?,
    modifier: Modifier = Modifier,
    value: String? = null,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = ink.onSurface,
            )
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = ink.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = ink.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** One camera in a list: ticked when it is the one, or a switch on a camera list. */
@Composable
private fun PickerRow(
    label: String,
    ticked: Boolean,
    modifier: Modifier = Modifier,
    /** A line under the name, for a list that has something to say about each row. */
    supporting: String? = null,
    /** A camera that is turned off reads quieter, so the switch is not the only thing saying it. */
    dimmed: Boolean = false,
    /** What sits at the end of the row, told whether the arrows are on it. */
    trailing: (@Composable (Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ink.primary else ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    focused -> ink.onPrimary
                    dimmed -> ink.onSurfaceVariant
                    else -> ink.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) ink.onPrimary.copy(alpha = 0.8f) else ink.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ticked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) ink.onPrimary else ink.primary,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing(focused)
        }
    }
}

/** `https://frigate.home:5000` as `frigate.home:5000`. */
private fun String.shortName(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')

/**
 * Whether anything this alert saw is one of the objects its camera makes an alert
 * from. Frigate reports a face it knows as `person-verified`, while the camera's
 * own list names what it is: `person`.
 *
 * The list is the server's, per camera, so a camera set to person only takes the
 * screen for a person, and a camera with its alerts off takes it for nothing.
 */
private fun Camera.follows(objects: List<String>): Boolean = objects.any { seen ->
    val name = seen.lowercase()
    this.objects.any { it == name || (name == "person-verified" && it == "person") }
}


/** Frigo on the left, the clock, sound, Alerts and Settings on the right. */
@Composable
private fun Header(
    newAlerts: Boolean,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    onAlerts: (() -> Unit)?,
    onSettings: () -> Unit,
    settingsFocus: FocusRequester,
) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(10_000)
            now = LocalTime.now()
        }
    }
    val clock = remember(now) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(now)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_mark_lens),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Frigo",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = clock,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        SoundPill(on = soundOn, onClick = onToggleSound)
        Spacer(Modifier.width(10.dp))
        if (onAlerts != null) {
            AlertsPill(newAlerts = newAlerts, onClick = onAlerts)
            Spacer(Modifier.width(10.dp))
        }
        SettingsPill(onClick = onSettings, focusRequester = settingsFocus)
    }
}

/** Sound on or off for the live picture, with the word rather than a glyph. */
@Composable
private fun SoundPill(on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) ink.primary else ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(if (on) R.string.tv_sound else R.string.tv_muted),
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> ink.onPrimary
                on -> ink.onSurfaceVariant
                // A television that stays quiet says so, in the colour of a warning.
                else -> ink.error
            },
        )
    }
}

/**
 * The way into the alerts, and a dot when something has come in since the page
 * was last opened. It says nothing about how many: a television in a corner is
 * not a place for a number that keeps changing.
 */
@Composable
private fun AlertsPill(newAlerts: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (focused) ink.primary else ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.alerts_title),
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) ink.onPrimary else ink.onSurfaceVariant,
        )
        if (newAlerts) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .background(Color(0xFFFF5A5A), CircleShape),
            )
        }
    }
}

/** The way back to the pairing code, so a television on the wrong server is a press from mending. */
@Composable
private fun SettingsPill(onClick: () -> Unit, focusRequester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .clip(shape)
            .background(if (focused) ink.primary else ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_settings),
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) ink.onPrimary else ink.onSurfaceVariant,
        )
    }
}

/**
 * The picture while the loop runs: every camera's stream held open and laid out
 * side by side, with one of them in the frame. Moving the row is the change, so
 * the camera leaving is still playing as it goes and the one arriving is already
 * live.
 *
 * A copy of the last camera sits before the first, and a copy of the first after
 * the last, so going round the end is one step like every other change rather
 * than a sweep back across the whole row.
 */
@Composable
private fun RotationBand(
    repository: TvRepository,
    cameras: List<Camera>,
    chosen: String,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = cameras.size
    val band = remember(cameras) {
        listOf(cameras.last()) + cameras + listOf(cameras.first())
    }
    // Where the row sits, counted in pictures: one is the first camera.
    val at = remember {
        Animatable((cameras.indexOfFirst { it.name == chosen } + 1).toFloat().coerceAtLeast(1f))
    }

    LaunchedEffect(chosen, cameras) {
        val target = cameras.indexOfFirst { it.name == chosen } + 1
        if (target <= 0) return@LaunchedEffect
        val showing = Math.round(at.value)
        if (showing == target) {
            at.snapTo(target.toFloat())
            return@LaunchedEffect
        }
        when {
            // Round the end: slide onto the copy, then start again at the first.
            showing == count && target == 1 -> {
                at.animateTo((count + 1).toFloat(), tween(SlideMillis))
                at.snapTo(1f)
            }

            showing == 1 && target == count -> {
                at.animateTo(0f, tween(SlideMillis))
                at.snapTo(count.toFloat())
            }

            else -> at.animateTo(target.toFloat(), tween(SlideMillis))
        }
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() },
    ) {
        val width = maxWidth
        band.forEachIndexed { index, camera ->
            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = (index - at.value) * width.toPx() },
            ) {
                BigPicture(
                    repository = repository,
                    camera = camera,
                    fullScreen = true,
                    muted = true,
                    // The two copies at the ends are only on screen while a slide
                    // passes them, so they show a still and leave the streams to
                    // the cameras that are really in the row.
                    stream = index != 0 && index != count + 1,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The rotation switch, over the picture's top right corner. Blue while the loop
 * runs, white while the picture holds still, so the corner says which state the
 * room is in without a word.
 */
@Composable
private fun RotationSymbol(
    running: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val spoken = stringResource(
        if (running) R.string.tv_rotation_running else R.string.tv_rotation_stopped,
    )
    val tint = if (running) MaterialTheme.colorScheme.primary else Color.White
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(
                width = 3.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(12.dp)
            .semantics { contentDescription = spoken },
    ) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = 3.5.dp.toPx()
            val inset = stroke + 2.dp.toPx()
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            // A gap at the top right, and a head on the end of the arc, so the
            // mark reads as something going round rather than as a circle.
            drawArc(
                color = tint,
                startAngle = 40f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val radius = box.minDimension / 2
            val centre = Offset(inset + radius, inset + radius)
            val angle = Math.toRadians(40.0)
            val tip = Offset(
                centre.x + (radius * cos(angle)).toFloat(),
                centre.y + (radius * sin(angle)).toFloat(),
            )
            val head = 6.dp.toPx()
            val point = Path().apply {
                moveTo(tip.x, tip.y - head)
                lineTo(tip.x + head, tip.y + head * 0.4f)
                lineTo(tip.x - head * 0.9f, tip.y + head * 0.5f)
                close()
            }
            drawPath(point, tint)
        }
    }
}

/**
 * The big one. The camera's own stream plays over a still of the same camera,
 * which is what is on the screen until the first frame arrives. A camera whose
 * own stream this television cannot decode falls back to Frigate's feed, which
 * is always H.264's older cousin: slower, but a picture rather than a still.
 *
 * A press on it fills the screen with the picture, and the same press or Back
 * brings the strip and the name back. The stream is not reopened for it: the
 * same player is moved and stretched.
 */
@Composable
private fun BigPicture(
    repository: TvRepository,
    camera: Camera,
    fullScreen: Boolean,
    muted: Boolean,
    focusRequester: FocusRequester? = null,
    onToggle: (() -> Unit)? = null,
    /**
     * Whether this picture plays the camera's stream. The copies either end of
     * the band are only on screen while a slide goes past them, so they show a
     * still and leave the streams to the real slots.
     */
    stream: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val session by repository.session.collectAsStateWithLifecycle()
    var playing by remember(camera.name) { mutableStateOf(false) }
    var feed by remember(camera.name) { mutableStateOf(false) }
    var feedLive by remember(camera.name) { mutableStateOf(false) }
    var still by remember(camera.name) { mutableStateOf<ImageBitmap?>(null) }
    var focused by remember { mutableStateOf(false) }
    val live = playing || feedLive
    val shape = RoundedCornerShape(20.dp)

    LaunchedEffect(camera.name, live, session?.token) {
        while (!live && isActive) {
            repository.still(camera.name, height = 720)?.let { still = it.asImageBitmap() }
            delay(1_000)
        }
    }

    Box(
        modifier
            .then(if (fullScreen) Modifier else Modifier.clip(shape))
            .background(Color.Black)
            .then(
                if (!fullScreen && focused) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (onToggle != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggle() }
                } else {
                    Modifier
                },
            ),
    ) {
        val picture = still
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val current = session
        val url = current?.let { repository.streamUrl(camera.name) }
        if (stream && current != null && url != null && !feed) {
            CameraStream(
                url = url,
                token = current.token,
                muted = muted,
                onPlaying = { playing = it },
                onFailed = { feed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (stream && feed) {
            BigFeed(
                repository = repository,
                camera = camera.name,
                onFrame = { feedLive = it },
            )
        }

        LivePill(
            playing = live,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(if (fullScreen) 24.dp else 16.dp),
        )

        if (fullScreen) {
            // The name comes with the picture when the strip is gone, so the
            // screen still says which camera this is.
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
            )
            Text(
                text = camera.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 28.dp, vertical = 22.dp),
            )
        } else {
            ExpandMark(
                active = focused,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp),
            )
        }
    }
}

/** Four corners opening outwards: the press that fills the screen with the picture. */
@Composable
private fun ExpandMark(active: Boolean, modifier: Modifier = Modifier) {
    val ink = if (active) Color.White else Color.White.copy(alpha = 0.6f)
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.Black.copy(alpha = if (active) 0.55f else 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(17.dp)) {
            val arm = size.minDimension * 0.4f
            val stroke = 2.dp.toPx()
            listOf(
                Triple(Offset(0f, arm), Offset(0f, 0f), Offset(arm, 0f)),
                Triple(Offset(size.width - arm, 0f), Offset(size.width, 0f), Offset(size.width, arm)),
                Triple(Offset(0f, size.height - arm), Offset(0f, size.height), Offset(arm, size.height)),
                Triple(
                    Offset(size.width - arm, size.height),
                    Offset(size.width, size.height),
                    Offset(size.width, size.height - arm),
                ),
            ).forEach { (start, corner, end) ->
                drawLine(ink, start, corner, stroke, StrokeCap.Round)
                drawLine(ink, corner, end, stroke, StrokeCap.Round)
            }
        }
    }
}

/**
 * The camera at five frames a second, for one whose own video this television
 * cannot decode. Still a moving picture, and the only thing that works on a box
 * with no HEVC.
 */
@Composable
private fun BigFeed(repository: TvRepository, camera: String, onFrame: (Boolean) -> Unit) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sayFrame by rememberUpdatedState(onFrame)

    LaunchedEffect(camera) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                var arrived = false
                try {
                    repository.pictures(camera, 720, 5).collect { picture ->
                        frame = picture.asImageBitmap()
                        if (!arrived) {
                            arrived = true
                            sayFrame(true)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    // Tried again below.
                }
                sayFrame(false)
                delay(3_000)
            }
        }
    }

    val picture = frame
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LivePill(playing: Boolean, modifier: Modifier = Modifier) {
    val breathe = rememberInfiniteTransition(label = "live")
    val alpha by breathe.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "dot",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (playing) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Color(0xFFFF5A5A).copy(alpha = alpha), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.tv_live),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        } else {
            Text(
                text = stringResource(R.string.tv_connecting),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The camera's own stream, with the sign-in on every request. A stream that
 * never starts, or stops without saying so, is opened again rather than left as
 * a still that would pass for a live picture.
 */
@Composable
private fun CameraStream(
    url: String,
    token: String,
    muted: Boolean,
    onPlaying: (Boolean) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var generation by remember(url, token) { mutableIntStateOf(0) }
    // Counted across reopenings, because a stream this television cannot decode
    // fails the same way each time and should end in the feed, not a loop.
    var troubles by remember(url, token) { mutableIntStateOf(0) }
    // Whether the picture is up. Asked of the player rather than taken from a
    // callback: a callback can be missed, and a view kept at zero alpha is a
    // picture nobody sees.
    var visible by remember(url, token, generation) { mutableStateOf(false) }
    val sayPlaying by rememberUpdatedState(onPlaying)
    val sayFailed by rememberUpdatedState(onFailed)

    val player = remember(url, token, generation) { buildPlayer(context, url, token) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                troubles++
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A stream has no end. One that ends has stopped carrying the
                // camera, and reopening it is the way back.
                if (state == Player.STATE_ENDED) troubles++
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // The picture is up when the player is playing and knows the picture's
    // shape. Asked four times a second, so the view comes out whenever it is
    // true, whether or not a first-frame callback was heard.
    LaunchedEffect(player) {
        var was = false
        while (isActive) {
            delay(400)
            val playingNow = player.playbackState == Player.STATE_READY &&
                player.isPlaying &&
                player.videoSize.height > 0
            // Once the picture is up it stays up: a stream that rebuffers for a
            // moment should not fall back to a still and back again.
            val live = playingNow || was
            if (live != was) {
                was = live
                visible = live
                sayPlaying(live)
            }
        }
    }

    // Watching is no reason to keep decoding once the television is showing
    // something else, and coming back is the other half of it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> player.pause()
                androidx.lifecycle.Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    // The sound pill changes the picture's sound without opening the stream again.
    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
    }

    // A stream on its way is not a stream that has failed: it gets this long to
    // put up a picture before it is opened again.
    LaunchedEffect(player) {
        delay(15_000)
        if (!visible) troubles++
    }

    LaunchedEffect(troubles) {
        if (troubles >= 2) {
            // Twice is enough to know this television cannot play this camera.
            sayPlaying(false)
            sayFailed()
            return@LaunchedEffect
        }
        if (troubles > 0) {
            sayPlaying(false)
            delay(3_000)
            generation++
        }
    }

    // A live stream never ends and never says it has fallen behind. Waiting for
    // more with the same position twice is a picture that has stopped.
    LaunchedEffect(player, visible) {
        if (!visible) return@LaunchedEffect
        var last = player.currentPosition
        while (isActive) {
            delay(10_000)
            val position = player.currentPosition
            if (player.playbackState == Player.STATE_BUFFERING && position == last) {
                generation++
                break
            }
            last = position
        }
    }

    AndroidView(
        factory = { viewContext ->
            (LayoutInflater.from(viewContext).inflate(R.layout.live_video, null) as PlayerView)
                .apply {
                    this.player = player
                    alpha = if (visible) 1f else 0f
                }
        },
        update = { view ->
            view.player = player
            val wanted = if (visible) 1f else 0f
            if (view.alpha != wanted) view.alpha = wanted
        },
        modifier = modifier,
    )
}

private fun buildPlayer(context: android.content.Context, url: String, token: String): ExoPlayer {
    val source = DefaultHttpDataSource.Factory()
        // A server with no sign-in is sent nothing: a token it never issued is
        // turned down, and the picture would never arrive.
        .setDefaultRequestProperties(
            if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"),
        )
        .setConnectTimeoutMs(6_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    val media = ProgressiveMediaSource.Factory(source)
        .createMediaSource(MediaItem.fromUri(url))

    return ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LiveBufferMillis,
                    LiveBufferMillis * 2,
                    LiveStartMillis,
                    LiveStartMillis,
                )
                .build(),
        )
        .build()
        .apply {
            // The television is the best speaker in the house. The stream
            // carries the camera's sound, and the remote's volume is the one
            // that decides how loud it is.
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            volume = 1f
            setMediaSource(media)
            prepare()
            playWhenReady = true
        }
}

/**
 * Every camera at once, in as square a wall as the count allows: four cameras are
 * a 2x2, six are two rows of three. The header stays where it always is, and a
 * press on a tile opens that camera with the strip beside it. Back brings the
 * wall back.
 */
@Composable
private fun CameraWall(
    repository: TvRepository,
    cameras: List<Camera>,
    newAlerts: Boolean,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    onAlerts: (() -> Unit)?,
    onSettings: () -> Unit,
    settingsFocus: FocusRequester,
    requesters: MutableMap<String, FocusRequester>,
    onFocused: (String) -> Unit,
    onOpen: (Camera) -> Unit,
) {
    val columns = wallColumns(cameras.size)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 36.dp),
    ) {
        Header(
            newAlerts = newAlerts,
            soundOn = soundOn,
            onToggleSound = onToggleSound,
            onAlerts = onAlerts,
            onSettings = onSettings,
            settingsFocus = settingsFocus,
        )
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cameras.chunked(columns).forEachIndexed { rowAt, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { camera ->
                        val requester = requesters.getOrPut(camera.name) { FocusRequester() }
                        CameraTile(
                            repository = repository,
                            camera = camera,
                            wall = true,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .focusRequester(requester)
                                .focusProperties {
                                    // Up from the top row is the header, the way it
                                    // is from the first camera in the strip.
                                    if (rowAt == 0) up = settingsFocus
                                },
                            onFocused = { onFocused(camera.name) },
                            onClick = { onOpen(camera) },
                        )
                    }
                    // A short last row keeps its tiles the width of the ones above.
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * The fewest columns that keep the wall near square, which is what a wall is for.
 * A house of three or four cameras is a 2x2, of six is two rows of three, and one
 * camera on its own fills the screen.
 */
private fun wallColumns(count: Int): Int {
    var columns = 1
    while (columns * columns < count) columns++
    return columns
}

/**
 * One camera in the strip: a live 360p feed, its name over the picture, and a
 * ring while the arrows are on it. On the wall the same tile fills its cell
 * instead of keeping the shape of the picture.
 */
@Composable
private fun CameraTile(
    repository: TvRepository,
    camera: Camera,
    modifier: Modifier = Modifier,
    wall: Boolean = false,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(if (wall) 18.dp else 14.dp)
    val ink = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .then(if (wall) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            .clip(shape)
            .background(ink.surfaceContainer)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) ink.primary else ink.outline.copy(alpha = 0.45f),
                shape = shape,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable { onClick() }
            .semantics { contentDescription = camera.title },
    ) {
        TileFeed(
            repository = repository,
            camera = camera.name,
            onOnline = { online = it },
        )

        // A scrim, so a white wall does not swallow the camera's name.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (wall) 11.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = camera.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!online) {
                Text(
                    text = stringResource(R.string.tv_offline),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFC9B27A),
                )
            }
        }
    }
}

/** The strip's picture, kept open while the strip is on the screen. */
@Composable
private fun TileFeed(repository: TvRepository, camera: String, onOnline: (Boolean) -> Unit) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sayOnline by rememberUpdatedState(onOnline)

    LaunchedEffect(camera) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                var arrived = false
                try {
                    repository.pictures(camera, TileHeight, TileFps).collect { picture ->
                        frame = picture.asImageBitmap()
                        if (!arrived) {
                            arrived = true
                            sayOnline(true)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Throwable) {
                    // A camera that has stopped is said so below, and tried again.
                }
                sayOnline(false)
                delay(4_000)
            }
        }
    }

    val picture = frame
    if (picture != null) {
        Image(
            bitmap = picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
