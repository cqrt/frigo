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
        Text(
            text = problem.detail,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodySmall,
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
            // first byte can take a moment. A phone on a slow connection to a
            // long clip needs longer than a LAN does.
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(http)
                    // Two more attempts, so a hiccup on the way to the first byte
                    // doesn't end the clip.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(2))
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
    // The reason sits somewhere down the chain, so look at all of it rather than
    // only the first cause. A proxy's answer and a decoder's complaint arrive at
    // different depths.
    val chain = generateSequence<Throwable>(error) { it.cause }.toList()
    val status = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

    val detail = buildString {
        append(error.errorCodeName)
        if (status != null) {
            append(" · the server answered ").append(status)
        } else {
            val cause = chain.drop(1).firstOrNull()
            if (cause != null) append(" · ").append(cause.javaClass.simpleName)
            val said = chain.drop(1).firstNotNullOfOrNull { cause ->
                cause.message?.takeIf { it.isNotBlank() }
            }
            if (said != null) append(": ").append(said.take(90))
        }
    }
    android.util.Log.w("Frigo", "clip failed: $detail", error)

    return when {
        status == 401 || status == 403 -> Problem(
            message = "Your session has expired. Sign in again.",
            canRetry = false,
            detail = detail,
        )

        status == 400 || status == 404 -> Problem(
            message = "Frigate has no clip for this one any more. It cuts clips out " +
                "of your recordings, so an old one goes when the recordings do.",
            canRetry = false,
            detail = detail,
        )

        status != null -> Problem(
            message = "The server wouldn't send the clip. It answered $status.",
            canRetry = true,
            detail = detail,
        )

        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Problem(
            message = "Couldn't reach your server.",
            canRetry = true,
            detail = detail,
        )

        // What a phone says when it has no decoder for the video, which is
        // possible because a clip keeps whatever the camera recorded. Frigate
        // copies the stream rather than converting it, so an H.265 camera gives
        // H.265 clips.
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> Problem(
            message = "This phone can't play this clip's video. A clip keeps whatever the " +
                "camera recorded, and this one is H.265, which not every phone can decode.",
            canRetry = false,
            detail = detail,
        )

        // A proxy that answers a request for the middle of the file with the whole
        // file again leaves the player unable to jump.
        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> Problem(
            message = "The player couldn't jump to that part of the clip. The server sends " +
                "the whole clip every time it is asked for a piece.",
            canRetry = true,
            detail = detail,
        )

        // A proxy that blocks a request sometimes answers with its own page, which
        // is not a video, and the player says the file is malformed.
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> Problem(
            message = "The server sent a page instead of a clip. If you reach Frigate " +
                "through a proxy, it may have stopped this request.",
            canRetry = true,
            detail = detail,
        )

        error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> Problem(
            message = "The clip took too long to start. A clip is cut from your recordings " +
                "when it is asked for, so a long one takes a moment.",
            canRetry = true,
            detail = detail,
        )

        else -> Problem(message = "That clip wouldn't play.", canRetry = true, detail = detail)
    }
}

/**
 * A failure the player can show, and whether trying again is worth offering.
 * The detail is the error's own name, shown small, so a report says what happened
 * rather than only that something did.
 */
private data class Problem(
    val message: String,
    val canRetry: Boolean,
    val detail: String,
)
