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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ImageCache

internal fun ByteArray.toImageBitmapOrNull(): ImageBitmap? {
    if (size == 0) return null
    val bitmap: Bitmap = BitmapFactory.decodeByteArray(this, 0, size) ?: return null
    return bitmap.asImageBitmap()
}

/**
 * One still, refreshed once a second while it is on screen. Slow enough not to
 * matter for a camera that is not moving, quick enough that you notice a person.
 */
@Composable
fun CameraStill(
    repository: FrigoRepository,
    camera: String,
    modifier: Modifier = Modifier,
    height: Int = 360,
    intervalMillis: Long = 1_000,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var frame by remember(camera) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(camera, height, intervalMillis) {
        while (isActive) {
            val bytes = repository.latestFrame(camera, height)
            val decoded = bytes?.let { withContext(Dispatchers.Default) { it.toImageBitmapOrNull() } }
            if (decoded != null) frame = decoded
            delay(intervalMillis)
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

/**
 * A picture that does not change: event thumbnails and snapshots. Held in
 * [ImageCache] so scrolling back up does not fetch the same JPEG twice.
 */
@Composable
fun CachedImage(
    cacheKey: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
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
