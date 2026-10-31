package nz.mckenzie.frigo.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ImageCache
import nz.mckenzie.frigo.data.MjpegReader
import java.io.IOException

internal fun ByteArray.toImageBitmapOrNull(): ImageBitmap? {
    if (size == 0) return null
    val bitmap: Bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return null
    return bitmap.asImageBitmap()
}

/**
 * One camera's picture, kept up to date while it is on screen.
 *
 * By default it asks for one still a second: slow enough not to matter for a
 * camera that is not moving, quick enough that you notice a person. [live] turns
 * that into the live stream instead, which moves smoothly and costs a great deal
 * more of both the phone's data and its battery.
 *
 * [refreshKey] is for a pull down the list: change it and a new picture is
 * fetched at once, rather than when the next second comes round.
 */
@Composable
fun CameraStill(
    repository: FrigoRepository,
    camera: String,
    modifier: Modifier = Modifier,
    height: Int = 360,
    intervalMillis: Long = 1_000,
    refreshKey: Int = 0,
    contentScale: ContentScale = ContentScale.Crop,
    live: Boolean = false,
) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(camera, height, intervalMillis, refreshKey, live) {
        // Only while the screen is actually in front of you: these are network
        // requests per second, and the app sitting in the background should not
        // keep making them.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!live) {
                showStills(repository, camera, height, intervalMillis) { frame = it }
                return@repeatOnLifecycle
            }

            // A camera the server will not stream falls back to a picture a
            // second, which always works. Two failed attempts in a row is enough
            // to know.
            var refusals = 0
            while (refusals < 2) {
                val shown = try {
                    showStream(repository, camera, height) { frame = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stopped: Throwable) {
                    0
                }
                refusals = if (shown > 0) 0 else refusals + 1
                delay(if (shown > 0) 1_000 else 2_000)
            }
            showStills(repository, camera, height, intervalMillis) { frame = it }
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        frame?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A picture a second until the screen goes away. */
private suspend fun showStills(
    repository: FrigoRepository,
    camera: String,
    height: Int,
    intervalMillis: Long,
    onFrame: (ImageBitmap) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val bytes = repository.latestFrame(camera, height)
        val decoded = bytes?.let { withContext(Dispatchers.Default) { it.toImageBitmapOrNull() } }
        if (decoded != null) onFrame(decoded)
        delay(intervalMillis)
    }
}

/** Frames off the live stream until it ends, reporting how many arrived. */
private suspend fun showStream(
    repository: FrigoRepository,
    camera: String,
    height: Int,
    onFrame: (ImageBitmap) -> Unit,
): Int {
    var shown = 0
    repository.openMjpeg(camera, height, LiveTileFps).use { response ->
        val body = response.body ?: throw IOException("The server sent no stream")
        val reader = MjpegReader(body.byteStream())
        withContext(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                val bytes = reader.nextFrame() ?: break
                val bitmap = MjpegReader.decode(bytes) ?: continue
                shown++
                withContext(Dispatchers.Main.immediate) { onFrame(bitmap.asImageBitmap()) }
            }
        }
    }
    return shown
}

/** Frames a second for a tile. Enough to see someone walk past, and no more. */
private const val LiveTileFps = 5

/**
 * A picture that does not change: event thumbnails and snapshots. Held in
 * [ImageCache] so scrolling back up does not fetch the same JPEG twice.
 */
@Composable
fun CachedImage(
    cacheKey: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    load: suspend () -> ByteArray?,
) {
    var frame by remember(cacheKey) { mutableStateOf(ImageCache.get(cacheKey)?.asImageBitmap()) }

    LaunchedEffect(cacheKey) {
        if (frame != null) return@LaunchedEffect
        val bytes = load() ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) { bytes.toImageBitmapOrNull() } ?: return@LaunchedEffect
        ImageCache.put(cacheKey, decoded.asAndroidBitmap())
        frame = decoded
    }

    Box(modifier.background(background)) {
        frame?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
