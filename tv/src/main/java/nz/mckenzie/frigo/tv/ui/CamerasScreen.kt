package nz.mckenzie.frigo.tv.ui

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import nz.mckenzie.frigo.tv.data.ScreenGrid
import nz.mckenzie.frigo.tv.data.ScreenOne
import nz.mckenzie.frigo.tv.data.TvRepository
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val settingsFocus = remember { FocusRequester() }
    val pictureFocus = remember { FocusRequester() }
    val current = cameras.firstOrNull { it.name == chosen } ?: cameras.firstOrNull()

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
    val takeoverOn by repository.takeover.collectAsStateWithLifecycle(initialValue = true)
    val soundOn by repository.liveSound.collectAsStateWithLifecycle(initialValue = true)
    val defaultCamera by repository.defaultCamera.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

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

    LaunchedEffect(cameras, defaultCamera) {
        val wanted = chosen
        if (wanted == null || cameras.none { it.name == wanted }) {
            // The television opens on the camera Settings names, or on the first.
            chosen = cameras.firstOrNull { it.name == defaultCamera }?.name
                ?: cameras.firstOrNull()?.name
        }
    }

    // The television is paired once, so the way back to the code has to be a
    // press away rather than a reinstall. Back closes what is on top of the
    // screen: the Settings card, the camera opened over the wall, or the picture
    // filling it.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = screen == ScreenGrid && wallOpen && !fullScreen) {
        // Back to the wall, with the arrows on the camera that was open.
        wallAt = current?.name
        wallOpen = false
    }
    BackHandler(enabled = fullScreen) { fullScreen = false }

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
                onAlerts = onOpenAlerts,
                onSettings = { settingsOpen = true },
                settingsFocus = settingsFocus,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.tv_no_cameras),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
        if (settingsOpen) {
            SettingsPanel(repository, cameras, onClose = { settingsOpen = false })
        }
        return
    }

    val others = cameras.filter { it.name != current.name }
    // Whether the one-camera layout is the one up: always unless the wall is on
    // and nothing has been opened from it.
    val stripShown = screen != ScreenGrid || wallOpen
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
        if (!stripShown) return@LaunchedEffect
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
    LaunchedEffect(stripShown, cameras.size) {
        if (stripShown) return@LaunchedEffect
        delay(60)
        val wanted = wallAt?.takeIf { name -> cameras.any { it.name == name } } ?: current.name
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
                seesPersonOrCar(alert.objects) &&
                    cameras.any { it.name == alert.cameraName } &&
                    seen.add(alert.id)
            }
            if (hit != null && !fullScreen && !settingsOpen && stripShown) {
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
        takeoverFrom = null
        takeoverUntil = 0L
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
            val wanted = wallAt?.takeIf { name -> cameras.any { it.name == name } } ?: current.name
            focusTile(wallRequesters, wanted)
        }
    }

    // The picture takes the focus when it fills the screen, and gives it back to
    // the strip when the strip comes back.
    LaunchedEffect(fullScreen) {
        delay(80)
        if (fullScreen) {
            runCatching { pictureFocus.requestFocus() }
        } else if (stripShown) {
            val target = others.getOrNull(focusAt) ?: others.firstOrNull()
            target?.let { focusCamera(it.name) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!stripShown) {
            CameraWall(
                repository = repository,
                cameras = cameras,
                newAlerts = newAlerts,
                soundOn = soundOn,
                onToggleSound = { scope.launch { repository.setLiveSound(!soundOn) } },
                onAlerts = onOpenAlerts,
                onSettings = { settingsOpen = true },
                settingsFocus = settingsFocus,
                requesters = wallRequesters,
                onFocused = { wallAt = it },
                onOpen = { camera ->
                    // The press asks for one camera, which is the layout with the
                    // picture and the strip. Back brings the wall back.
                    chosen = camera.name
                    wallAt = camera.name
                    landing = 0
                    wallOpen = true
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
                            onAlerts = onOpenAlerts,
                            onSettings = { settingsOpen = true },
                            settingsFocus = settingsFocus,
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    BigPicture(
                        repository = repository,
                        camera = current,
                        fullScreen = fullScreen,
                        muted = !soundOn,
                        focusRequester = pictureFocus,
                        onToggle = {
                            // Filling the screen is a choice; it ends a takeover.
                            if (!fullScreen) {
                                takeoverFrom = null
                                takeoverUntil = 0L
                            }
                            fullScreen = !fullScreen
                        },
                        modifier = if (fullScreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        },
                    )
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

        if (settingsOpen) {
            SettingsPanel(repository, cameras, onClose = { settingsOpen = false })
        }
    }
}

/** Settings: the server, the camera it opens on, and what a newcomer does. */
@Composable
private fun SettingsPanel(
    repository: TvRepository,
    cameras: List<Camera>,
    onClose: () -> Unit,
) {
    val session by repository.session.collectAsStateWithLifecycle()
    val takeoverOn by repository.takeover.collectAsStateWithLifecycle(initialValue = true)
    val defaultCamera by repository.defaultCamera.collectAsStateWithLifecycle()
    val screen by repository.screen.collectAsStateWithLifecycle(initialValue = ScreenOne)
    val scope = rememberCoroutineScope()

    // The camera the big picture opens on: the one that was picked, or the first
    // in Frigate's list on a television that has never picked one.
    val opening = cameras.firstOrNull { it.name == defaultCamera } ?: cameras.firstOrNull()

    // Which short list is up, and nothing when the rows are.
    var picking by remember { mutableStateOf<CardPicker?>(null) }
    // The card is the whole screen while it is up, so the arrows move between
    // its rows and nowhere else. They come back round, which on five rows is
    // kinder than stopping at an edge.
    var at by remember { mutableIntStateOf(CardRows - 1) }
    val rows = remember { List(CardRows) { FocusRequester() } }
    val firstChoice = remember { FocusRequester() }

    // The list goes when a choice is made, so the arrows come back to the row
    // that opened it rather than to nothing.
    fun closePicker(returnTo: Int) {
        picking = null
        at = returnTo
        scope.launch {
            delay(80)
            runCatching { rows[returnTo].requestFocus() }
        }
    }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { rows[CardRows - 1].requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .onPreviewKeyEvent { event ->
                // While a short list is up, its own rows have the arrows.
                if (picking != null || event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft,
                    Key.DirectionUp,
                    -> {
                        at = (at + rows.size - 1) % rows.size
                        runCatching { rows[at].requestFocus() }
                        true
                    }

                    Key.DirectionRight,
                    Key.DirectionDown,
                    -> {
                        at = (at + 1) % rows.size
                        runCatching { rows[at].requestFocus() }
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.width(640.dp).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (picking) {
                    CardPicker.DefaultCamera -> DefaultCameraPicker(
                        cameras = cameras,
                        chosen = opening?.name,
                        firstFocus = firstChoice,
                        onCancel = { closePicker(DefaultCameraAt) },
                        onPick = { name ->
                            scope.launch { repository.setDefaultCamera(name) }
                            closePicker(DefaultCameraAt)
                        },
                    )

                    CardPicker.Screen -> ScreenPicker(
                        screen = screen,
                        firstFocus = firstChoice,
                        onCancel = { closePicker(ScreenAt) },
                        onPick = { value ->
                            scope.launch { repository.setScreen(value) }
                            closePicker(ScreenAt)
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
                            modifier = Modifier.focusRequester(rows[DefaultCameraAt]),
                            onFocused = { at = DefaultCameraAt },
                            onClick = { picking = CardPicker.DefaultCamera },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_screen),
                            supporting = stringResource(R.string.tv_screen_note),
                            value = stringResource(
                                if (screen == ScreenGrid) R.string.tv_screen_grid
                                else R.string.tv_screen_one,
                            ),
                            chevron = true,
                            modifier = Modifier.focusRequester(rows[ScreenAt]),
                            onFocused = { at = ScreenAt },
                            onClick = { picking = CardPicker.Screen },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_takeover),
                            supporting = stringResource(R.string.tv_takeover_note),
                            modifier = Modifier.focusRequester(rows[TakeoverAt]),
                            onFocused = { at = TakeoverAt },
                            onClick = { scope.launch { repository.setTakeover(!takeoverOn) } },
                            trailing = { Switch(checked = takeoverOn, onCheckedChange = null) },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_pair_again),
                            supporting = stringResource(R.string.tv_pair_again_note),
                            modifier = Modifier.focusRequester(rows[PairAt]),
                            onFocused = { at = PairAt },
                            onClick = {
                                onClose()
                                scope.launch { repository.forget() }
                            },
                        )
                        SettingRow(
                            title = stringResource(R.string.tv_close),
                            supporting = null,
                            modifier = Modifier.focusRequester(rows[CloseAt]),
                            onFocused = { at = CloseAt },
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
private const val ScreenAt = 1
private const val TakeoverAt = 2
private const val PairAt = 3
private const val CloseAt = 4
private const val CardRows = 5

/** A short list that takes the card's place while it is up. */
private enum class CardPicker { DefaultCamera, Screen }

/** One camera at a time with the rest beside it, or all of them on a wall. */
@Composable
private fun ScreenPicker(
    screen: String,
    firstFocus: FocusRequester,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

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
            ScreenOne to R.string.tv_screen_one,
            ScreenGrid to R.string.tv_screen_grid,
        ).forEachIndexed { index, (value, label) ->
            PickerRow(
                label = stringResource(label),
                ticked = screen == value,
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = { onPick(value) },
            )
        }
    }
}

/** The camera list behind Default camera, with the one that opens ticked. */
@Composable
private fun DefaultCameraPicker(
    cameras: List<Camera>,
    chosen: String?,
    firstFocus: FocusRequester,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    BackHandler(enabled = true, onBack = onCancel)

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

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
                modifier = if (opens) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = { onPick(camera.name) },
            )
        }
    }
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
            .padding(horizontal = 18.dp, vertical = 10.dp),
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

/** One camera in the Default camera list, ticked when it is the one. */
@Composable
private fun PickerRow(
    label: String,
    ticked: Boolean,
    modifier: Modifier = Modifier,
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
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) ink.onPrimary else ink.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (ticked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) ink.onPrimary else ink.primary,
            )
        }
    }
}

/** `https://frigate.home:5000` as `frigate.home:5000`. */
private fun String.shortName(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')

/** Frigate names a face-recognised person `person-verified`; both count. */
private fun seesPersonOrCar(objects: List<String>): Boolean = objects.any {
    when (it.lowercase()) {
        "person", "person-verified", "car" -> true
        else -> false
    }
}


/** Frigo on the left, the clock, sound, Alerts and Settings on the right. */
@Composable
private fun Header(
    newAlerts: Boolean,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    onAlerts: () -> Unit,
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
        Text(
            text = "FRIGO",
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
        AlertsPill(newAlerts = newAlerts, onClick = onAlerts)
        Spacer(Modifier.width(10.dp))
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
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
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
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() },
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
        if (current != null && url != null && !feed) {
            CameraStream(
                url = url,
                token = current.token,
                muted = muted,
                onPlaying = { playing = it },
                onFailed = { feed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (feed) {
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
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
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
    onAlerts: () -> Unit,
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
                .padding(horizontal = if (wall) 18.dp else 12.dp, vertical = if (wall) 14.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = camera.title,
                style = if (wall) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
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
