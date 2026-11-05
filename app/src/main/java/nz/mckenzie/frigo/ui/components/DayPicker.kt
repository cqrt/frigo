package nz.mckenzie.frigo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.ui.screenLocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * A month you walk back through, with a dot under each day that still has
 * something you haven't looked at.
 *
 * [marked] are those days. Explore has nothing to mark them with, so it passes
 * none and the calendar is only a calendar.
 */
@Composable
fun DayDialog(
    initial: LocalDate?,
    marked: Set<LocalDate> = emptySet(),
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val locale = screenLocale()
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    var month by remember {
        mutableStateOf(YearMonth.from(initial ?: today))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { month = month.minusMonths(1) },
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.comp_month_before),
                        )
                    }
                    Text(
                        text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        // Nothing has happened in a month that hasn't started.
                        onClick = { month = month.plusMonths(1) },
                        enabled = month.isBefore(YearMonth.from(today)),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.comp_month_after),
                        )
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    (0 until 7).forEach { offset ->
                        Text(
                            text = firstDay.plus(offset.toLong())
                                .getDisplayName(TextStyle.NARROW, locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                monthWeeks(month, firstDay).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            DayCell(
                                day = day,
                                chosen = day != null && day == initial,
                                today = today,
                                marked = day != null && day in marked,
                                onPick = onPick,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.comp_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    day: LocalDate?,
    chosen: Boolean,
    today: LocalDate,
    marked: Boolean,
    onPick: (LocalDate) -> Unit,
) {
    Box(
        modifier = Modifier.weight(1f).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (day == null) {
            Box(Modifier.size(CellSize))
            return@Box
        }
        // A day that hasn't happened can't be picked, and says so quietly.
        val ahead = day.isAfter(today)
        val circle = when {
            chosen -> Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
            day == today -> Modifier.border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            )
            else -> Modifier
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (ahead) {
                Modifier.size(CellSize)
            } else {
                Modifier
                    .size(CellSize)
                    .clickable { onPick(day) }
            },
        ) {
            Box(
                modifier = Modifier.size(30.dp).then(circle),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        chosen -> MaterialTheme.colorScheme.onPrimary
                        ahead -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            // The dot says there's something here you haven't looked at.
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(4.dp)
                    .background(
                        if (marked) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
            )
        }
    }
}

private val CellSize = 38.dp

/**
 * A month in weeks, with nothing in the blanks before the first day and after
 * the last. [firstDay] is where a week starts, which is the app's own.
 */
internal fun monthWeeks(month: YearMonth, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<List<LocalDate?>> {
    val leading = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val cells = MutableList<LocalDate?>(leading) { null }
    cells += (1..month.lengthOfMonth()).map { month.atDay(it) }
    cells += List((7 - cells.size % 7) % 7) { null }
    return cells.chunked(7)
}
