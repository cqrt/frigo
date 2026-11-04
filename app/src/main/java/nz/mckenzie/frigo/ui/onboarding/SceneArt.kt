package nz.mckenzie.frigo.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import kotlin.math.max

/*
 * The pictures the walkthrough draws: ordinary houses, not photographs of
 * anyone's cameras. One drawing serves Day and Night, because the drawing
 * itself does not change with the theme, only the screen around it.
 */

/** Which drawing fills the box. */
internal enum class Scene {
    FrontDoor,
    Driveway,
    BackYard,
    Garage,
}

/** The drawings are laid out on this box and scaled to whatever box you give them. */
private const val Wide = 160f
private const val High = 100f

/**
 * Fills the box with a drawing. A box that isn't the drawing's shape is cropped
 * at the edges rather than squashed, the way a camera picture is.
 */
@Composable
internal fun SceneArt(scene: Scene, modifier: Modifier = Modifier, figure: Boolean = false) {
    Canvas(modifier) {
        val scale = max(size.width / Wide, size.height / High)
        withTransform({
            translate(
                (size.width - Wide * scale) / 2f,
                (size.height - High * scale) / 2f,
            )
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            when (scene) {
                Scene.FrontDoor -> frontDoor(figure)
                Scene.Driveway -> driveway()
                Scene.BackYard -> backYard()
                Scene.Garage -> garage()
            }
        }
    }
}

/**
 * The dark wash under a picture's name, so the name reads on a busy drawing.
 * It covers the bottom of the picture and leaves the rest alone.
 */
@Composable
internal fun NameScrim(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xC6030810)),
                ),
            ),
    )
}

/* ------------------------------------------------------------- the scenes */

/** A porch: a door under two lamps, a step, and a plant beside it. */
private fun DrawScope.frontDoor(figure: Boolean = false) {
    val wall = Color(0xFFBFCFDF)
    val ground = Color(0xFFA9BACD)
    val siding = Color(0xFFB2C2D4)
    val door = Color(0xFF46688A)
    val panel = Color(0xFF3D5D7D)

    box(0f, 0f, 160f, 100f, wall)
    box(0f, 70f, 160f, 30f, ground)
    for (board in 16..64 step 16) box(0f, board.toFloat(), 160f, 1f, siding)
    box(48f, 84f, 64f, 6f, Color(0xFFCBD7E3), 1f)
    box(56f, 20f, 48f, 62f, Color(0xFFEFF4F9), 2f)
    box(60f, 24f, 40f, 58f, door)
    box(66f, 30f, 28f, 18f, Color(0xFF6E92B4))
    box(66f, 54f, 12f, 22f, panel)
    box(82f, 54f, 12f, 22f, panel)
    dot(95f, 55f, 1.9f, Color(0xFFE8C87A))
    box(66f, 78f, 28f, 5f, Color(0xFF8FA2B7), 1.5f)
    box(44f, 30f, 7f, 9f, Color(0xFFEFE3BF), 1.5f)
    box(109f, 30f, 7f, 9f, Color(0xFFEFE3BF), 1.5f)
    box(24f, 66f, 17f, 15f, Color(0xFF9CAFC3), 2f)
    dot(28f, 60f, 7f, Color(0xFF7E9B85))
    dot(38f, 62f, 6f, Color(0xFF6E8C78))
    dot(33f, 55f, 6f, Color(0xFF90AB94))

    // Someone at the door, for the page about what the server makes of a visitor.
    if (figure) {
        dot(80f, 60f, 4f, Color(0xFFE3CDB4))
        box(73f, 65f, 14f, 19f, Color(0xFF3D5D7D), 4f)
    }
}

