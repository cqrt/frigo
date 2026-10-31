package nz.mckenzie.frigo.ui.cameras

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.MjpegReader
import nz.mckenzie.frigo.ui.components.StatusBarIcons
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.IOException

/** The heights and rates offered in the player. Bigger means more data. */
private val Heights = listOf(1080, 720, 480)
private val Rates = listOf(15, 10, 5, 2)

/** How far in a pinch can go. Past this the picture is just soft pixels. */
private const val MaxZoom = 5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    repository: FrigoRepository,
    cameraName: String,
    title: String,
    onBack: () -> Unit,
) {
    var frame by remember(cameraName) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(cameraName) { mutableStateOf<String?>(null) }
    var connecting by remember(cameraName) { mutableStateOf(true) }
    var height by rememberSaveable(cameraName) { mutableIntStateOf(720) }
    var rate by rememberSaveable(cameraName) { mutableIntStateOf(5) }
    var menuOpen by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(cameraName, height, rate) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            connecting = true
            problem = null
            while (isActive) {
                try {
                    repository.openMjpeg(cameraName, height, rate).use { response ->
                        val body = response.body ?: throw IOException("The server sent no stream")
                        val reader = MjpegReader(body.byteStream())
                        withContext(Dispatchers.IO) {
                            while (isActive) {
                                val bytes = reader.nextFrame() ?: break
                                val bitmap = MjpegReader.decode(bytes) ?: continue
                                withContext(Dispatchers.Main.immediate) {
                                    frame = bitmap.asImageBitmap()
                                    connecting = false
                                }
                            }
                        }
                    }
                    problem = "The stream stopped."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    problem = error.friendlyMessage()
                }
                connecting = true
                delay(2_000)
            }
        }
    }

    StatusBarIcons(darkIcons = false)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Stream quality")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Heights.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option}p") },
                            onClick = { height = option; menuOpen = false },
                        )
                    }
                    Rates.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option frames a second") },
                            onClick = { rate = option; menuOpen = false },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            frame?.let { bitmap ->
                ZoomableStill(bitmap = bitmap, camera = cameraName)
            }

            if (connecting) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = problem ?: "Connecting…",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (problem != null) {
                        Text(
                            text = "Retrying",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (frame != null) {
                Text(
                    text = "MJPEG · ${height}p · $rate fps",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * The picture, with a pinch to zoom in, a drag to move around it, and a double
 * tap to go back to the whole frame.
 *
 * A new frame arrives every moment, so the zoom belongs to the screen rather than
 * to any one picture: the frames change underneath while the view stays where you
 * put it.
 */
@Composable
private fun ZoomableStill(
    bitmap: ImageBitmap,
    camera: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember(camera) { mutableFloatStateOf(1f) }
    var offset by remember(camera) { mutableStateOf(Offset.Zero) }
    var viewport by remember(camera) { mutableStateOf(IntSize.Zero) }
    var fitted by remember(camera) { mutableStateOf(IntSize.Zero) }

    // Zooming keeps the point between your fingers where it is, which is what
    // makes a pinch feel like it is grabbing the picture.
    fun zoomTo(next: Float, around: Offset, drag: Offset = Offset.Zero) {
        val factor = next / scale
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val fromCentre = around - centre
        val moved = fromCentre - (fromCentre - offset) * factor + drag
        scale = next
        offset = keepInside(moved, next, fitted, viewport)
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(camera) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            zoomTo(2.5f, point)
                        }
                    },
                )
            }
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MaxZoom)
                    zoomTo(next, centroid, pan)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .onSizeChanged { fitted = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/**
 * Stops the picture being dragged away from the screen: it can move until its
 * edge meets the edge of the view, and no further.
 */
private fun keepInside(offset: Offset, scale: Float, fitted: IntSize, viewport: IntSize): Offset {
    val roomX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val roomY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        x = offset.x.coerceIn(-roomX, roomX),
        y = offset.y.coerceIn(-roomY, roomY),
    )
}
