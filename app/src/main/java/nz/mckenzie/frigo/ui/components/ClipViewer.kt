package nz.mckenzie.frigo.ui.components

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.FrigateException
import nz.mckenzie.frigo.data.words
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
 * One clip, with everything about it.
 *
 * Frigate cuts a clip out of your recordings when it is asked for one, and sends
 * it with no length and no support for jumping about inside it. So the clip is
 * fetched into a file first and played from there. A player reading the stream
 * directly calls it stuck after ten seconds without progress, which is what a
 * home connection on a phone does often enough to matter.
 *
 * [sheet] lays the viewer out as a page of the app: the line with the camera and
 * the time below the status bar, the clip in a frame under it, [around] beneath
 * that for a strip of other cameras, and [details] on the card filling the rest.
 * Explore and Clips leave it false and get the full screen clip they always had.
 * On its side a sheet becomes the full screen clip too. The tree is the same
 * either way, so turning the phone doesn't fetch or restart the clip.
 */
@Composable
fun ClipViewer(
    subject: ClipSubject,
    onDismiss: () -> Unit,
    sheet: Boolean = false,
    around: (@Composable () -> Unit)? = null,
    details: (@Composable () -> Unit)? = null,
) {
    val state = remember(subject.stillKey) { ClipState() }
    // On its side, the clip gets the whole screen. Turning the phone is the button.
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val clip = subject.clip
    val context = LocalContext.current

    // Keyed on the attempt so "Try again" asks the server again. Closing the
    // viewer cancels this, which stops the download where it got to.
    LaunchedEffect(clip, state.attempt) {
        if (clip == null) return@LaunchedEffect
        state.file = null
        state.received = 0L
        state.problem = null
        state.started = false
        try {
            state.file = clip { bytes ->
                // Every chunk would recompose the screen; four updates a megabyte
                // is enough to watch.
                if (bytes - state.received >= 256 * 1024) state.received = bytes
            }
        } catch (stop: kotlinx.coroutines.CancellationException) {
            throw stop
        } catch (failure: Throwable) {
            state.problem = problemFrom(failure, context)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        HideSystemBars(landscape)
        if (sheet) {
            // The page takes the app's own colours, so its status bar icons follow.
            StatusBarIcons(
                darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f,
            )
            SheetClip(
                subject = subject,
                state = state,
                landscape = landscape,
                navBar = navBar,
                around = around,
                details = details,
                onDismiss = onDismiss,
            )
        } else {
            StatusBarIcons(darkIcons = false)
            FullScreenClip(
                subject = subject,
                state = state,
                landscape = landscape,
                navBar = navBar,
                onDismiss = onDismiss,
            )
        }
    }
}

/** What the viewer has of the clip so far: the file, how far it got, and any trouble. */
private class ClipState {
    var attempt by mutableIntStateOf(0)
    var file by mutableStateOf<File?>(null)
    var received by mutableLongStateOf(0L)
    var problem by mutableStateOf<Problem?>(null)
    var started by mutableStateOf(false)

    /** A clip that downloaded short stays short on disk, so a retry fetches it again. */
    fun retry() {
        file?.delete()
        file = null
        problem = null
        started = false
        attempt++
    }
}

/**
 * The clip alone on a black page, as a clip viewer has always been, for the events
 * in Explore and the clips you kept.
 */
@Composable
private fun FullScreenClip(
    subject: ClipSubject,
    state: ClipState,
    landscape: Boolean,
    navBar: Dp,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PictureContent(
            subject = subject,
            state = state,
            modifier = Modifier.fillMaxSize().padding(bottom = navBar),
        )

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
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.comp_close),
                            tint = Color.White,
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ClipStatus(
                    subject = subject,
                    state = state,
                    dark = true,
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // On its side the header is gone, so the way out sits on the picture.
        if (landscape) {
            CloseOnPicture(onDismiss)
        }
    }
}

/** The line above the clip: one line with a card under it, two without. */
private val SheetHeader = 56.dp

/**
 * The viewer as a page: the line above the picture, the clip in its own frame, the
 * other cameras at that moment, and the card under both.
 */
@Composable
private fun SheetClip(
    subject: ClipSubject,
    state: ClipState,
    landscape: Boolean,
    navBar: Dp,
    around: (@Composable () -> Unit)?,
    details: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // The line above the clip. It keeps its place on its side at no height,
            // so the picture below is always the same slot in the tree.
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(if (landscape) 0.dp else SheetHeader),
            ) {
                if (!landscape) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            // With a card under the clip the title is said there, so
                            // this line is the camera and the time alone.
                            if (details == null) {
                                Text(
                                    text = subject.title,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = subject.subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.comp_close),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }

            Box(
                if (landscape) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(16f / 9f)
                },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(if (landscape) 0.dp else 16.dp))
                        .background(Color.Black),
                ) {
                    PictureContent(
                        subject = subject,
                        state = state,
                        // Keeps the player's own controls clear of the gesture bar.
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = if (landscape) navBar else 0.dp),
                    )
                }
            }

            if (!landscape && around != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                ) {
                    around()
                }
            }

            if (!landscape && details != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        details.invoke()
                        // Anything that isn't the picture is read down here rather
                        // than squeezed into the frame above it.
                        ClipStatus(
                            subject = subject,
                            state = state,
                            dark = false,
                            onDismiss = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else if (!landscape) {
                ClipStatus(
                    subject = subject,
                    state = state,
                    dark = false,
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }

        if (landscape) {
            ClipStatus(
                subject = subject,
                state = state,
                dark = true,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxSize(),
            )
            CloseOnPicture(onDismiss)
        }
    }
}

/** The picture in its frame: the clip once it is on the phone, the still until then. */
@Composable
private fun PictureContent(
    subject: ClipSubject,
    state: ClipState,
    modifier: Modifier,
) {
    val ready = state.file
    // A clip that failed to play leaves its own controls behind, so the still goes
    // back up and the reason is read over that.
    if (ready != null && state.problem == null) {
        ClipPlayer(
            file = ready,
            onStarted = { state.started = true },
            onProblem = { state.problem = it },
            onHalfWatched = subject.onHalfWatched,
            modifier = modifier,
        )
    } else {
        CachedImage(
            cacheKey = subject.stillKey,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            background = Color.Black,
            load = subject.still,
        )
    }
}

/** The way out when the header is gone, drawn on the picture itself. */
@Composable
private fun BoxScope.CloseOnPicture(onDismiss: () -> Unit) {
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
            contentDescription = stringResource(R.string.comp_close),
            tint = Color.White,
        )
    }
}

