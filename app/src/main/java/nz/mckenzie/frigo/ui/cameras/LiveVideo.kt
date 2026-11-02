package nz.mckenzie.frigo.ui.cameras

import android.os.SystemClock
import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.ClipSource

/**
 * How much of the stream the player keeps, and how much of it has to be in hand
 * before the picture starts. A few seconds of each: enough to ride out a stutter on
 * mobile data, short enough that the picture stays close to now. The default is
 * tens of seconds, which a live picture never recovers from.
 */
private const val LiveBufferMillis = 3_000
private const val LiveStartMillis = 500

/** How often the picture's place against the clock is checked. */
private const val LagEveryMillis = 4_000L

/** How far the picture may fall behind before the stream is opened again. */
private const val MostLagMillis = 15_000L

/** How long the player may sit waiting for more before it is opened again. */
private const val StuckMillis = 12_000L

/**
 * The camera's own picture, as the camera sends it.
 *
 * Frigate's stream is one frame per camera, kept at the size it decodes for
 * detection. This is the camera's H.264 instead, coming from go2rtc, which is
 * packaged with Frigate and reachable through the same proxy path the sound uses. It
 * is the whole picture, it costs a fraction of the data, it arrives about a second
 * behind rather than four, and the sound comes with it.
 *
 * [onFirstFrame] is what tells the screen the picture has arrived, and [onFailed] is
 * what sends it back to Frigate's stream. A stream that stops is a failure as much
 * as one that never started: what is on screen would otherwise be a still that looks
 * live.
 *
 * [visible] is what keeps the video out of the way while the stills stand in for it.
 * The player is a view of its own, and a view is drawn over everything the page
 * draws around it, so a still drawn beside it would sit underneath it rather than on
 * top. The video is there with nothing drawn in it until it has a frame, which is
 * what that flag is for.
 */
@Composable
internal fun LiveVideo(
    source: ClipSource,
    camera: String,
    withSound: Boolean,
    visible: Boolean,
    onFirstFrame: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sayFrame by rememberUpdatedState(onFirstFrame)
    val sayFailed by rememberUpdatedState(onFailed)
    // The picture's own shape, so it is drawn whole and a pinch has something to
    // move into. Cameras send 16:9, which is right until the stream says otherwise.
    var aspect by remember(camera) { mutableFloatStateOf(PictureAspect) }
    // Counts the times the stream has been opened for this camera. The watchdog
    // below moves it on, and a new player is what comes of that.
    var opening by remember(camera) { mutableIntStateOf(0) }

    val player = remember(camera, source.url, opening) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(http(source.token)))
            // A live stream is worth only what is happening now. The default
            // buffers tens of seconds, which on a live picture means tens of
            // seconds of lag that never comes back on its own.
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
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                // Unplugging the headphones should stop it rather than play the
                // garden out loud.
                setHandleAudioBecomingNoisy(true)
                volume = if (withSound) 1f else 0f
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(source.url)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LiveStartMillis.toLong())
                                .build(),
                        )
                        .build(),
                )
                prepare()
                playWhenReady = true
            }
    }

    // Whether this player has drawn a frame yet. A player that is still filling its
    // first buffer reports itself as waiting for more, which is not the same thing as
    // a picture that has stalled, and reopening it there only pushes the picture
    // further away.
    var started by remember(player) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                started = true
                sayFrame()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onPlayerError(error: PlaybackException) = sayFailed()

            // The stream has stopped carrying the camera. The stills stream is the
            // thing that works when that happens.
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) sayFailed()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Watching is no reason to play on in the background.
    DisposableEffect(lifecycle, player) {
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    LaunchedEffect(player, withSound) {
        player.volume = if (withSound) 1f else 0f
    }

    // A stream with no end never says it has fallen behind, and a picture that lags
    // further and further behind the clock is the thing this screen was changed to
    // get away from. So the gap between the picture and now is measured while the
    // screen is in front of you, and the stream is opened again when it grows. A
    // player that sits waiting for more is treated the same way. Nothing is measured
    // until the first frame is up: a stream on its way is waiting for more, and
    // reopening it there does not make it arrive sooner.
    LaunchedEffect(player, camera) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var soonest = Long.MAX_VALUE
            var waitingSince = 0L
            while (isActive) {
                delay(LagEveryMillis)
                if (!started) continue
                val now = SystemClock.elapsedRealtime()
                if (player.playbackState == Player.STATE_BUFFERING) {
                    if (waitingSince == 0L) waitingSince = now
                    if (now - waitingSince > StuckMillis) {
                        opening++
                        return@repeatOnLifecycle
                    }
                } else {
                    waitingSince = 0L
                    if (!player.isPlaying) continue
                    // How far the picture is behind the clock, measured against the
                    // best it has managed since it was opened: the stream itself
                    // need not start at any particular place.
                    val behind = now - player.currentPosition
                    soonest = minOf(soonest, behind)
                    if (behind - soonest > MostLagMillis) {
                        opening++
                        return@repeatOnLifecycle
                    }
                }
            }
        }
    }

    ZoomablePicture(camera = camera, aspect = aspect, modifier = modifier) {
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.live_video, null) as PlayerView)
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A player that sends the sign-in and keeps the stream open until it is closed. */
private fun http(token: String): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        .setUserAgent("Frigo")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
