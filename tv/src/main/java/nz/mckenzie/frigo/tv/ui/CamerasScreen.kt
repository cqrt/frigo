package nz.mckenzie.frigo.tv.ui

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

/**
 * The screen the television exists for: one camera big, the rest down the right.
 *
 * The chosen camera is the one thing on the screen that is not a tile. The strip
 * is the sorted list with that camera left out, so a camera moved into the big
 * picture goes back into the strip in the place its name gives it, not at the end.
 */
@Composable
fun CamerasScreen(repository: TvRepository, cameras: List<Camera>) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val settingsFocus = remember { FocusRequester() }
    val current = cameras.firstOrNull { it.name == chosen } ?: cameras.firstOrNull()

    LaunchedEffect(cameras) {
        if (chosen == null || cameras.none { it.name == chosen }) {
            chosen = cameras.firstOrNull()?.name
        }
    }

    // The television is paired once, so the way back to the code has to be a
    // press away rather than a reinstall. Back closes it, as it closes anything
    // laid on top of the screen.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    if (current == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 36.dp),
        ) {
            Header(onSettings = { settingsOpen = true }, settingsFocus = settingsFocus)
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
            SettingsPanel(repository, onClose = { settingsOpen = false })
        }
        return
    }

    val others = cameras.filter { it.name != current.name }
    var focusAt by remember { mutableIntStateOf(0) }
    // The place a press asked for, kept apart from where the focus happened to
    // be when the picture changed: a tile taken out of the list hands its focus
    // to the first one before anything can be asked for, and that is not where
    // the viewer was.
    var landing by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()

    // The strip takes the focus when the screen opens, and keeps its place after
    // one camera replaces another: the press that moved a picture left the
    // arrows where they were.
    LaunchedEffect(current.name) {
        val wanted = landing ?: focusAt
        landing = null
        val target = others.getOrNull(wanted) ?: others.firstOrNull() ?: return@LaunchedEffect
        val at = others.indexOf(target)
        if (at >= 0) runCatching { listState.animateScrollToItem(at) }
        delay(80)
        runCatching { requesters[target.name]?.requestFocus() }
        focusAt = at
    }

    // Closing Settings puts the arrows back where they were, rather than leaving
    // nothing focused for the next press to fall into.
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) return@LaunchedEffect
        delay(80)
        runCatching {
            val target = others.getOrNull(focusAt) ?: others.firstOrNull()
            target?.let { requesters[it.name]?.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Header(onSettings = { settingsOpen = true }, settingsFocus = settingsFocus)
                Spacer(Modifier.height(16.dp))
                BigPicture(repository, current)
                Spacer(Modifier.height(18.dp))
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

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
                            .then(
                                if (index == 0) {
                                    // Up from the first camera reaches the button
                                    // that leads back to the pairing code.
                                    Modifier.focusProperties { up = settingsFocus }
                                } else {
                                    Modifier
                                },
                            ),
                        onFocused = { focusAt = index },
                        onClick = {
                            landing = index
                            chosen = camera.name
                        },
                    )
                }
            }
        }

        if (settingsOpen) {
            SettingsPanel(repository, onClose = { settingsOpen = false })
        }
    }
}

/** The server the television is signed in to, and the way out of it. */
@Composable
private fun SettingsPanel(repository: TvRepository, onClose: () -> Unit) {
    val session by repository.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { closeFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.width(560.dp).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = session?.baseUrl?.shortName().orEmpty(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.tv_signed_in_note,
                        session?.baseUrl?.shortName().orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelAction(
                        label = stringResource(R.string.tv_pair_again),
                        filled = true,
                        onClick = {
                            onClose()
                            scope.launch { repository.forget() }
                        },
                    )
                    PanelAction(
                        label = stringResource(R.string.tv_close),
                        filled = false,
                        modifier = Modifier.focusRequester(closeFocus),
                        onClick = onClose,
                    )
                }
            }
        }
    }
}

/** One choice in the Settings panel, wearing a ring while the arrows are on it. */
@Composable
private fun PanelAction(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) ink.primary else ink.surfaceContainerHighest)
            .border(
                width = 2.dp,
                color = if (focused) {
                    if (filled) ink.onPrimary else ink.primary
                } else {
                    Color.Transparent
                },
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (filled) ink.onPrimary else ink.onSurface,
        )
    }
}

/** `https://frigate.home:5000` as `frigate.home:5000`. */
private fun String.shortName(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')


/** Frigo on the left, the clock and Settings on the right, over the picture. */
@Composable
private fun Header(onSettings: () -> Unit, settingsFocus: FocusRequester) {
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
        SettingsPill(onClick = onSettings, focusRequester = settingsFocus)
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
 */
@Composable
private fun BigPicture(repository: TvRepository, camera: Camera) {
    val session by repository.session.collectAsStateWithLifecycle()
    var playing by remember(camera.name) { mutableStateOf(false) }
    var feed by remember(camera.name) { mutableStateOf(false) }
    var feedLive by remember(camera.name) { mutableStateOf(false) }
    var still by remember(camera.name) { mutableStateOf<ImageBitmap?>(null) }
    val live = playing || feedLive


    LaunchedEffect(camera.name, live, session?.token) {
        while (!live && isActive) {
            repository.still(camera.name, height = 720)?.let { still = it.asImageBitmap() }
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black),
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

        LivePill(playing = live, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
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
            // A television is watched, not listened to. The sound is on the phone.
            volume = 0f
            setMediaSource(media)
            prepare()
            playWhenReady = true
        }
}

/**
 * One camera in the strip: a live 360p feed, its name over the picture, and a
 * ring while the arrows are on it.
 */
@Composable
private fun CameraTile(
    repository: TvRepository,
    camera: Camera,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(14.dp)
    val ink = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
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
                .padding(horizontal = 12.dp, vertical = 9.dp),
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
