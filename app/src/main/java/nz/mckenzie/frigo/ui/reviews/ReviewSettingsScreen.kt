package nz.mckenzie.frigo.ui.reviews

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.SearchField
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.components.prettyLabel
import nz.mckenzie.frigo.ui.relativeTime

/**
 * The page behind the tune icon on Review: which objects make an alert, which
 * make a detection, and whether either is made at all.
 *
 * Frigate keeps these in its own settings file and applies a change as it saves,
 * so the next alert follows it. One camera is set at a time, as Frigate's own
 * settings do it: a list that covered every camera would offer objects some of
 * them can't report.
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
            modifier = modifier,
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
                        all = false,
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
                                ?.joinToString(", ") { prettyLabel(it) }
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

                                else -> shown.detectionLabels.joinToString(", ") { prettyLabel(it) }
                            },
                            onClick = if (changeable) {
                                { picking = ReviewLabels.Detections }
                            } else {
                                null
                            },
                            trailing = if (changeable) ({ OpenMark() }) else null,
                        )
                    }
                }
            }
        }
    }
}


/**
 * The labels to choose from: a search box, then a row a checkbox in, drawn the way
 * every other settings page is.
 *
 * Nothing ticked on detections means every other object, which is why Done stays
 * available there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelPicker(
    kind: ReviewLabels,
    objects: List<String>,
    tracked: Set<String>,
    initial: List<String>,
    modifier: Modifier = Modifier,
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

    Column(modifier.fillMaxSize()) {
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SearchField(value = query, onValueChange = { query = it })

            Group(stringResource(R.string.reviews_labels)) {
                // A label the server can't report is not offered, so the page says
                // what the list is rather than leaving a missing object unexplained.
                Note(stringResource(R.string.revset_only_detected))

                listed.forEachIndexed { index, label ->
                    if (index > 0) CardDivider()
                    // The row does the ticking, so a tap anywhere on it counts and
                    // every row is the same height.
                    Item(
                        icon = FrigoIcons.Tag,
                        title = prettyLabel(label),
                        onClick = { toggle(label) },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (label in tracked) {
                                    Text(
                                        text = stringResource(R.string.revset_tracked),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Checkbox(
                                    checked = label in chosen,
                                    onCheckedChange = null,
                                )
                            }
                        },
                    )
                }

                if (listed.isEmpty() && text.isNotEmpty()) {
                    CardDivider()
                    Note(stringResource(R.string.revset_no_match))
                }

                // Nothing ticked is a state Done won't write for alerts, so the card
                // says what to do about it rather than leaving the button greyed with
                // no reason. On detections it is a reading, not a warning.
                val hint = when {
                    !nothingTicked -> null
                    kind == ReviewLabels.Alerts -> stringResource(R.string.revset_alerts_hint)
                    else -> stringResource(R.string.revset_detections_hint)
                }
                if (hint != null) {
                    CardDivider()
                    Note(
                        text = hint,
                        colour = if (kind == ReviewLabels.Alerts) {
                            MaterialTheme.colorScheme.error
                        } else {
                            null
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}