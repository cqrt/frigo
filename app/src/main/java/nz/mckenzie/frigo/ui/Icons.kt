package nz.mckenzie.frigo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons this app draws itself, because Material's own set has no magnifying
 * glass with room for a label and no play mark in a box.
 *
 * Everything is painted in one colour and let [androidx.compose.material3.Icon]
 * tint it, so a path that is dimmer than its neighbour fakes depth without a
 * second colour.
 */
object FrigoIcons {

    /** A play mark in a solid box, the box's top edge a shade lighter. */
    val Reviews: ImageVector by lazy {
        vector("Reviews") {
            path(
                // The box and the play mark as one shape, so the mark is a hole.
                "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z" +
                    "M15.6 12 9.9 7.4v9.2z",
                evenOdd = true,
            )
            path(
                // A lid: the band across the top reads as the box's depth.
                "M6 3h12a3 3 0 0 1 3 3v1.6H3V6a3 3 0 0 1 3-3z",
                alpha = 0.22f,
            )
        }
    }

    /** A magnifying glass, for the Explore tab. */
    val Search: ImageVector by lazy {
        vector("Search") {
            path(
                "M10.6 4.2a6.4 6.4 0 1 0 0 12.8 6.4 6.4 0 1 0 0-12.8z",
                strokeWidth = 2.2f,
            )
            path("M15.4 15.4 20.4 20.4", strokeWidth = 2.5f)
        }
    }

    /** Two by two squares, shown on the button that goes from the list to the grid. */
    val Grid: ImageVector by lazy {
        vector("Grid") {
            path(
                "M3.5 5.1a1.6 1.6 0 0 1 1.6-1.6h3.8a1.6 1.6 0 0 1 1.6 1.6v3.8a1.6 1.6 0 0 1-1.6 1.6" +
                    "H5.1a1.6 1.6 0 0 1-1.6-1.6z" +
                    "M13.5 5.1a1.6 1.6 0 0 1 1.6-1.6h3.8a1.6 1.6 0 0 1 1.6 1.6v3.8a1.6 1.6 0 0 1-1.6 1.6" +
                    "h-3.8a1.6 1.6 0 0 1-1.6-1.6z" +
                    "M3.5 15.1a1.6 1.6 0 0 1 1.6-1.6h3.8a1.6 1.6 0 0 1 1.6 1.6v3.8a1.6 1.6 0 0 1-1.6 1.6" +
                    "H5.1a1.6 1.6 0 0 1-1.6-1.6z" +
                    "M13.5 15.1a1.6 1.6 0 0 1 1.6-1.6h3.8a1.6 1.6 0 0 1 1.6 1.6v3.8a1.6 1.6 0 0 1-1.6 1.6" +
                    "h-3.8a1.6 1.6 0 0 1-1.6-1.6z",
            )
        }
    }

    /** A dot and a line, three times, shown on the button that goes from the grid to the list. */
    val Rows: ImageVector by lazy {
        vector("Rows") {
            path(
                // Three bullets in one path, then three lines in another.
                "M4.8 4.9a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3z" +
                    "M4.8 10.5a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3z" +
                    "M4.8 16.1a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3z",
            )
            path(
                "M8.6 6.4a1.1 1.1 0 0 1 1.1-1.1h9.2a1.1 1.1 0 0 1 0 2.2H9.7a1.1 1.1 0 0 1-1.1-1.1z" +
                    "M8.6 12a1.1 1.1 0 0 1 1.1-1.1h9.2a1.1 1.1 0 0 1 0 2.2H9.7A1.1 1.1 0 0 1 8.6 12z" +
                    "M8.6 17.6a1.1 1.1 0 0 1 1.1-1.1h9.2a1.1 1.1 0 0 1 0 2.2H9.7a1.1 1.1 0 0 1-1.1-1.1z",
            )
        }
    }

    /** Four corners opening outward, for the button that fills the screen with the picture. */
    val Fullscreen: ImageVector by lazy {
        vector("Fullscreen") {
            path("M4 9V5.4A1.4 1.4 0 0 1 5.4 4H9", strokeWidth = 2f)
            path("M15 4h3.6A1.4 1.4 0 0 1 20 5.4V9", strokeWidth = 2f)
            path("M20 15v3.6A1.4 1.4 0 0 1 18.6 20H15", strokeWidth = 2f)
            path("M9 20H5.4A1.4 1.4 0 0 1 4 18.6V15", strokeWidth = 2f)
        }
    }

    /** The same corners turned inward, for the way back out of fullscreen. */
    val FullscreenOut: ImageVector by lazy {
        vector("FullscreenOut") {
            path("M9 4v3.6A1.4 1.4 0 0 1 7.6 9H4", strokeWidth = 2f)
            path("M15 4v3.6A1.4 1.4 0 0 0 16.4 9H20", strokeWidth = 2f)
            path("M20 15h-3.6A1.4 1.4 0 0 0 15 16.4V20", strokeWidth = 2f)
            path("M4 15h3.6A1.4 1.4 0 0 1 9 16.4V20", strokeWidth = 2f)
        }
    }

    /** A speaker with two sound waves, for turning the sound on. */
    val Sound: ImageVector by lazy {
        vector("Sound") {
            path("M4 9.6h3.3L11.8 5.4v13.2L7.3 14.4H4a1 1 0 0 1-1-1v-2.8a1 1 0 0 1 1-1z")
            path("M15.4 9.3a3.8 3.8 0 0 1 0 5.4", strokeWidth = 2f)
            path("M18 6.7a7.4 7.4 0 0 1 0 10.6", strokeWidth = 2f)
        }
    }

    /** The same speaker crossed out, for turning it off. */
    val SoundOff: ImageVector by lazy {
        vector("SoundOff") {
            path("M4 9.6h3.3L11.8 5.4v13.2L7.3 14.4H4a1 1 0 0 1-1-1v-2.8a1 1 0 0 1 1-1z")
            path("M15.2 9.8l4.6 4.6", strokeWidth = 2f)
            path("M19.8 9.8l-4.6 4.6", strokeWidth = 2f)
        }
    }

    /** A wide frame with the name written on it, shown on the button that goes from the list to the wide look. */
    val Wide: ImageVector by lazy {
        vector("Wide") {
            path("M4.5 5.5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", strokeWidth = 1.8f)
            path("M5.8 14.2h6", strokeWidth = 1.8f)
        }
    }
}

/** Filled unless a stroke is asked for, which is how the magnifying glass is drawn. */
private fun vector(name: String, block: Builder.() -> Unit): ImageVector {
    val builder = Builder()
    builder.block()
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        builder.paths.forEach { drawn ->
            addPath(
                pathData = addPathNodes(drawn.data),
                pathFillType = if (drawn.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (drawn.strokeWidth == null) SolidColor(Color.Black) else null,
                fillAlpha = drawn.alpha,
                stroke = drawn.strokeWidth?.let { SolidColor(Color.Black) },
                strokeLineWidth = drawn.strokeWidth ?: 0f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }.build()
}

private class Builder {
    val paths = mutableListOf<Drawn>()

    fun path(data: String, alpha: Float = 1f, evenOdd: Boolean = false, strokeWidth: Float? = null) {
        paths += Drawn(data, alpha, evenOdd, strokeWidth)
    }
}

private class Drawn(
    val data: String,
    val alpha: Float,
    val evenOdd: Boolean,
    val strokeWidth: Float?,
)
