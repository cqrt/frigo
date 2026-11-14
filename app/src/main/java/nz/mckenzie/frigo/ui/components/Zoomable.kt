package nz.mckenzie.frigo.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** The most a picture may be pinched in. */
private const val MostZoom = 5f

/** How far a double tap takes a picture in. */
private const val DoubleTapZoom = 2.5f

/**
 * How far a picture is pinched in, and where it has been dragged to.
 *
 * A screen that wants to say something about the zoom, such as offering a way
 * back to the whole picture, keeps the state of its own and hands it in.
 */
class PictureZoom internal constructor() {

    internal var scale by mutableFloatStateOf(1f)
    internal var offset by mutableStateOf(Offset.Zero)

    /** True while any of the picture is out of sight. */
    val zoomed: Boolean get() = scale > 1f

    /** Back to the whole picture, in the middle of its frame. */
    fun fit() {
        scale = 1f
        offset = Offset.Zero
    }
}

/** A fresh zoom for [key], so a new clip or camera opens on its whole picture. */
@Composable
fun rememberPictureZoom(key: Any): PictureZoom = remember(key) { PictureZoom() }

/**
 * A picture you can pinch into, a drag to move around it, and a double tap to go
 * back to the whole frame.
 *
 * [aspect] is the picture's own shape, so it is drawn whole inside the box and
 * fitted rather than cropped: a pinch has something to move into. [picture] is
 * the camera's stream, a clip, or a still, and the zoom belongs to the screen
 * rather than to any one frame, so what is drawn underneath can change without
 * the view moving. [onTap] is a plain tap, for a screen whose controls come and go.
 *
 * [overlay] is drawn in a box the shape of the picture itself, over it and never
 * scaled with it, which is where a way back to the whole frame belongs: the
 * picture is fitted inside its frame, so a corner of the frame is not a corner of
 * the picture.
 */
@Composable
fun ZoomablePicture(
    zoom: PictureZoom,
    aspect: Float,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    picture: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var viewport by remember(zoom) { mutableStateOf(IntSize.Zero) }
    val tapped by rememberUpdatedState(onTap)

    // Zooming keeps the point between your fingers where it is, which is what
    // makes a pinch feel like it is grabbing the picture.
    fun zoomTo(next: Float, around: Offset, drag: Offset = Offset.Zero) {
        val size = viewport
        if (size.width <= 0 || size.height <= 0) return
        val fitted = fitPicture(aspect, size)
        val factor = next / zoom.scale
        val centre = Offset(size.width / 2f, size.height / 2f)
        val fromCentre = around - centre
        val moved = fromCentre - (fromCentre - zoom.offset) * factor + drag
        zoom.scale = next
        zoom.offset = keepInside(moved, next, fitted, size)
    }

    val fitted = fitPicture(aspect, viewport)

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(zoom, aspect) {
                detectTapGestures(
                    onTap = { tapped?.invoke() },
                    onDoubleTap = { point ->
                        if (zoom.scale > 1f) {
                            zoom.fit()
                        } else {
                            zoomTo(DoubleTapZoom, point)
                        }
                    },
                )
            }
            .pointerInput(zoom, aspect) {
                detectTransformGestures { centroid, pan, zoomBy, _ ->
                    val next = (zoom.scale * zoomBy).coerceIn(1f, MostZoom)
                    zoomTo(next, centroid, pan)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(
                    width = with(density) { fitted.width.toDp() },
                    height = with(density) { fitted.height.toDp() },
                )
                .graphicsLayer(
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offset.x,
                    translationY = zoom.offset.y,
                ),
        ) {
            picture()
        }

        if (overlay != null && fitted.width > 0 && fitted.height > 0) {
            Box(
                Modifier.size(
                    width = with(density) { fitted.width.toDp() },
                    height = with(density) { fitted.height.toDp() },
                ),
            ) {
                overlay()
            }
        }
    }
}

/** The rectangle a picture of [aspect] is drawn in, fitted inside [viewport]. */
private fun fitPicture(aspect: Float, viewport: IntSize): IntSize {
    if (viewport.width <= 0 || viewport.height <= 0 || aspect <= 0f) return IntSize.Zero
    val height = (viewport.width / aspect).roundToInt()
    return if (height <= viewport.height) {
        IntSize(viewport.width, height)
    } else {
        IntSize((viewport.height * aspect).roundToInt(), viewport.height)
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
