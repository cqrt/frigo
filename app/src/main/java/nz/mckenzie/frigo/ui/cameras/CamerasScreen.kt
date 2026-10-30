package nz.mckenzie.frigo.ui.cameras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.components.CameraStill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasScreen(
    container: AppContainer,
    onOpenCamera: (Camera) -> Unit,
) {
    val viewModel: CamerasViewModel = viewModel(factory = CamerasViewModel.factory(container))
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Cameras") },
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
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

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(cameras, key = { it.name }) { camera ->
                        CameraTile(container.repository, camera) { onOpenCamera(camera) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    repository: FrigoRepository,
    camera: Camera,
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
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant),
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
