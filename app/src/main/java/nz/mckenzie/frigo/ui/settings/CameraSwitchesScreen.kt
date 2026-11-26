package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.CameraSwitch
import nz.mckenzie.frigo.data.Light
import nz.mckenzie.frigo.data.label
import nz.mckenzie.frigo.data.lightName
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.friendlyMessage
import nz.mckenzie.frigo.ui.login.Field
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
    var renaming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // This phone's own name for the camera, where it has one, and the name the
    // server gave it. The page says it the phone's way; every address behind it is
    // still built from the server's name.
    val list by container.repository.cameraList.collectAsStateWithLifecycle()
    val homeAssistant by container.repository.homeAssistant.collectAsStateWithLifecycle()
    val serverName = state.title
    val title = list.name(cameraName, serverName)
    val ownName = list.names[cameraName].orEmpty()
    // The light in Home Assistant that goes with this camera, if one has been
    // picked, and the list offered while the picker is open.
    val light = list.light(cameraName)
    var picking by remember { mutableStateOf(false) }
    var lights by remember { mutableStateOf<List<Light>?>(null) }
    var lightsProblem by remember { mutableStateOf<String?>(null) }
    // A house can have a lot of lights, so the list is searched rather than
    // scrolled. It starts empty each time the picker opens.
    var lightSearch by remember { mutableStateOf("") }

    LaunchedEffect(picking) {
        if (!picking) return@LaunchedEffect
        lights = null
        lightsProblem = null
        lightSearch = ""
        runCatching { container.repository.lightsFor(cameraName, title) }
            .onSuccess { lights = it }
            .onFailure { lightsProblem = it.friendlyMessage(context) }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
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

                // What this phone calls the camera. It sits above the server's own
                // switches because it is what the page, and the rest of the app,
                // says the camera is called.
                Group(stringResource(R.string.camsw_name_heading)) {
                    Item(
                        icon = FrigoIcons.Tag,
                        title = stringResource(R.string.camsw_name),
                        value = title,
                        onClick = {
                            typed = title
                            renaming = true
                        },
                        trailing = { OpenMark() },
                    )

                    CardDivider()

                    // Frigate has nothing to say about a floodlight, so which light
                    // in the house belongs to this camera is a choice of this
                    // phone's, kept beside its name for the camera.
                    Item(
                        icon = FrigoIcons.Floodlight,
                        title = stringResource(R.string.camsw_floodlight),
                        value = light?.let { lightName(it) }
                            ?: stringResource(R.string.camsw_floodlight_none),
                        onClick = { picking = true },
                        trailing = { OpenMark() },
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
            text = { Text(stringResource(R.string.camsw_stop_detail, title)) },
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

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.camsw_name_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.camsw_name_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextField(
                        value = typed,
                        onValueChange = { typed = it },
                        placeholder = { Text(serverName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Only offered once there is a name of this phone's own to go
                    // back from.
                    if (ownName.isNotBlank()) {
                        TextButton(
                            onClick = {
                                renaming = false
                                scope.launch { container.repository.setCameraName(cameraName, "") }
                            },
                            modifier = Modifier.padding(start = 0.dp),
                        ) {
                            Text(stringResource(R.string.camsw_name_use_server))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = false
                        scope.launch { container.repository.setCameraName(cameraName, typed) }
                    },
                ) {
                    Text(stringResource(R.string.camsw_name_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.camsw_cancel))
                }
            },
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.camsw_floodlight_pick)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.camsw_floodlight_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        homeAssistant == null -> Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.camsw_floodlight_set_up),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(R.string.settings_home_assistant_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        lightsProblem != null -> Text(
                            text = lightsProblem.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        lights == null -> Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }

                        lights?.isEmpty() == true -> Text(
                            text = stringResource(R.string.camsw_no_lights),
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // A house can have a lot of lights, and a list of them is
                            // long. The search narrows it as you type, by the name
                            // Home Assistant gave or by the entity's own id.
                            Field(
                                value = lightSearch,
                                onValueChange = { lightSearch = it },
                                label = stringResource(R.string.camsw_light_search),
                                icon = FrigoIcons.Search,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            )
                            val found = lights.orEmpty().filter { it.matches(lightSearch) }
                            Column(
                                modifier = Modifier
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                LightRow(
                                    name = stringResource(R.string.camsw_floodlight_none),
                                    chosen = light == null,
                                    onClick = {
                                        picking = false
                                        scope.launch {
                                            container.repository.setCameraLight(cameraName, null)
                                        }
                                    },
                                )
                                if (found.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.logs_no_match),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    )
                                }
                                found.forEach { found ->
                                    LightRow(
                                        name = found.label(lights.orEmpty()),
                                        chosen = found.id == light,
                                        onClick = {
                                            picking = false
                                            scope.launch {
                                                container.repository.setCameraLight(cameraName, found.id)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.camsw_cancel))
                }
            },
        )
    }
}

/**
 * Whether a light matches what has been typed: its name as Home Assistant gives it,
 * or the entity's own id with the underscores read as spaces.
 */
private fun Light.matches(query: String): Boolean {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return true
    val said = id.lowercase().replace('_', ' ')
    return name.lowercase().contains(wanted) || said.contains(wanted)
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

/**
 * One light in the picker, with a tick on the one this camera has.
 *
 * The name is Home Assistant's own, which is the name that is on the box in the
 * house rather than one the app made up.
 */
@Composable
private fun LightRow(name: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (chosen) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
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
