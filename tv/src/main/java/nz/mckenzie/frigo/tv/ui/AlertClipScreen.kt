package nz.mckenzie.frigo.tv.ui

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.focusable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nz.mckenzie.frigo.tv.R
import nz.mckenzie.frigo.tv.data.Alert
import nz.mckenzie.frigo.tv.data.TvRepository
import java.io.File

/** The ten seconds a press of an arrow moves the picture. */
private const val SeekMillis = 10_000L

/**
 * One alert, full screen, with sound.
 *
 * The clip is fetched whole before it plays: Frigate cuts it out of the
 * recordings as it sends it, and a player given that directly can sit waiting
 * long enough to give up. Fetching first costs the wait once and then lets the
 * arrows move about in it. Watching it says so on the server, so it leaves the
 * list here and in Frigo on the phone.
 */
@Composable
fun AlertClipScreen(
    repository: TvRepository,
    alert: Alert,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    var file by remember(alert.id) { mutableStateOf<File?>(null) }
    var progress by remember(alert.id) { mutableStateOf<Int?>(null) }
    var failed by remember(alert.id) { mutableStateOf(false) }
    var picture by remember(alert.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(alert.id) {
        picture = repository.alertThumbnail(alert)?.asImageBitmap()
        val clip = repository.clipFile(alert) { progress = it }
        if (clip == null) {
            failed = true
            return@LaunchedEffect
        }
        file = clip
        // Opened is watched, which is what clears it from the list.
        repository.markAlertWatched(alert)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val clip = file
        when {
            clip != null -> ClipPlayer(clip, alert)
            failed -> Waiting(picture = picture, alert = alert, failed = true)
            else -> Waiting(picture = picture, alert = alert, failed = false, progress = progress)
        }
    }
}

/** The wait while the clip arrives, over the alert's own picture. */
@Composable
private fun Waiting(
    picture: ImageBitmap?,
    alert: Alert,
    failed: Boolean,
    progress: Int? = null,
) {
    Box(Modifier.fillMaxSize()) {
        picture?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (failed) {
                    stringResource(R.string.clip_failed)
                } else {
                    stringResource(R.string.clip_fetching) +
                        (progress?.let { "  $it%" } ?: "")
                },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        AlertLine(alert, Modifier.align(Alignment.BottomStart).padding(32.dp))
    }
}

/** The clip itself, with its name under it and the arrows for moving about. */
@Composable
private fun ClipPlayer(file: File, alert: Alert) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 1f
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                playing = player.isPlaying
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // The picture's place, read four times a second: a file does not tell the
    // screen when it moves, and the bar under it has to.
    LaunchedEffect(player) {
        while (isActive) {
            delay(400)
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            ended = player.playbackState == Player.STATE_ENDED
            playing = player.isPlaying
        }
    }

    LaunchedEffect(Unit) {
        delay(80)
        runCatching { focus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        if (ended) {
                            player.seekTo(0)
                            player.play()
                        } else if (player.isPlaying) {
                            player.pause()
                        } else {
                            player.play()
                        }
                        true
                    }

                    Key.DirectionLeft -> {
                        player.seekTo((player.currentPosition - SeekMillis).coerceAtLeast(0L))
                        true
                    }

                    Key.DirectionRight -> {
                        val most = if (duration > 0) duration else player.currentPosition + SeekMillis
                        player.seekTo((player.currentPosition + SeekMillis).coerceAtMost(most))
                        true
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.live_video, null) as PlayerView)
                    .apply {
                        this.player = player
                    }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // The scrim, the name, and the bar that says where in the clip this is.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f),
                    ),
                ),
        )
        AlertLine(
            alert = alert,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 32.dp, vertical = 28.dp),
        )

        if (!playing || ended) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                PlayMark()
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            val fraction = if (duration > 0) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Which camera, and when, on one line over the picture. */
@Composable
private fun AlertLine(alert: Alert, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = alert.camera + "  ·  " + whenText(alert.start),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = alert.title ?: alert.words,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
