package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.CameraSwitch

/**
 * What one camera does: whether it looks for objects, records, keeps snapshots,
 * listens for sounds, appears in the birdseye view, and recognises faces and
 * number plates.
 *
 * These are Frigate's own settings for the camera, saved on the server as you
 * tap and applied without a restart. Frigate's web page shows the same values.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSwitchesScreen(
    container: AppContainer,
    cameraName: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: CameraSwitchesViewModel = viewModel(
        key = cameraName,
        factory = CameraSwitchesViewModel.factory(container, cameraName),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page
    val switches = state.switches
    var stopping by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.title) },
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
                    text = state.error ?: "Can't read this camera's settings.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) { Text("Try again") }
            }

            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!page.admin) {
                    Text(
                        text = "Only an admin can change these.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                CameraSwitch.entries.forEach { switch ->
                    val off = page.blocked(switch)
                    SwitchRow(
                        label = switch.label,
                        on = switches?.get(switch) ?: false,
                        changeable = page.admin && off == null,
                        note = off,
                        onToggle = { wanted ->
                            // Recording is the one that loses something when it
                            // stops, so it asks first.
                            if (switch == CameraSwitch.Recording && !wanted) {
                                stopping = true
                            } else {
                                viewModel.set(switch, wanted)
                            }
                        },
                    )
                }
            }
        }
    }

    if (stopping) {
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text("Stop recording?") },
            text = { Text("${state.title} stops saving video. What's already saved stays.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        stopping = false
                        viewModel.set(CameraSwitch.Recording, false)
                    },
                ) {
                    Text("Stop")
                }
            },
            dismissButton = {
                TextButton(onClick = { stopping = false }) { Text("Cancel") }
            },
        )
    }
}

/** One setting, its switch on the right, and a line under it when it can't be moved. */
@Composable
private fun SwitchRow(
    label: String,
    on: Boolean,
    changeable: Boolean,
    note: String?,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = on,
                enabled = changeable,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = on, onCheckedChange = null, enabled = changeable)
    }
}
