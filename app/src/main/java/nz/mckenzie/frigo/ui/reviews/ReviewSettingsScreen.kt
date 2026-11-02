package nz.mckenzie.frigo.ui.reviews

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer

/**
 * The page behind the tune icon on Review: which objects make an alert, which
 * make a detection, and whether either is made at all.
 *
 * Frigate keeps these in its own settings file and applies a change as it saves,
 * so the next alert follows it. They are set for all cameras at once, and any one
 * camera can be set on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: ReviewSettingsViewModel = viewModel(
        factory = ReviewSettingsViewModel.factory(container),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    val shown = state.shown
    var picking by remember { mutableStateOf<ReviewLabels?>(null) }

    val choosing = picking
    if (choosing != null && shown != null) {
        LabelPicker(
            kind = choosing,
            objects = page?.settings?.objects.orEmpty(),
            tracked = page?.settings?.tracked.orEmpty(),
            initial = when (choosing) {
                ReviewLabels.Alerts -> shown.alertLabels
                ReviewLabels.Detections -> shown.detectionLabels.orEmpty()
            },
            onDone = {
                viewModel.setLabels(choosing, it)
                picking = null
            },
            onBack = { picking = null },
        )
        return
    }


    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Alerts & Detections") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        when {
            page == null && state.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            page == null -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.error ?: "Can't read these settings.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) { Text("Try again") }
            }

            shown == null -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "That camera has settings of its own this app can't read.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item("all") {
                        FilterChip(
                            selected = state.scope == null,
                            onClick = { viewModel.show(null) },
                            label = { Text("All cameras") },
                        )
                    }
                    items(page.cameras, key = { it.name }) { camera ->
                        FilterChip(
                            selected = state.scope == camera.name,
                            onClick = { viewModel.show(camera.name) },
                            label = { Text(camera.title) },
                        )
                    }
                }

                if (!page.admin) {
                    Text(
                        text = "Only an admin can change these.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                FlagCard(
                    title = "Alerts",
                    on = shown.alertsOn,
                    summary = shown.alertLabels.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { it.labelName() }
                        ?: "Nothing",
                    detail = "Settings for which tracked objects generate alerts.",
                    changeable = page.admin && !state.busy,
                    onToggle = viewModel::setAlertsOn,
                    onChange = { picking = ReviewLabels.Alerts },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                FlagCard(
                    title = "Detections",
                    on = shown.detectionsOn,
                    summary = when {
                        shown.detectionLabels == null -> "Everything else"
                        shown.detectionLabels.isEmpty() -> "Nothing"
                        else -> shown.detectionLabels.joinToString(", ") { it.labelName() }
                    },
                    detail = "Settings for which tracked objects generate detections " +
                        "(non-alert).",
                    changeable = page.admin && !state.busy,
                    onToggle = viewModel::setDetectionsOn,
                    onChange = { picking = ReviewLabels.Detections },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FlagCard(
    title: String,
    on: Boolean,
    summary: String,
    detail: String,
    changeable: Boolean,
    onToggle: (Boolean) -> Unit,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = on, onCheckedChange = onToggle, enabled = changeable)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (changeable) {
                    TextButton(onClick = onChange) { Text("Change") }
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * The objects to choose from, with a search box. Nothing ticked on detections
 * means every other object, which is why Done stays available there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelPicker(
    kind: ReviewLabels,
    objects: List<String>,
    tracked: Set<String>,
    initial: List<String>,
    onDone: (List<String>) -> Unit,
    onBack: () -> Unit,
) {
    var chosen by remember(kind, initial) { mutableStateOf(initial.toSet()) }
    var query by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    val text = query.trim()
    val listed = remember(objects, text) {
        if (text.isEmpty()) objects else objects.filter { it.contains(text, ignoreCase = true) }
    }
    val nothingTicked = chosen.isEmpty()

    fun toggle(label: String) {
        chosen = if (label in chosen) chosen - label else chosen + label
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (kind == ReviewLabels.Alerts) "Alert labels" else "Detection labels") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(
                    onClick = { onDone(objects.filter { it in chosen }) },
                    enabled = kind == ReviewLabels.Detections || !nothingTicked,
                ) {
                    Text("Done")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        val hint = when {
            !nothingTicked -> null
            kind == ReviewLabels.Alerts -> "Pick at least one, or turn Alerts off."
            else -> "None ticked means every other object."
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (kind == ReviewLabels.Alerts) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (listed.isEmpty()) {
            Text(
                text = "Nothing here goes by that name.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(listed, key = { it }) { label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggle(label) }
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = label in chosen,
                        onCheckedChange = { toggle(label) },
                    )
                    Text(
                        text = label.labelName(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (label in tracked) {
                        Text(
                            text = "Tracked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** `fire_alarm` said the way a person would. */
private fun String.labelName(): String = replace('_', ' ')
    .replaceFirstChar { first -> first.uppercase() }
