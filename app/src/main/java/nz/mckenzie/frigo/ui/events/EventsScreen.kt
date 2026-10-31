package nz.mckenzie.frigo.ui.events

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.CachedImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: EventsViewModel = viewModel(factory = EventsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<FrigateEvent?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Events") },
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
                        selected = state.selectedCamera == null,
                        onClick = { viewModel.selectCamera(null) },
                        label = { Text("All") },
                    )
                }
                items(state.cameras, key = { it.name }) { camera ->
                    FilterChip(
                        selected = state.selectedCamera == camera.name,
                        onClick = { viewModel.selectCamera(camera.name) },
                        label = { Text(camera.title) },
                    )
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

                state.events.isEmpty() -> Text(
                    text = "Nothing recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

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
        EventPlayerDialog(
            repository = container.repository,
            event = event,
            onDismiss = { opened = null },
        )
    }
}

@Composable
private fun EventRow(
    repository: nz.mckenzie.frigo.data.FrigoRepository,
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

internal fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis < 60_000) return "Just now"
    return DateUtils.getRelativeTimeSpanString(
        millis,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}
