package nz.mckenzie.frigo.ui.reviews

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CameraMenu
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.relativeTime

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
            title = { Text(stringResource(R.string.revset_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
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
                    text = state.error ?: stringResource(R.string.revset_cant_read),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) {
                    Text(stringResource(R.string.revset_try_again))
                }
            }

            shown == null -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.revset_camera_own),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> {
                val changeable = page.admin && !state.busy && page.readAt == null

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The phone's own copy, so the page still opens while the server
                    // is away. Nothing can be sent, so nothing can be tapped.
                    page.readAt?.let { at ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.revset_stale,
                                    relativeTime(at).lowercase(),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = attention(),
                            )
                            TextButton(onClick = viewModel::load) {
                                Text(stringResource(R.string.revset_try_again))
                            }
                        }
                    }

                    CameraMenu(
                        cameras = page.cameras,
                        chosen = state.scope,
                        onChoose = viewModel::show,
                    )

                    if (!page.admin) {
                        Text(
                            text = stringResource(R.string.err_not_admin),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    state.error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Group(stringResource(R.string.review_kind_alerts)) {
                        Item(
                            icon = FrigoIcons.Bell,
                            title = stringResource(R.string.action_enable),
                            onClick = if (changeable) {
                                { viewModel.setAlertsOn(!shown.alertsOn) }
                            } else {
                                null
                            },
                            trailing = {
                                Switch(
                                    checked = shown.alertsOn,
                                    onCheckedChange = null,
                                    enabled = changeable,
                                )
                            },
                        )
                        CardDivider()
                        Item(
                            icon = FrigoIcons.Tag,
                            title = stringResource(R.string.revset_objects),
                            value = shown.alertLabels.takeIf { it.isNotEmpty() }
                                ?.joinToString(", ") { it.labelName() }
                                ?: stringResource(R.string.revset_nothing),
                            onClick = if (changeable) {
                                { picking = ReviewLabels.Alerts }
                            } else {
                                null
                            },
                            trailing = if (changeable) ({ OpenMark() }) else null,
                        )
                    }

                    Group(stringResource(R.string.review_kind_detections)) {
                        Item(
                            icon = FrigoIcons.Eye,
                            title = stringResource(R.string.action_enable),
                            onClick = if (changeable) {
                                { viewModel.setDetectionsOn(!shown.detectionsOn) }
                            } else {
                                null
                            },
                            trailing = {
                                Switch(
                                    checked = shown.detectionsOn,
                                    onCheckedChange = null,
                                    enabled = changeable,
                                )
                            },
                        )
                        CardDivider()
                        Item(
                            icon = FrigoIcons.Tag,
                            title = stringResource(R.string.revset_objects),
                            value = when {
                                shown.detectionLabels == null ->
                                    stringResource(R.string.revset_everything_else)

                                shown.detectionLabels.isEmpty() ->
                                    stringResource(R.string.revset_nothing)

                                else -> shown.detectionLabels.joinToString(", ") { it.labelName() }
                            },
                            onClick = if (changeable) {
                                { picking = ReviewLabels.Detections }
                            } else {
                                null
                            },
                            trailing = if (changeable) ({ OpenMark() }) else null,
                        )
                    }

                    if (state.scope == null) {
                        Text(
                            text = stringResource(R.string.revset_all_cameras),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
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
            title = {
                val name = if (kind == ReviewLabels.Alerts) {
                    R.string.revset_alert_labels
                } else {
                    R.string.revset_detection_labels
                }
                Text(stringResource(name))
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                TextButton(
                    onClick = { onDone(objects.filter { it in chosen }) },
                    enabled = kind == ReviewLabels.Detections || !nothingTicked,
                ) {
                    Text(stringResource(R.string.revset_done))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.revset_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        // A label the server can't report is not offered, so the page says what the
        // list is rather than leaving a missing object unexplained.
        Text(
            text = stringResource(R.string.revset_only_detected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val hint = when {
            !nothingTicked -> null
            kind == ReviewLabels.Alerts -> stringResource(R.string.revset_alerts_hint)
            else -> stringResource(R.string.revset_detections_hint)
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

        if (listed.isEmpty() && text.isNotEmpty()) {
            Text(
                text = stringResource(R.string.revset_no_match),
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
                            text = stringResource(R.string.revset_tracked),
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
