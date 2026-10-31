package nz.mckenzie.frigo.ui.components

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nz.mckenzie.frigo.data.FrigateException
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.File

/**
 * What the full-screen viewer is showing: a title, the line under it, a clip if
 * there is one, and a still to show while the clip is on its way.
 */
data class ClipSubject(
    val title: String,
    val subtitle: String,
    /**
     * Null when Frigate kept no clip, which leaves the still on screen. Otherwise
     * this fetches the clip and hands back the file it landed in, saying how much
     * has arrived as it goes.
     */
    val clip: (suspend (onProgress: (Long) -> Unit) -> File)?,
    val stillKey: String,
    val still: suspend () -> ByteArray?,
    /** Said at the bottom when there is no clip at all. */
    val note: String? = null,
    /** Said once, when the clip passes its halfway point. */
    val onHalfWatched: (() -> Unit)? = null,
)

/**
 * One clip filling the screen, with its title over the top.
 *
 * Frigate cuts a clip out of your recordings when it is asked for one, and sends
 * it with no length and no support for jumping about inside it. So the clip is
 * fetched into a file first and played from there. A player reading the stream
 * directly calls it stuck after ten seconds without progress, which is what a
 * home connection on a phone does often enough to matter.
 */
@Composable
fun ClipViewer(subject: ClipSubject, onDismiss: () -> Unit) {
    var attempt by remember(subject.stillKey) { mutableIntStateOf(0) }
    var file by remember(subject.stillKey) { mutableStateOf<File?>(null) }
    var received by remember(subject.stillKey) { mutableLongStateOf(0L) }
    var problem by remember(subject.stillKey) { mutableStateOf<Problem?>(null) }
    var started by remember(subject.stillKey) { mutableStateOf(false) }
    // On its side, the clip gets the whole screen. Turning the phone is the button.
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val clip = subject.clip

    // Keyed on the attempt so "Try again" asks the server again. Closing the
    // viewer cancels this, which stops the download where it got to.
    LaunchedEffect(clip, attempt) {
        if (clip == null) return@LaunchedEffect
        file = null
        received = 0L
        problem = null
        started = false
        try {
            file = clip { bytes ->
                // Every chunk would recompose the screen; four updates a megabyte
                // is enough to watch.
                if (bytes - received >= 256 * 1024) received = bytes
            }
        } catch (stop: kotlinx.coroutines.CancellationException) {
            throw stop
        } catch (failure: Throwable) {
            problem = problemFrom(failure)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        StatusBarIcons(darkIcons = false)
        HideSystemBars(landscape)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val ready = file
            // A clip that failed to play leaves its own controls behind, so the
            // still goes back up and the reason is read over that.
            if (ready != null && problem == null) {
                ClipPlayer(
                    file = ready,
                    onStarted = { started = true },
                    onProblem = { problem = it },
                    onHalfWatched = subject.onHalfWatched,
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
                if (!landscape) {
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
                }

                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        problem != null -> ProblemPanel(
                            problem = problem!!,
                            onRetry = {
                                // A clip that downloaded short stays short on disk.
                                // Remove it so the retry fetches it again rather
                                // than playing the same broken file.
                                file?.delete()
                                file = null
                                problem = null
                                started = false
                                attempt++
                            },
                            onClose = onDismiss,
                        )

                        clip == null -> subject.note?.let {
                            FootNote(
                                text = it,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 32.dp),
                            )
                        }

                        ready == null -> GettingClip(received = received)

                        !started -> CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // On its side the header is gone, so the way out sits on the picture.
            if (landscape) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** Said while the clip is being fetched, with how much has arrived. */
@Composable
private fun GettingClip(received: Long) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = "Getting the clip…",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (received > 0) {
            Text(
                text = megabytes(received),
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1048576.0)

@Composable
private fun ProblemPanel(problem: Problem, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            // Readable over whatever the still happens to be.
            .background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.large)
            .padding(24.dp),
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
 * The clip itself, played from a file on the phone. There is no token and no
 * network in here: by the time the player starts, the whole clip is on disk.
 */
@Composable
private fun ClipPlayer(
    file: File,
    onStarted: () -> Unit,
    onProblem: (Problem) -> Unit,
    onHalfWatched: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentStarted by rememberUpdatedState(onStarted)
    val currentProblem by rememberUpdatedState(onProblem)
    val watchedHalf by rememberUpdatedState(onHalfWatched)
    var wasPlaying by remember { mutableStateOf(false) }

    val player = remember(file) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
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
                currentProblem(playbackProblemFrom(error))
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Half the clip is where Frigate counts a review as watched, so a clip left
    // part way through the first half stays in the list. The player reports its
    // position twice a second, and the check waits until the length is known.
    LaunchedEffect(player) {
        while (true) {
            val length = player.duration
            if (length > 0 && player.currentPosition * 2 >= length) {
                watchedHalf?.invoke()
                return@LaunchedEffect
            }
            delay(500)
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

/** Why the fetch failed, in a sentence with something to do about it. */
private fun problemFrom(failure: Throwable): Problem {
    val status = (failure as? FrigateException)?.status ?: 0
    val detail = buildString {
        if (status > 0) append("the server answered ").append(status)
        else append(failure.javaClass.simpleName)
        failure.message?.takeIf { it.isNotBlank() }?.let {
            append(": ").append(it.take(90))
        }
    }
    android.util.Log.w("Frigo", "clip fetch failed: $detail", failure)

    return Problem(
        message = failure.friendlyMessage(),
        // A refused token needs a new sign-in, not another try.
        canRetry = status != 401 && status != 403,
        detail = detail,
    )
}

/** Why a clip that was already on the phone didn't play. */
private fun playbackProblemFrom(error: PlaybackException): Problem {
    val detail = buildString {
        append(error.errorCodeName)
        error.cause?.let { append(" · ").append(it.javaClass.simpleName) }
    }
    android.util.Log.w("Frigo", "clip playback failed: $detail", error)

    return when (error.errorCode) {
        // What a phone says when it has no decoder for the video, which is
        // possible because a clip keeps whatever the camera recorded. Frigate
        // copies the stream rather than converting it, so an H.265 camera gives
        // H.265 clips.
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> Problem(
            message = "This phone can't play this clip's video. Frigate copies what the " +
                "camera recorded rather than converting it, so these clips are H.265, and " +
                "not every phone can decode that.",
            canRetry = false,
            detail = detail,
        )

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Problem(
            message = "The clip has gone from the phone. Fetch it again.",
            canRetry = true,
            detail = detail,
        )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> Problem(
            message = "The clip arrived incomplete. Fetch it again.",
            canRetry = true,
            detail = detail,
        )

        // The player reached the end of the file before the clip did. Frigate
        // writes a clip as it goes, and for a broken one it stops writing part
        // way through and never closes the response. Fetching it again gives the
        // same short file, so there is nothing to try.
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED if error.isAtEndOfFile() -> Problem(
            message = "Frigate didn't finish writing this clip, so it stops part way " +
                "through. Fetching it again would give the same broken file.",
            canRetry = false,
            detail = detail,
        )

        else -> Problem(message = "That clip wouldn't play.", canRetry = true, detail = detail)
    }
}

/** Whether the failure underneath is the file simply running out. */
private fun PlaybackException.isAtEndOfFile(): Boolean {
    var cause: Throwable? = this.cause
    while (cause != null) {
        if (cause is java.io.EOFException) return true
        cause = cause.cause
    }
    return false
}

/**
 * A failure the viewer can show, and whether trying again is worth offering. The
 * detail is the failure's own name, shown small, so a report says what happened
 * rather than only that something did.
 */
private data class Problem(
    val message: String,
    val canRetry: Boolean,
    val detail: String,
)
