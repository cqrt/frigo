package nz.mckenzie.frigo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.said
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The chips that narrow a list, shared by Explore and Review so the two pages
 * read the same way: one chip naming the camera, then a chip each for what was
 * seen and for the day.
 */

/**
 * One chip naming the camera in view, opening the list of them. A chip keeps
 * the whole choice on one line, where a chip per camera took a row of its own.
 */
@Composable
fun CameraMenu(
    cameras: List<Camera>,
    chosen: String?,
    onChoose: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val picked = cameras.firstOrNull { it.name == chosen }

    Box {
        FilterChip(
            selected = picked != null,
            onClick = { open = true },
            label = { Text(picked?.title ?: "All cameras") },
            trailingIcon = { Arrow() },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All cameras") },
                onClick = {
                    open = false
                    onChoose(null)
                },
                trailingIcon = if (chosen == null) {
                    { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                } else {
                    null
                },
            )
            cameras.forEach { camera ->
                DropdownMenuItem(
                    text = { Text(camera.title) },
                    onClick = {
                        open = false
                        onChoose(camera.name)
                    },
                    trailingIcon = if (chosen == camera.name) {
                        { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * A chip that opens a list of choices, ticking each one you pick.
 *
 * The chip says the choice when there is one, so the list doesn't have to be
 * opened to see how it is narrowed.
 */
@Composable
fun <T> ChoiceChip(
    title: String,
    choices: List<T>,
    chosen: Set<T>,
    name: (T) -> String,
    onToggle: (T) -> Unit,
    empty: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = chosen.isNotEmpty(),
            onClick = { open = true },
            label = { Text(chosenSaid(title, chosen.map(name))) },
            trailingIcon = { Arrow() },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (choices.isEmpty()) {
                DropdownMenuItem(text = { Text(empty) }, onClick = { open = false })
            } else {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(name(choice)) },
                        onClick = { onToggle(choice) },
                        trailingIcon = if (chosen.contains(choice)) {
                            { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** A chip for the stretch of time, with its own list and a way to a calendar. */
@Composable
fun PeriodChip(period: Period, onChoose: (Period) -> Unit, onPickDay: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = period != Period.All,
            onClick = { open = true },
            label = { Text(period.said()) },
            trailingIcon = { Arrow() },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(Period.All, Period.Today, Period.Yesterday).forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.said()) },
                    onClick = {
                        open = false
                        onChoose(choice)
                    },
                    trailingIcon = if (period == choice) {
                        { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                    } else {
                        null
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Pick a day…") },
                onClick = {
                    open = false
                    onPickDay()
                },
                trailingIcon = if (period is Period.On) {
                    { Icon(Icons.Filled.Check, contentDescription = "Chosen") }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun Arrow() {
    Icon(
        Icons.Filled.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

/** A calendar, for a day that isn't today or yesterday. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val start = (initial ?: LocalDate.now())
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val picker = rememberDatePickerState(initialSelectedDateMillis = start)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) {
                Text("Use this day")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        DatePicker(state = picker)
    }
}

/** "Labels", or "Person +1" when a few are on, so the chip stays short. */
private fun chosenSaid(title: String, names: List<String>): String {
    val sorted = names.sorted()
    return when (sorted.size) {
        0 -> title
        1 -> sorted.first()
        else -> "${sorted.first()} +${sorted.size - 1}"
    }
}

/** `person` and `smoke_detector` said the way a person would. */
fun prettyLabel(raw: String): String = raw
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { it.uppercase() }