/**
 * The part of the viewer that isn't the picture: why a clip won't play, how much
 * of it has arrived, or that there is no clip at all. [dark] says it is being read
 * over a picture, so it takes white ink and a see-through panel; on the page and
 * its card it takes the theme's own.
 */
@Composable
private fun ClipStatus(
    subject: ClipSubject,
    state: ClipState,
    dark: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = state.problem
    val hasClip = subject.clip != null
    val shows = problem != null ||
        (!hasClip && subject.note != null) ||
        (hasClip && state.file == null) ||
        (hasClip && state.file != null && !state.started)
    if (!shows) return

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            problem != null -> ProblemPanel(
                problem = problem,
                onRetry = { state.retry() },
                onClose = onDismiss,
                dark = dark,
            )

            !hasClip -> subject.note?.let {
                FootNote(
                    text = it,
                    dark = dark,
                    modifier = if (dark) {
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    } else {
                        Modifier
                    },
                )
            }

            state.file == null -> GettingClip(received = state.received, dark = dark)

            !state.started -> CircularProgressIndicator(
                color = if (dark) Color.White else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Said while the clip is being fetched, with how much has arrived. */
@Composable
private fun GettingClip(received: Long, dark: Boolean = true) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = if (dark) Color.White else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.comp_clip_getting),
            color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (received > 0) {
            Text(
                text = megabytes(received),
                color = if (dark) {
                    Color.White.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1048576.0)

@Composable
private fun ProblemPanel(
    problem: Problem,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    dark: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(if (dark) 32.dp else 0.dp)
            // Readable over whatever the still happens to be, and a raised
            // surface when it sits on the page.
            .background(
                if (dark) {
                    Color.Black.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                if (dark) MaterialTheme.shapes.large else MaterialTheme.shapes.medium,
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = problem.message,
            color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = problem.detail,
            color = if (dark) {
                Color.White.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (problem.canRetry) {
                Button(onClick = onRetry) { Text(stringResource(R.string.comp_try_again)) }
            }
            TextButton(onClick = onClose) {
                Text(
                    text = stringResource(R.string.comp_close),
                    color = if (dark) Color.White else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FootNote(text: String, modifier: Modifier = Modifier, dark: Boolean = true) {
    Text(
        text = text,
        color = if (dark) {
            Color.White.copy(alpha = 0.8f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(
                if (dark) {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                } else {
                    Modifier
                },
            ),
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
                currentProblem(playbackProblemFrom(error, context))
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
private fun problemFrom(failure: Throwable, context: Context): Problem {
    val status = (failure as? FrigateException)?.status ?: 0
    val detail = buildString {
        if (status > 0) append(context.words(R.string.comp_clip_detail_status, status))
        else append(failure.javaClass.simpleName)
        failure.message?.takeIf { it.isNotBlank() }?.let {
            append(": ").append(it.take(90))
        }
    }
    android.util.Log.w("Frigo", "clip fetch failed: $detail", failure)

    return Problem(
        message = failure.friendlyMessage(context),
        // A refused token needs a new sign-in, not another try.
        canRetry = status != 401 && status != 403,
        detail = detail,
    )
}

/** Why a clip that was already on the phone didn't play. */
private fun playbackProblemFrom(error: PlaybackException, context: Context): Problem {
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
            message = context.words(R.string.comp_clip_cant_play),
            canRetry = false,
            detail = detail,
        )

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Problem(
            message = context.words(R.string.comp_clip_gone),
            canRetry = true,
            detail = detail,
        )

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> Problem(
            message = context.words(R.string.comp_clip_incomplete),
            canRetry = true,
            detail = detail,
        )

        // The player reached the end of the file before the clip did. Frigate
        // writes a clip as it goes, and for a broken one it stops writing part
        // way through and never closes the response. Fetching it again gives the
        // same short file, so there is nothing to try.
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED if error.isAtEndOfFile() -> Problem(
            message = context.words(R.string.comp_clip_unfinished),
            canRetry = false,
            detail = detail,
        )

        else -> Problem(
            message = context.words(R.string.comp_clip_wouldnt_play),
            canRetry = true,
            detail = detail,
        )
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
