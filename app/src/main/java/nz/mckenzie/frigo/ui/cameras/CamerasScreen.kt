package nz.mckenzie.frigo.ui.cameras

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CameraStill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onOpenCamera: (Camera) -> Unit,
) {
    val viewModel: CamerasViewModel = viewModel(factory = CamerasViewModel.factory(container))
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val refreshKey by viewModel.refreshKey.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Cameras") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh the pictures")
                    }
                }
                LayoutButton(layout, viewModel::cycleLayout)
            },
        )

        Box(Modifier.fillMaxSize()) {
            when {
                loading && cameras.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                error != null && cameras.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) { Text("Try again") }
                }

                cameras.isEmpty() -> Text(
                    text = "No cameras are turned on in Frigate.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                layout == CameraLayout.Grid -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cameras, key = { it.name }) { camera ->
                        CameraTile(container.repository, camera, refreshKey) { onOpenCamera(camera) }
                    }
                }

                layout == CameraLayout.List -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cameras, key = { it.name }) { camera ->
                        CameraRow(container.repository, camera, refreshKey) { onOpenCamera(camera) }
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cameras, key = { it.name }) { camera ->
                        CameraWide(container.repository, camera, refreshKey) { onOpenCamera(camera) }
                    }
                }
            }
        }
    }
}

/**
 * The button that changes the layout. Its icon is the look you get next, not the
 * one you are looking at, so a tap always takes you somewhere you can see coming.
 */
@Composable
private fun LayoutButton(layout: CameraLayout, onClick: () -> Unit) {
    val icon: ImageVector
    val said: String
    when (layout) {
        CameraLayout.Grid -> {
            icon = FrigoIcons.Rows
            said = "Show one to a line"
        }
        CameraLayout.List -> {
            icon = FrigoIcons.Wide
            said = "Show one to a row"
        }
        CameraLayout.Wide -> {
            icon = FrigoIcons.Grid
            said = "Show as a grid"
        }
    }
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = said)
    }
}

@Composable
private fun CameraTile(
    repository: FrigoRepository,
    camera: Camera,
    refreshKey: Int,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            CameraStill(
                repository = repository,
                camera = camera.name,
                refreshKey = refreshKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Text(
                text = camera.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** The same camera on one line: a small picture and its name. */
@Composable
private fun CameraRow(
    repository: FrigoRepository,
    camera: Camera,
    refreshKey: Int,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CameraStill(
                repository = repository,
                camera = camera.name,
                refreshKey = refreshKey,
                modifier = Modifier
                    .padding(8.dp)
                    .size(width = 128.dp, height = 72.dp),
            )
            Text(
                text = camera.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
        }
    }
}

/**
 * One camera filling the width, with its name written on the picture. The name
 * sits on a dark fade, because a camera pointed at a bright wall would otherwise
 * leave it unreadable.
 */
@Composable
private fun CameraWide(
    repository: FrigoRepository,
    camera: Camera,
    refreshKey: Int,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            CameraStill(
                repository = repository,
                camera = camera.name,
                refreshKey = refreshKey,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                        )
                    ),
            )
            Text(
                text = camera.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
