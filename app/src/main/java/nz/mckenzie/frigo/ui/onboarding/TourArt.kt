package nz.mckenzie.frigo.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        // Four tiles, filling the box whatever its height.
        0 -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniTile("Front door", Modifier.weight(1f).fillMaxHeight())
                MiniTile("Driveway", Modifier.weight(1f).fillMaxHeight())
            }
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MiniTile("Back yard", Modifier.weight(1f).fillMaxHeight())
                MiniTile("Garage", Modifier.weight(1f).fillMaxHeight())
            }
        }

        1 -> Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniChip("Alerts 4")
                MiniChip("Detections 12")
            }
            Spacer(Modifier.height(12.dp))
            MiniRow(tag = true)
            MiniRow(tag = true)
            MiniRow(tag = false)
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
            MiniRow(tag = false)
            MiniRow(tag = false)
        }

        3 -> Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A3B4F), Color(0xFF111A26)))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFFE9F0F8),
                    modifier = Modifier.size(30.dp),
                )
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
            SettingsRow(switchOn = null)
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
private fun MiniTile(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2A3B4F), Color(0xFF111A26)))),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9030810))),
            ),
        )
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
private fun MiniRow(tag: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2A3B4F), Color(0xFF111A26)))),
        )
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

