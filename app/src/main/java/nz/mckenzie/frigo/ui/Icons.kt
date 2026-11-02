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

    /** An arrow onto a shelf, for keeping a clip as a file. */
    val Save: ImageVector by lazy {
        vector("Save") {
            path("M10.6 3.4h2.8v7.6h3.4L12 16.8l-4.8-5.8h3.4z")
            path("M5 19.8h14", strokeWidth = 2.1f)
        }
    }

    /** A strip of film with its sprocket holes, for the tab of clips kept on the server. */
    val Clips: ImageVector by lazy {
        vector("Clips") {
            path(
                // The frame, with the holes cut out of it: one shape, evenOdd.
                "M4.4 4h15.2a2.4 2.4 0 0 1 2.4 2.4v11.2a2.4 2.4 0 0 1-2.4 2.4H4.4" +
                    "A2.4 2.4 0 0 1 2 17.6V6.4A2.4 2.4 0 0 1 4.4 4z" +
                    "M5.2 7.2h1.8v1.8H5.2zM5.2 11.1h1.8v1.8H5.2zM5.2 15h1.8v1.8H5.2z" +
                    "M17 7.2h1.8v1.8H17zM17 11.1h1.8v1.8H17zM17 15h1.8v1.8H17z",
                evenOdd = true,
            )
            // The picture in the middle of the strip, a shade dimmer than the frame.
            path("M9.2 8.2h5.6v7.6H9.2z", alpha = 0.45f)
        }
    }

    /** Two lines with a knob on each, for the page that says what Frigate flags. */
    val Tune: ImageVector by lazy {
        vector("Tune") {
            path("M4 7h8.4M17.6 7H20M4 17h2.4M11.6 17H20", strokeWidth = 2f)
            path(
                "M15 4.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 1 0 0-4.4z" +
                    "M9 14.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 1 0 0-4.4z",
            )
        }
    }

    /** A corner turned to the right, for a row that opens something. */
    val Chevron: ImageVector by lazy {
        vector("Chevron") {
            path("M9.2 5.4 15.8 12l-6.6 6.6", strokeWidth = 2.2f)
        }
    }

    /** Two stacked boxes, for the address of the server. */
    val Server: ImageVector by lazy {
        vector("Server") {
            path(
                "M5.5 3.6h13a2 2 0 0 1 2 2v2.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V5.6a2 2 0 0 1 2-2z" +
                    "M5.5 14.1h13a2 2 0 0 1 2 2v2.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-2.3a2 2 0 0 1 2-2z",
                strokeWidth = 1.7f,
            )
            path("M7 6.75h.01M7 17.25h.01", strokeWidth = 1.9f)
        }
    }

    /** A head and shoulders, for the account signed in. */
    val Person: ImageVector by lazy {
        vector("Person") {
            path("M12 4.6a3.4 3.4 0 0 0 0 6.8 3.4 3.4 0 0 0 0-6.8z", strokeWidth = 1.7f)
            path("M5.4 19.4c0-3.1 3-5.2 6.6-5.2s6.6 2.1 6.6 5.2", strokeWidth = 1.7f)
        }
    }

    /** A bell, for the notifications setting. */
    val Bell: ImageVector by lazy {
        vector("Bell") {
            path("M6.4 16.2v-5a5.6 5.6 0 0 1 11.2 0v5", strokeWidth = 1.7f)
            path("M4.8 16.2h14.4", strokeWidth = 1.7f)
            path("M10.2 19.4a2 2 0 0 0 3.6 0", strokeWidth = 1.7f)
        }
    }

    /** A camera on its side with a lens at the front, for a camera's switches. */
    val Camera: ImageVector by lazy {
        vector("Camera") {
            path(
                "M5 6.4h9.8a2.2 2.2 0 0 1 2.2 2.2v6.8a2.2 2.2 0 0 1-2.2 2.2H5a2.2 2.2 0 0 1-2.2-2.2V8.6A2.2 2.2 0 0 1 5 6.4z",
                strokeWidth = 1.7f,
            )
            path("M17 10.4 21.2 8v8L17 13.6", strokeWidth = 1.7f)
        }
    }

    /** An eye, for what a camera looks for. */
    val Eye: ImageVector by lazy {
        vector("Eye") {
            path(
                "M3 12.2S6.4 6.9 12 6.9s9 5.3 9 5.3-3.4 5.3-9 5.3-9-5.3-9-5.3z",
                strokeWidth = 1.7f,
            )
            path("M12 9.4a2.8 2.8 0 1 0 0 5.6 2.8 2.8 0 1 0 0-5.6z", strokeWidth = 1.7f)
        }
    }

    /** A line that jumps and settles, for motion. */
    val Motion: ImageVector by lazy {
        vector("Motion") {
            path("M3 12.4h3.4l2.1-5.2 3.4 10.2 2.2-5.8 1.6 3.2H21", strokeWidth = 1.9f)
        }
    }

    /** A photograph in a frame, for snapshots. */
    val Photo: ImageVector by lazy {
        vector("Photo") {
            path(
                "M5.8 5.2h12.4a2.6 2.6 0 0 1 2.6 2.6v8.4a2.6 2.6 0 0 1-2.6 2.6H5.8a2.6 2.6 0 0 1-2.6-2.6" +
                    "V7.8a2.6 2.6 0 0 1 2.6-2.6z",
                strokeWidth = 1.7f,
            )
            path("M8.6 8.6a1.5 1.5 0 1 0 0 3 1.5 1.5 0 1 0 0-3z", strokeWidth = 1.5f)
            path("M4.4 16.6 9.4 12l3.2 3.1 2.6-2.2 4.2 4", strokeWidth = 1.7f)
        }
    }

    /** A face with a smile, for recognising one. */
    val Face: ImageVector by lazy {
        vector("Face") {
            path("M12 4a8 8 0 1 0 0 16 8 8 0 1 0 0-16z", strokeWidth = 1.7f)
            path("M9.2 10.1h.01M14.8 10.1h.01", strokeWidth = 2f)
            path("M8.9 14.4a4 4 0 0 0 6.2 0", strokeWidth = 1.7f)
        }
    }

    /** A car seen from the side, for number plates. */
    val Car: ImageVector by lazy {
        vector("Car") {
            path(
                "M4.6 15.2 6.2 9.7a2.4 2.4 0 0 1 2.3-1.8h7a2.4 2.4 0 0 1 2.3 1.8l1.6 5.5",
                strokeWidth = 1.7f,
            )
            path("M3.6 15.2h16.8", strokeWidth = 1.7f)
            path("M6.4 15.2v2.4M17.6 15.2v2.4", strokeWidth = 1.7f)
        }
    }

    /** A label with a hole, for the objects a camera knows. */
    val Tag: ImageVector by lazy {
        vector("Tag") {
            path(
                "M3.4 6.2h8.4l7.8 5.8-7.8 5.8H3.4a1.4 1.4 0 0 1-1.4-1.4V7.6a1.4 1.4 0 0 1 1.4-1.4z",
                strokeWidth = 1.7f,
            )
            path("M6.6 10.7a1.3 1.3 0 1 0 0 2.6 1.3 1.3 0 1 0 0-2.6z", strokeWidth = 1.5f)
        }
    }

    /** A page of dates, for a day to narrow to. */
    val Calendar: ImageVector by lazy {
        vector("Calendar") {
            path(
                "M5.8 5.4h12.4a2.2 2.2 0 0 1 2.2 2.2v10.6a2.2 2.2 0 0 1-2.2 2.2H5.8a2.2 2.2 0 0 1-2.2" +
                    "-2.2V7.6a2.2 2.2 0 0 1 2.2-2.2z",
                strokeWidth = 1.7f,
            )
            path("M3.6 10.2h16.8", strokeWidth = 1.7f)
            path("M8.4 3.4v3.6M15.6 3.4v3.6", strokeWidth = 1.8f)
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
