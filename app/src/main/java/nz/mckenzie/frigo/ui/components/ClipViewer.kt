package nz.mckenzie.frigo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import nz.mckenzie.frigo.data.ClipSource

/**
 * What the full-screen viewer is showing: a title, the line under it, the clip if
 * there is one, and a still to show when there is not.
 */
data class ClipSubject(
    val title: String,
    val subtitle: String,
    /** Null when Frigate kept no clip, which leaves the still on screen. */
    val clip: ClipSource?,
    val stillKey: String,
    val still: suspend () -> ByteArray?,
    /** Said at the bottom when there is no clip at all. */
    val note: String? = null,
)

/**
 * One clip filling the screen, with its title over the top.
 *
 * Frigate cuts a clip out of your recordings when it is asked for one, so a clip
 * is only there for as long as the recordings are.
 */
@Composable
fun ClipViewer(subject: ClipSubject, onDismiss: () -> Unit) {
    var attempt by remember(subject.stillKey) { mutableIntStateOf(0) }
    var problem by remember(subject.stillKey) { mutableStateOf<Problem?>(null) }
    var started by remember(subject.stillKey) { mutableStateOf(false) }
    val clip = remember(subject.clip?.url, attempt) { subject.clip }
    val playing = clip != null && problem == null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        StatusBarIcons(darkIcons = false)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (playing) {
                ClipPlayer(
                    source = clip!!,
                    onStarted = { started = true },
                    onProblem = { problem = it },
                    // Keeps the player's own controls clear of the gesture bar.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                )
            } else {
                CachedImage(
                    cacheKey = subject.stillKey,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    background = Color.Black,
                    load = subject.still,
                )
            }

            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = subject.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subject.subtitle,
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        playing && !started -> CircularProgressIndicator(color = Color.White)

                        problem != null -> ProblemPanel(
                            problem = problem!!,
                            onRetry = {
                                problem = null
                                started = false
                                attempt++
                            },
                            onClose = onDismiss,
                        )

                        subject.note != null -> FootNote(
                            text = subject.note,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProblemPanel(problem: Problem, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = problem.message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (problem.canRetry) {
                Button(onClick = onRetry) { Text("Try again") }
            }
            TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        }
    }
}

@Composable
private fun FootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The clip itself. The token goes on every request, because Frigate's auth covers
 * the clip as well as the API.
 */
@Composable
private fun ClipPlayer(
    source: ClipSource,
    onStarted: () -> Unit,
    onProblem: (Problem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentStarted by rememberUpdatedState(onStarted)
    val currentProblem by rememberUpdatedState(onProblem)
    var wasPlaying by remember { mutableStateOf(false) }

    val player = remember(source) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${source.token}"))
            .setUserAgent("Frigo")
            .setAllowCrossProtocolRedirects(true)
            // Frigate cuts the clip with ffmpeg before it sends anything, so the
            // first byte can take a moment. The connect itself should not.
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(30_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http)
                    // One attempt, so a server that is down says so in seconds
                    // rather than being retried quietly for half a minute.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            )
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(source.url))
                playWhenReady = true
                prepare()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) currentStarted()
            }

            override fun onPlayerError(error: PlaybackException) {
                currentProblem(problemFrom(error))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Watching a clip is no reason to keep playing in the background.
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = player.isPlaying
                    player.pause()
                }

                Lifecycle.Event.ON_RESUME -> if (wasPlaying) player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
            }
        },
        modifier = modifier,
    )
}

/** Why the clip didn't play, in a sentence with something to do about it. */
private fun problemFrom(error: PlaybackException): Problem {
    val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    return when {
        status == 401 || status == 403 -> Problem(
            message = "Your session has expired. Sign in again.",
            canRetry = false,
        )

        status == 400 || status == 404 -> Problem(
            message = "Frigate has no clip for this one any more. It cuts clips out " +
                "of your recordings, so an old one goes when the recordings do.",
            canRetry = false,
        )

        status != null -> Problem(
            message = "The server wouldn't send the clip. It answered $status.",
            canRetry = true,
        )

        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Problem(
            message = "Couldn't reach your server.",
            canRetry = true,
        )

        else -> Problem(message = "That clip wouldn't play.", canRetry = true)
    }
}

/** A failure the player can show, and whether trying again is worth offering. */
private data class Problem(val message: String, val canRetry: Boolean)
