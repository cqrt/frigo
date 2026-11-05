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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.components.CameraMenu
import nz.mckenzie.frigo.ui.components.ChoiceChip
import nz.mckenzie.frigo.ui.components.DayDialog
import nz.mckenzie.frigo.ui.components.PeriodChip
import nz.mckenzie.frigo.ui.components.prettyLabel
import nz.mckenzie.frigo.ui.relativeTime

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
            title = { Text(stringResource(R.string.explore_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                CameraMenu(
                    cameras = state.cameras,
                    chosen = state.camera,
                    onChoose = viewModel::selectCamera,
                )
            }
            item {
                ChoiceChip(
                    title = stringResource(R.string.explore_labels),
                    choices = state.labels,
                    chosen = state.chosenLabels,
                    name = ::prettyLabel,
                    onToggle = viewModel::toggleLabel,
                    empty = stringResource(R.string.explore_no_labels),
                )
            }
            item {
                ChoiceChip(
                    title = stringResource(R.string.explore_people),
                    choices = state.subLabels,
                    chosen = state.chosenSubLabels,
                    name = { it },
                    onToggle = viewModel::toggleSubLabel,
                    empty = stringResource(R.string.explore_no_people),
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
                    TextButton(onClick = viewModel::clearFilters) {
                        Text(stringResource(R.string.explore_clear))
                    }
                }
            }
        }

        PullToRefreshBox(
            // The full screen spinner takes over when there is nothing to show,
            // so the pull indicator only appears over a list that has content.
            isRefreshing = state.loading && state.events.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading && state.events.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.events.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.explore_try_again))
                    }
                }

                state.events.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (state.narrowed) {
                            stringResource(R.string.explore_nothing_for_filters)
                        } else {
                            stringResource(R.string.explore_nothing_yet)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.narrowed) {
                        Button(onClick = viewModel::clearFilters) {
                            Text(stringResource(R.string.explore_clear_filters))
                        }
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
                                    Button(onClick = viewModel::loadMore) {
                                        Text(stringResource(R.string.explore_load_older))
                                    }
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

@Composable
private fun EventRow(
    repository: FrigoRepository,
    event: FrigateEvent,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The same corners as the small camera tiles under the live picture.
            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp))) {
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
                            contentDescription = stringResource(R.string.explore_has_clip),
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