/** A house side: a garage, the drive running out to the road, a car on it. */
private fun DrawScope.driveway() {
    val house = Color(0xFFDEE7F0)
    val lines = Color(0xFF416080)
    val rail = Color(0xFFB9C7D6)

    box(0f, 0f, 160f, 100f, Color(0xFFC6D5E4))
    box(0f, 52f, 160f, 48f, Color(0xFF93A98E))
    box(0f, 20f, 88f, 52f, house)
    box(0f, 16f, 88f, 6f, Color(0xFF8FA3B8))
    box(24f, 34f, 50f, 38f, Color(0xFFEFF4F9))
    box(26f, 36f, 46f, 34f, Color(0xFF4E6E8E))
    for (seam in listOf(45f, 54f, 63f)) box(26f, seam, 46f, 1.5f, lines)
    shape(Color(0xFFC2CBD7)) {
        moveTo(40f, 72f)
        lineTo(84f, 72f)
        lineTo(110f, 100f)
        lineTo(14f, 100f)
        close()
    }
    box(28f, 74f, 46f, 14f, Color(0xFF7C93AC), 5f)
    box(36f, 65f, 28f, 11f, Color(0xFF8FA6BE), 4f)
    dot(39f, 90f, 4.5f, Color(0xFF3C4A5A))
    dot(63f, 90f, 4.5f, Color(0xFF3C4A5A))
    for (post in listOf(118f, 132f, 146f)) box(post, 34f, 2f, 34f, rail)
    for (bar in listOf(44f, 56f)) box(112f, bar, 44f, 1.6f, rail)
    dot(104f, 34f, 14f, Color(0xFF6F8B74))
    dot(96f, 42f, 9f, Color(0xFF7E9B82))
}

/** A back yard: a fence, a tree, a patio, and a planter along the lawn. */
private fun DrawScope.backYard() {
    val wood = Color(0xFFC4B49B)

    box(0f, 0f, 160f, 100f, Color(0xFFCBD9E7))
    box(0f, 44f, 160f, 56f, Color(0xFF8CA588))
    for (bar in listOf(34f, 48f)) box(0f, bar, 160f, 4f, wood)
    for (post in 0..140 step 20) box(post.toFloat(), 28f, 4f, 36f, wood)
    box(156f, 28f, 4f, 36f, wood)
    box(112f, 50f, 6f, 28f, Color(0xFF8A7A66))
    dot(115f, 42f, 16f, Color(0xFF6F8B74))
    dot(104f, 48f, 11f, Color(0xFF7E9B82))
    dot(126f, 48f, 11f, Color(0xFF7E9B82))
    box(16f, 74f, 36f, 18f, Color(0xFFC6CDD7), 2f)
    box(58f, 70f, 24f, 11f, Color(0xFFA9BBD0), 2f)
    dot(64f, 65f, 5.5f, Color(0xFF8FAA93))
    dot(76f, 65f, 5.5f, Color(0xFF8FAA93))
    dot(70f, 60f, 5f, Color(0xFF9DB79F))
}

/** A garage door head on, with its lamp and the apron running out. */
private fun DrawScope.garage() {
    val lines = Color(0xFF416080)

    box(0f, 0f, 160f, 100f, Color(0xFFC9D6E5))
    box(14f, 10f, 132f, 80f, Color(0xFFEFF4F9), 3f)
    box(18f, 14f, 124f, 72f, Color(0xFF4E6E8E))
    for (seam in listOf(32f, 50f, 68f)) box(18f, seam, 124f, 2f, lines)
    for (stile in listOf(56f, 104f)) {
        box(stile, 14f, 2f, 18f, lines)
        box(stile, 50f, 2f, 18f, lines)
    }
    box(70f, 2f, 20f, 7f, Color(0xFFEFE3BF), 2f)
    shape(Color(0xFFC2CBD7)) {
        moveTo(14f, 86f)
        lineTo(146f, 86f)
        lineTo(160f, 100f)
        lineTo(0f, 100f)
        close()
    }
    drawOval(Color(0xFFAEB9C6), Offset(50f, 90.5f), Size(60f, 9f))
}

/* --------------------------------------------------------------- helpers */

private fun DrawScope.box(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    colour: Color,
    radius: Float = 0f,
) {
    if (radius > 0f) {
        drawRoundRect(
            color = colour,
            topLeft = Offset(x, y),
            size = Size(w, h),
            cornerRadius = CornerRadius(radius, radius),
        )
    } else {
        drawRect(colour, Offset(x, y), Size(w, h))
    }
}

private fun DrawScope.dot(x: Float, y: Float, r: Float, colour: Color) {
    drawCircle(colour, r, Offset(x, y))
}

private fun DrawScope.shape(colour: Color, block: Path.() -> Unit) {
    drawPath(Path().apply(block), colour)
}
