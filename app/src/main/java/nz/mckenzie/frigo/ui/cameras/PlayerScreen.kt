package nz.mckenzie.frigo.ui.cameras

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.MjpegReader
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.HideSystemBars
import nz.mckenzie.frigo.ui.components.LiveSound
import nz.mckenzie.frigo.ui.components.StatusBarIcons
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.IOException
import kotlin.math.abs

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
    onBack: () -> Unit,
) {
    // Which camera is on screen. Swiping changes this rather than navigating, so
    // Back still lands on the grid you came from.
    var watching by rememberSaveable(cameraName) { mutableStateOf(cameraName) }
    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var zoomed by remember(cameraName) { mutableStateOf(false) }

    val title = cameras.firstOrNull { it.name == watching }?.title ?: prettyCameraName(watching)

    var frame by remember(watching) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(watching) { mutableStateOf<String?>(null) }
    var connecting by remember(watching) { mutableStateOf(true) }
    var height by rememberSaveable(watching) { mutableIntStateOf(720) }
    var rate by rememberSaveable(watching) { mutableIntStateOf(5) }
    var menuOpen by remember { mutableStateOf(false) }
    var sound by rememberSaveable(watching) { mutableStateOf(false) }
    var noSound by rememberSaveable(watching) { mutableStateOf(false) }
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The order the grid shows, so a swipe goes wherever the grid would.
    LaunchedEffect(Unit) {
        runCatching { repository.cameras() }.onSuccess { cameras = it }
    }

    // The last camera leads round to the first, so a swipe is never a dead end.
    fun step(by: Int) {
        val names = cameras.map { it.name }
        val at = names.indexOf(watching)
        if (names.size < 2 || at < 0) return
        watching = names[(at + by + names.size) % names.size]
        zoomed = false
    }

    LaunchedEffect(watching, height, rate) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            connecting = true
            problem = null
            while (isActive) {
                try {
                    repository.openMjpeg(watching, height, rate).use { response ->
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
    // On its side, the picture gets the whole screen. No button: turning the
    // phone is the button.
    HideSystemBars(landscape)

    LiveSound(repository = repository, camera = watching, playing = sound) {
        sound = false
        noSound = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (!landscape) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { sound = !sound; noSound = false }) {
                        Icon(
                            imageVector = if (sound) FrigoIcons.Sound else FrigoIcons.SoundOff,
                            contentDescription = if (sound) "Turn the sound off" else "Turn the sound on",
                        )
                    }
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
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            frame?.let { bitmap ->
                SwipeForNextCamera(
                    enabled = cameras.size > 1 && !zoomed,
                    onSwipe = ::step,
                ) {
                    ZoomableStill(
                        bitmap = bitmap,
                        camera = watching,
                        onZoom = { zoomed = it },
                    )
                }
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

            if (frame != null && !landscape) {
                Text(
                    text = "MJPEG · ${height}p · $rate fps" + if (sound) " · sound" else "",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (noSound) {
                Text(
                    text = "No sound came through.",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = if (landscape) 12.dp else 46.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // On its side there is no bar, so the sound sits over the picture.
            if (landscape) {
                IconButton(
                    onClick = { sound = !sound; noSound = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .displayCutoutPadding()
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (sound) FrigoIcons.Sound else FrigoIcons.SoundOff,
                        contentDescription = if (sound) "Turn the sound off" else "Turn the sound on",
                        tint = Color.White,
                    )
                }
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
    onZoom: (Boolean) -> Unit = {},
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
        onZoom(next > 1f)
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
                            onZoom(false)
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

/** How far a finger has to travel sideways before it counts as a swipe. */
private val SwipeDistance = 90.dp

/**
 * Watches for a sideways swipe and says which way it went: 1 for the next camera,
 * -1 for the one before.
 *
 * The picture underneath takes traffic first unless [enabled] is true, in which
 * case this gets the events before it does. When you are zoomed in, a sideways
 * drag is you moving around the picture, so [enabled] is false and this keeps out
 * of the way entirely.
 *
 * Only a drag that is mostly sideways and long enough counts, and only a drag
 * consumes anything, so a tap and a double tap still reach the picture.
 */
@Composable
private fun SwipeForNextCamera(
    enabled: Boolean,
    onSwipe: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val swipe by rememberUpdatedState(onSwipe)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val far = SwipeDistance.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var across = 0f
                    var downTheScreen = 0f
                    var swiping = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        across += change.positionChange().x
                        downTheScreen += change.positionChange().y
                        if (!swiping && abs(across) > slop && abs(across) > abs(downTheScreen)) {
                            swiping = true
                        }
                        if (swiping) change.consume()
                        if (!change.pressed) break
                    }
                    if (swiping && abs(across) >= far) swipe(if (across < 0f) 1 else -1)
                }
            },
    ) {
        content()
    }
}
