package nz.mckenzie.frigo.tv.ui

import android.view.LayoutInflater
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
    val current = cameras.firstOrNull { it.name == chosen } ?: cameras.firstOrNull()

    LaunchedEffect(cameras) {
        if (chosen == null || cameras.none { it.name == chosen }) {
            chosen = cameras.firstOrNull()?.name
        }
    }

    if (current == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 160.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tv_no_cameras),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Header()
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
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.tv_choose_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    modifier = Modifier.focusRequester(requester),
                    onFocused = { focusAt = index },
                    onClick = {
                        landing = index
                        chosen = camera.name
                    },
                )
            }
        }
    }
}

/** Frigo on the left, the clock on the right, over the picture they belong to. */
@Composable
private fun Header() {
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
    }
}

/**
 * The big one. The camera's own H.264 plays over a still of the same camera,
 * which is what is on the screen until the first frame arrives.
 */
@Composable
private fun BigPicture(repository: TvRepository, camera: Camera) {
    val session by repository.session.collectAsStateWithLifecycle()
    var playing by remember(camera.name) { mutableStateOf(false) }
    var still by remember(camera.name) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(camera.name, playing, session?.token) {
        while (!playing && isActive) {
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
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val current = session
        val url = current?.let { repository.streamUrl(camera.name) }
        if (current != null && url != null) {
            CameraStream(
                url = url,
                token = current.token,
                playing = playing,
                onPlaying = { playing = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        LivePill(playing = playing, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
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
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var generation by remember(url, token) { mutableIntStateOf(0) }
    var started by remember(generation) { mutableStateOf(false) }
    var failures by remember(generation) { mutableIntStateOf(0) }
    val sayPlaying by rememberUpdatedState(onPlaying)

    val player = remember(url, token, generation) { buildPlayer(context, url, token) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started = true
            }

            override fun onPlayerError(error: PlaybackException) {
                failures++
            }

            override fun onPlaybackStateChanged(state: Int) {
                // A stream has no end. One that ends has stopped carrying the
                // camera, and reopening it is the way back.
                if (state == Player.STATE_ENDED) failures++
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(started) { sayPlaying(started) }

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
    // put up a frame before it is opened again.
    LaunchedEffect(player) {
        delay(15_000)
        if (!started) generation++
    }

    LaunchedEffect(failures) {
        if (failures > 0) {
            sayPlaying(false)
            delay(3_000)
            generation++
        }
    }

    // A live stream never ends and never says it has fallen behind. Waiting for
    // more with the same position twice is a picture that has stopped.
    LaunchedEffect(player, started) {
        if (!started) return@LaunchedEffect
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
                    alpha = if (playing) 1f else 0f
                }
        },
        update = { view ->
            view.player = player
            val wanted = if (playing) 1f else 0f
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
