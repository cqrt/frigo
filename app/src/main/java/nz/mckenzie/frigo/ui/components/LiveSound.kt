package nz.mckenzie.frigo.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import nz.mckenzie.frigo.data.ClipSource
import nz.mckenzie.frigo.data.FrigoRepository

/**
 * A camera's sound, while [playing] is true.
 *
 * The picture comes one JPEG at a time down Frigate's API, which carries no
 * sound at all. Sound comes from go2rtc instead, on a path of its own, and the
 * two are separate streams: the sound drifts a second or so behind the picture,
 * which is the cost of getting it at all.
 *
 * The player is made and thrown away with the switch, so nothing is downloaded
 * while the sound is off. [onFailed] means the server sent nothing, which is
 * what a server without the second path set up does.
 */
@Composable
fun LiveSound(
    repository: FrigoRepository,
    camera: String,
    playing: Boolean,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val failed by rememberUpdatedState(onFailed)
    val source = remember(camera) { runCatching { repository.soundSource(camera) }.getOrNull() }

    DisposableEffect(camera, playing, source) {
        if (!playing || source == null) return@DisposableEffect onDispose { }

        val player = soundPlayer(context, source) { failed() }

        // A player keeps going in the background, and a camera's sound in a pocket
        // is nobody's idea of useful.
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(watcher)

        onDispose {
            lifecycle.removeObserver(watcher)
            player.release()
        }
    }
}

/** A player for one stream, with the sign-in it needs, started at once. */
private fun soundPlayer(context: Context, source: ClipSource, onError: () -> Unit): ExoPlayer {
    val data = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${source.token}"))
        .setUserAgent("Frigo")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(ProgressiveMediaSource.Factory(data))
        .build()

    player.setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build(),
        true,
    )
    // Unplugging the headphones should stop it rather than play it out loud.
    player.setHandleAudioBecomingNoisy(true)
    // The stream has no end. If the server closes it, start again rather than stop.
    player.repeatMode = Player.REPEAT_MODE_ONE
    player.addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onError()
        }
    })

    player.setMediaItem(MediaItem.fromUri(source.url))
    player.prepare()
    player.playWhenReady = true
    return player
}
