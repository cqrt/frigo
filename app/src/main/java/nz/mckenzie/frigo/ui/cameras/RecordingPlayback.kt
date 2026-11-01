package nz.mckenzie.frigo.ui.cameras

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.RecordingSpan
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** The speeds the playback button steps through, in order. */
internal val PlaySpeeds = listOf(1f, 2f, 4f, 8f, 16f)

/**
 * How long the bar has to be still before the picture starts playing. Long enough
 * that a drag which is on its way somewhere does not start fetching a clip, short
 * enough that landing on a moment and letting go feels like it starts at once.
 */
internal const val SettleMillis = 450L

/**
 * How many seconds of recording one piece of playback holds. Frigate cuts a piece
 * with ffmpeg when it is asked, and the app downloads it before playing, so a piece
 * is a balance: long enough that the joins are rare, short enough that the first
 * frame does not wait on a long cut.
 */
private const val ChunkSeconds = 120.0

/**
 * The recording at that moment, playing, in the picture where the still was.
 *
 * Frigate has no live view of what it recorded: it cuts the recording with ffmpeg
 * when it is asked and sends whatever stretch it was asked for. So playing is a
 * chain of pieces fetched one after another. One piece is fetched and handed to the
 * player, and the next is fetched while it plays, so the joins pass without a stop.
 *
 * A stretch with nothing recorded is hopped over rather than played as black: the
 * next piece starts wherever recording starts again, and the playhead jumps with it.
 *
 * Every piece is a file already on the phone the second time you pass it, so going
 * back over a run costs nothing.
 */
@Composable
internal fun RecordingRun(
    repository: FrigoRepository,
    camera: String,
    from: Double,
    spans: List<RecordingSpan>,
    until: Double,
    speed: Float,
    onPosition: (Double) -> Unit,
    onEnd: (Double) -> Unit,
    onProblem: (String) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Where in the day each queued piece starts and ends, so the playhead can say
    // where the picture has got to in the recordings rather than in the files.
    val pieces = remember(camera, from) { mutableStateListOf<RecordingSpan>() }
    val sayPosition by rememberUpdatedState(onPosition)
    val sayEnd by rememberUpdatedState(onEnd)
    val sayProblem by rememberUpdatedState(onProblem)
    val sayFrame by rememberUpdatedState(onFirstFrame)
    // Where the playing has got to, so the end of a run is the last moment that
    // was actually shown rather than the end of whatever was queued.
    val playedTo = remember(camera, from) { mutableDoubleStateOf(from) }

    val player = remember(camera, from) {
        ExoPlayer.Builder(context).build().apply {
            // A sped up recording has nothing worth hearing in it, and the live
            // sound is a separate stream that has no business here.
            volume = 0f
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                sayFrame()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, speed) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    // Watching is no reason to play on in the background.
    DisposableEffect(lifecycle, player) {
        var wasPlaying = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlaying = player.isPlaying
                    player.pause()
                }

                Lifecycle.Event.ON_RESUME -> if (wasPlaying) player.play() else Unit
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Fetch a piece, hand it to the player, and keep one in hand: the next is asked
    // for while this one plays, which is what makes the joins pass unseen. Waiting
    // until the player has started on the piece before is what keeps it to one in
    // hand rather than downloading an afternoon the finger may never reach.
    LaunchedEffect(player, camera, from) {
        var next = from
        var reached = from
        while (true) {
            // The player has started on the newest piece, or there is nothing in
            // hand at all, so there is room for one more. A player that has
            // stopped has nothing to wait for either: more is fetched, and it is
            // set going again below.
            while (pieces.isNotEmpty() && player.currentMediaItemIndex < pieces.lastIndex) {
                if (player.playbackState == Player.STATE_ENDED) break
                delay(150)
            }

            val piece = pieceFrom(next, spans, until) ?: break

            val file = try {
                repository.clipFile(
                    source = repository.recordingClipSource(camera, piece.startTime, piece.endTime),
                    // The same key Play uses, so a stretch you watched from the
                    // button is already on the phone.
                    key = "$camera-${piece.startTime.toLong()}-${piece.endTime.toLong()}",
                    onProgress = {},
                )
            } catch (stop: CancellationException) {
                throw stop
            } catch (failure: Throwable) {
                // A piece that does not come is not worth stopping the ones already
                // queued for: they play out, and the run ends with them.
                android.util.Log.w("Frigo", "playback piece failed: ${piece.startTime}", failure)
                if (pieces.isEmpty()) sayProblem(failure.message ?: "That recording wouldn't come.")
                break
            }

            pieces.add(piece)
            reached = piece.endTime
            player.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            when (player.playbackState) {
                // Ended before the piece got here, which a fast speed makes
                // ordinary: the piece is next, so the player moves on to it.
                Player.STATE_ENDED -> {
                    player.seekTo(player.currentMediaItemIndex + 1, 0)
                    player.play()
                }

                Player.STATE_IDLE -> {
                    player.prepare()
                    player.play()
                }

                else -> Unit
            }
            next = piece.endTime
        }

        // Nothing to play at all, and nothing playing: say so rather than sit on a
        // black picture.
        if (pieces.isEmpty()) {
            sayProblem("There's no recording to play from here.")
            return@LaunchedEffect
        }

        // Everything that can be queued is queued, so the run ends where the
        // playing gets to rather than where the queue does.
        while (player.playbackState != Player.STATE_ENDED) delay(200)
        sayEnd(playedTo.doubleValue.coerceAtMost(reached))
    }

    // Where the picture is, four times a second, in the recordings' own time.
    LaunchedEffect(player) {
        while (true) {
            val piece = pieces.getOrNull(player.currentMediaItemIndex)
            if (piece != null) {
                val at = piece.startTime + player.currentPosition / 1000.0
                val now = at.coerceAtMost(piece.endTime)
                playedTo.doubleValue = now
                sayPosition(now)
            }
            delay(250)
        }
    }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                keepScreenOn = true
            }
        },
        modifier = modifier,
    )
}

/**
 * The piece of recording to play from a moment: the stretch the moment is in, or
 * the next one when it fell in a gap, cut to [ChunkSeconds] and stopping at the end
 * of that stretch and at [until].
 */
internal fun pieceFrom(
    moment: Double,
    spans: List<RecordingSpan>,
    until: Double,
): RecordingSpan? {
    val span = spans.firstOrNull { moment >= it.startTime && moment < it.endTime }
        ?: spans.firstOrNull { it.startTime > moment }
        ?: return null
    val start = maxOf(moment, span.startTime)
    val end = minOf(span.endTime, until, start + ChunkSeconds)
    if (end <= start) return null
    return RecordingSpan(startTime = start, endTime = end)
}

/**
 * The speed button, on the picture while it plays. One tap cycles through the
 * speeds and starts again at normal.
 */
@Composable
internal fun SpeedButton(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "${speed.toInt()}×",
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClickLabel = "Change the speed") { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
