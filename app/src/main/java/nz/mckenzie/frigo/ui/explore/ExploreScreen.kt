package nz.mckenzie.frigo.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.relativeTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What Frigate has recorded, newest first, the way the Explore page on the server
 * shows it. The chips under the title narrow it by camera, by what was seen, by
 * the person Frigate recognised, and by day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<FrigateEvent?>(null) }
    var pickingDay by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Explore") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )

        if (state.cameras.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    FilterChip(
                        selected = state.camera == null,
                        onClick = { viewModel.selectCamera(null) },
                        label = { Text("All") },
                    )
                }
                items(state.cameras, key = { it.name }) { camera ->
                    FilterChip(
                        selected = state.camera == camera.name,
                        onClick = { viewModel.selectCamera(camera.name) },
                        label = { Text(camera.title) },
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ChoiceChip(
                    text = chosenText("Labels", state.chosenLabels) { prettyLabel(it) },
                    active = state.chosenLabels.isNotEmpty(),
                    choices = state.labels,
                    chosen = state.chosenLabels,
                    name = { prettyLabel(it) },
                    onToggle = viewModel::toggleLabel,
                    empty = "The server listed no labels.",
                )
            }
            item {
                ChoiceChip(
                    text = chosenText("People", state.chosenSubLabels) { it },
                    active = state.chosenSubLabels.isNotEmpty(),
                    choices = state.subLabels,
                    chosen = state.chosenSubLabels,
                    name = { it },
                    onToggle = viewModel::toggleSubLabel,
                    empty = "Frigate hasn't recognised a face yet.",
                )
            }
            item {
                PeriodChip(
                    period = state.period,
                    onChoose = viewModel::setPeriod,
                    onPickDay = { pickingDay = true },
                )
            }
            if (state.narrowed) {
                item {
                    TextButton(onClick = viewModel::clearFilters) { Text("Clear") }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.events.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.events.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) { Text("Try again") }
                }

                state.events.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (state.narrowed) {
                            "Nothing recorded for these filters."
                        } else {
                            "Nothing recorded yet."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.narrowed) {
                        Button(onClick = viewModel::clearFilters) { Text("Clear filters") }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.events, key = { it.id }) { event ->
                        EventRow(
                            repository = container.repository,
                            event = event,
                            onClick = { opened = event },
                        )
                    }
                    if (state.hasMore) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    Button(onClick = viewModel::loadMore) { Text("Load older") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { event ->
        ExplorePlayerDialog(
            repository = container.repository,
            event = event,
            onDismiss = { opened = null },
        )
    }

    if (pickingDay) {
        DayDialog(
            initial = (state.period as? Period.On)?.day,
            onDismiss = { pickingDay = false },
            onPick = { day ->
                viewModel.setPeriod(Period.On(day))
                pickingDay = false
            },
        )
    }
}

/** A chip that opens a list of names, each one ticked when it's on. */
@Composable
private fun <T> ChoiceChip(
    text: String,
    active: Boolean,
    choices: List<T>,
    chosen: Set<T>,
    name: (T) -> String,
    onToggle: (T) -> Unit,
    empty: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(text) },
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
private fun PeriodChip(period: Period, onChoose: (Period) -> Unit, onPickDay: () -> Unit) {
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
private fun DayDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
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

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

private fun Period.said(): String = when (this) {
    Period.All -> "All time"
    Period.Today -> "Today"
    Period.Yesterday -> "Yesterday"
    is Period.On -> day.format(dayFormat)
}

/** "Labels", or "Person +1" when a few are on, so the chip stays short. */
private fun chosenText(name: String, chosen: Set<String>, pretty: (String) -> String): String {
    val sorted = chosen.sorted()
    return when (sorted.size) {
        0 -> name
        1 -> pretty(sorted.first())
        else -> "${pretty(sorted.first())} +${sorted.size - 1}"
    }
}

private fun prettyLabel(raw: String): String = raw
    .replace('_', ' ')
    .trim()
    .replaceFirstChar { it.uppercase() }

@Composable
private fun EventRow(
    repository: FrigoRepository,
    event: FrigateEvent,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(8.dp)) {
                CachedImage(
                    cacheKey = "thumb-${event.id}",
                    modifier = Modifier
                        .size(width = 112.dp, height = 63.dp)
                        .aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                    load = { repository.thumbnail(event.id) },
                )
                if (event.hasClip) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Has a clip",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = event.title(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = prettyCameraName(event.camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = relativeTime(event.startedAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun FrigateEvent.title(): String {
    val base = label.replaceFirstChar { it.uppercase() }
    return if (subLabel.isNullOrBlank()) base else "$base · $subLabel"
}
