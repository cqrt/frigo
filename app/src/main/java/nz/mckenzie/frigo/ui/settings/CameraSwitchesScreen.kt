package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.CameraSwitch
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.relativeTime

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
    val context = LocalContext.current

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.title) },
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
                    text = state.error ?: stringResource(R.string.camsw_cant_read),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) {
                    Text(stringResource(R.string.camsw_try_again))
                }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The phone's own copy, so you can see what is set while the server
                // is away. Nothing can be sent, so nothing can be tapped.
                val stale = page.readAt
                if (stale != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(
                                R.string.camsw_stale,
                                relativeTime(stale).lowercase(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = attention(),
                        )
                        TextButton(onClick = viewModel::load) {
                            Text(stringResource(R.string.camsw_try_again))
                        }
                    }
                }

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

                groups.forEach { (heading, listed) ->
                    Group(stringResource(heading)) {
                        listed.forEachIndexed { index, switch ->
                            if (index > 0) CardDivider()
                            val off = page.blocked(context, switch)
                            SwitchRow(
                                switch = switch,
                                on = switches?.get(switch) ?: false,
                                changeable = page.admin && off == null && stale == null,
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

                if (stale == null) {
                    Text(
                        text = stringResource(R.string.camsw_changes_save),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }

    if (stopping) {
        AlertDialog(
            onDismissRequest = { stopping = false },
            title = { Text(stringResource(R.string.camsw_stop_question)) },
            text = { Text(stringResource(R.string.camsw_stop_detail, state.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stopping = false
                        viewModel.set(CameraSwitch.Recording, false)
                    },
                ) {
                    Text(stringResource(R.string.camsw_stop))
                }
            },
            dismissButton = {
                TextButton(onClick = { stopping = false }) {
                    Text(stringResource(R.string.camsw_cancel))
                }
            },
        )
    }
}

/** The eight switches, in the order they sit on the page, and each group's heading. */
private val groups: List<Pair<Int, List<CameraSwitch>>> = listOf(
    R.string.camsw_group_detection to listOf(
        CameraSwitch.ObjectDetection,
        CameraSwitch.MotionDetection,
        CameraSwitch.AudioDetection,
    ),
    R.string.camsw_group_recording to listOf(CameraSwitch.Recording, CameraSwitch.Snapshots),
    R.string.camsw_group_live_view to listOf(CameraSwitch.Birdseye),
    R.string.camsw_group_recognition to listOf(
        CameraSwitch.FaceRecognition,
        CameraSwitch.LicensePlate,
    ),
)

/** One setting, its switch on the right, and a line under it when it can't be moved. */
@Composable
private fun SwitchRow(
    switch: CameraSwitch,
    on: Boolean,
    changeable: Boolean,
    note: String?,
    onToggle: (Boolean) -> Unit,
) {
    Item(
        icon = switch.icon,
        title = stringResource(switch.labelRes),
        supporting = note,
        onClick = if (changeable) ({ onToggle(!on) }) else null,
        trailing = {
            Switch(checked = on, onCheckedChange = null, enabled = changeable)
        },
    )
}

/** What each switch looks like at the start of its line. */
private val CameraSwitch.icon: ImageVector
    get() = when (this) {
        CameraSwitch.ObjectDetection -> FrigoIcons.Eye
        CameraSwitch.MotionDetection -> FrigoIcons.Motion
        CameraSwitch.Recording -> FrigoIcons.Camera
        CameraSwitch.Snapshots -> FrigoIcons.Photo
        CameraSwitch.AudioDetection -> FrigoIcons.Sound
        CameraSwitch.Birdseye -> FrigoIcons.Grid
        CameraSwitch.FaceRecognition -> FrigoIcons.Face
        CameraSwitch.LicensePlate -> FrigoIcons.Car
    }
