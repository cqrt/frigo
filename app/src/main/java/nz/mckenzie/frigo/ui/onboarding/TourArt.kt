package nz.mckenzie.frigo.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/*
 * The pictures the tour draws: each one the shape of what the tab holds, in the
 * app's own colours. They are not screen grabs, because the pictures on a phone
 * are someone else's cameras.
 */

/**
 * A small drawing of what the tab holds, in the app's own shapes.
 */
@Composable
internal fun TourArt(index: Int) {
    when (index) {
        // Four cameras, and the bar that runs under a camera when you open it.
        0 -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniTile("Front door", Scene.FrontDoor, Modifier.weight(1f).fillMaxHeight())
                MiniTile("Driveway", Scene.Driveway, Modifier.weight(1f).fillMaxHeight())
            }
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniTile("Back yard", Scene.BackYard, Modifier.weight(1f).fillMaxHeight())
                MiniTile("Garage", Scene.Garage, Modifier.weight(1f).fillMaxHeight())
            }
            DayBar()
        }

        1 -> Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip("Alerts 4")
                MiniChip("Detections 12")
            }
            Spacer(Modifier.height(12.dp))
            MiniRow(Scene.FrontDoor, tag = true)
            MiniRow(Scene.Driveway, tag = true)
            MiniRow(Scene.BackYard, tag = false)
        }

        // The filters across the top, a week of days, then what it plays.
        2 -> Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip("Person")
                MiniChip("Driveway")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { day, letter ->
                    DayCell(letter = letter, chosen = day == 4)
                }
            }
            Spacer(Modifier.height(10.dp))
            MiniRow(Scene.Driveway, tag = false)
            MiniRow(Scene.BackYard, tag = false)
        }

        3 -> Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    // A kept clip keeps the camera's shape, so the drawing has
                    // the room to show the whole drive rather than its middle.
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                SceneArt(Scene.Driveway, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x8C08111A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFFE9F0F8),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Bar(0.5f)
            Spacer(Modifier.height(6.dp))
            Bar(0.24f)
        }

        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(switchOn = true, wide = true)
            SettingsRow(switchOn = false)
            SettingsRow(switchOn = false)
            SettingsRow(switchOn = true)
            SettingsRow(switchOn = false)
            SettingsRow(switchOn = null)
        }
    }
}

/**
 * The bar under an open camera: a wash where Frigate kept something, and a
 * sliver of red or amber for everything it flagged. It is drawn from the page's
 * own ink, so it reads the same in Day and Night.
 */
@Composable
private fun DayBar() {
    val ink = MaterialTheme.colorScheme.onBackground
    // The kept hours are a wash of the ink. On the light page the same wash as
    // the night one reads as a solid block, so it goes lighter there.
    val kept = if (ink.luminance() > 0.5f) 0.7f else 0.45f
    val flagged = listOf(
        Color(0xFFE9A23B) to 0.12f,
        Color(0xFFF05252) to 0.30f,
        Color(0xFFE9A23B) to 0.47f,
        Color(0xFFE9A23B) to 0.63f,
        Color(0xFFF05252) to 0.79f,
        Color(0xFFE9A23B) to 0.94f,
    )

    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        // The band inside the strip, where the kept hours and the spikes go, at
        // the same share of the height as the real bar under a camera.
        val bandTop = size.height * 0.32f
        val bandHeight = size.height * 0.36f

        drawRoundRect(
            color = ink.copy(alpha = 0.1f),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(4f, 4f),
        )
        listOf(
            0f to 0.15f,
            0.18f to 0.33f,
            0.41f to 0.65f,
            0.68f to 0.87f,
            0.90f to 1f,
        ).forEach { (from, to) ->
            drawRect(
                color = ink.copy(alpha = kept),
                topLeft = Offset(size.width * from, bandTop),
                size = Size(size.width * (to - from), bandHeight),
            )
        }
        flagged.forEach { (colour, at) ->
            drawRect(
                color = colour,
                topLeft = Offset(size.width * at, bandTop),
                size = Size(size.width * 0.008f, bandHeight),
            )
        }
    }
}

/** One row of the settings drawing: a square, a line, and a switch where there is one. */
@Composable
private fun SettingsRow(switchOn: Boolean?, wide: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Square()
        Spacer(Modifier.width(10.dp))
        Bar(if (wide) 0.46f else 0.34f)
        Spacer(Modifier.weight(1f))
        if (switchOn != null) FakeSwitch(on = switchOn)
    }
}

/** One day in the strip above the list on the Explore tab. */
@Composable
private fun DayCell(letter: String, chosen: Boolean) {
    Box(
        Modifier
            .size(width = 28.dp, height = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (chosen) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniTile(name: String, scene: Scene, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(9.dp))) {
        SceneArt(scene, Modifier.fillMaxSize())
        NameScrim(22.dp, Modifier.align(Alignment.BottomCenter))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFDBE4EF),
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
        )
    }
}

@Composable
private fun MiniChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun MiniRow(scene: Scene, tag: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(7.dp)),
        ) {
            SceneArt(scene, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Bar(0.6f, strong = true)
            Bar(0.36f)
        }
        if (tag) {
            Box(
                Modifier
                    .width(26.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

@Composable
private fun Bar(fraction: Float, strong: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(if (strong) 7.dp else 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (strong) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
    )
}

@Composable
private fun Square() {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun FakeSwitch(on: Boolean) {
    Box(
        Modifier
            .size(width = 32.dp, height = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            ),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(
                    if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.surface,
                ),
        )
    }
}

