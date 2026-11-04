package nz.mckenzie.frigo.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.BuildConfig
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.CameraState
import nz.mckenzie.frigo.data.CameraSwitchPage
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ServerPrefs
import nz.mckenzie.frigo.data.Update
import nz.mckenzie.frigo.data.Updater
import nz.mckenzie.frigo.data.askToInstallApps
import nz.mckenzie.frigo.data.canInstallApps
import nz.mckenzie.frigo.data.cameraState
import nz.mckenzie.frigo.data.installUpdate
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.openBatterySettings
import nz.mckenzie.frigo.ui.relativeTime
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.theme.ThemeMode
import java.io.File

/**
 * The page is a stack of groups: a small heading, and a card under it holding
 * the rows. Every group is one thing you came here to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: FrigoRepository,
    updater: Updater,
    baseUrl: String,
    modifier: Modifier = Modifier,
    onOpenCamera: (String) -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onAddServer: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alerts by repository.alerts.collectAsStateWithLifecycle()
    val rules by repository.alertRules.collectAsStateWithLifecycle()
    val servers by repository.servers.collectAsStateWithLifecycle()
    val liveTiles by repository.liveTiles.collectAsStateWithLifecycle(initialValue = false)
    val theme by repository.theme.collectAsStateWithLifecycle(
        initialValue = ThemeMode.System.ordinal,
    )

    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var switches by remember { mutableStateOf<CameraSwitchPage?>(null) }
    var blocked by remember { mutableStateOf(false) }
    // The server couldn't be asked, so the list below is nothing rather than empty.
    var offline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // One trip answers both: the cameras to list, and what each camera has
        // switched off.
        runCatching { repository.cameraSwitchPage() }
            .onSuccess { page ->
                switches = page
                cameras = page.cameras
            }
            .onFailure {
                offline = true
                runCatching { repository.cameras() }.onSuccess { cameras = it }
            }
    }

    fun save(next: ServerPrefs.Alerts) {
        scope.launch { repository.setAlerts(next) }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        blocked = !granted
        if (granted) save(alerts.copy(on = true))
    }

    // What GitHub says about the newest Frigo, asked for each time this page is
    // opened: the answer is only worth having while you are looking at it.
    var release by remember { mutableStateOf<Update?>(null) }
    var looking by remember { mutableStateOf(true) }
    var looked by remember { mutableStateOf(false) }
    var lookProblem by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }

    fun look(fresh: Boolean = false) {
        scope.launch {
            looking = true
            lookProblem = null
            val asked = runCatching { updater.latest(fresh) }
            looking = false
            asked
                .onSuccess { found ->
                    lookProblem = null
                    looked = true
                    release = found
                    // A file fetched on an earlier visit is still there, so a second
                    // look does not fetch it again.
                    ready = found
                        ?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME) }
                        ?.let { updater.downloaded(it.version) }
                }
                .onFailure {
                    lookProblem = "Couldn't reach GitHub. Check your connection and try again."
                }
        }
    }

    fun fetch(wanted: Update) {
        scope.launch {
            fetching = true
            percent = 0
            runCatching {
                updater.download(wanted) { got, total ->
                    percent = if (total > 0) (got * 100 / total).toInt() else 0
                }
            }
                .onSuccess { ready = it }
                .onFailure {
                    lookProblem = "Couldn't fetch it. Check your connection and try again."
                }
            fetching = false
        }
    }

    fun install(apk: File) {
        // Android keeps this switch itself, and nothing in the app can grant it.
        if (!canInstallApps(context)) {
            needsPermission = true
            askToInstallApps(context)
            return
        }
        needsPermission = false
        runCatching { installUpdate(context, apk) }
            .onFailure { lookProblem = "Android wouldn't open the installer." }
    }

    // Android keeps the switch itself, so the note about it goes away by itself once
    // the switch has been turned on and you come back.
    LifecycleResumeEffect(Unit) {
        if (needsPermission && canInstallApps(context)) needsPermission = false
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) { look() }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("Servers") {
                servers.forEachIndexed { index, server ->
                    if (index > 0) CardDivider()
                    val here = server.baseUrl == baseUrl
                    Item(
                        icon = FrigoIcons.Server,
                        title = server.baseUrl.shortName(),
                        value = server.username,
                        // Tapping the one you are on does nothing, so it isn't a tap.
                        onClick = if (here) {
                            null
                        } else {
                            { scope.launch { repository.switchTo(server.baseUrl) } }
                        },
                        trailing = {
                            if (here) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Signed in",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                OpenMark()
                            }
                        },
                    )
                }

                if (servers.isNotEmpty()) CardDivider()

                Item(
                    icon = Icons.Filled.Add,
                    title = "Add a server",
                    onClick = onAddServer,
                )

                CardDivider()

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { scope.launch { repository.signOut() } }) {
                        Text(if (servers.size > 1) "Sign out of this server" else "Sign out")
                    }
                }
            }

            Group("Notifications") {
                Item(
                    icon = FrigoIcons.Bell,
                    title = "Enable",
                    supporting = "You'll be alerted when Frigate spots something. Tap the " +
                        "notification for live view.",
                    trailing = {
                        Switch(
                            checked = alerts.on,
                            onCheckedChange = { wanted ->
                                blocked = false
                                if (!wanted) {
                                    save(alerts.copy(on = false))
                                    return@Switch
                                }
                                val needsPermission =
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                if (needsPermission) {
                                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    save(alerts.copy(on = true))
                                }
                            },
                        )
                    },
                )

                if (blocked) {
                    Note(
                        text = "Notifications are off for Frigo in Android.",
                        colour = MaterialTheme.colorScheme.error,
                    )
                }

                CardDivider()

                // Temporary, while the two ways of buzzing are tried against your
                // own cameras. It goes once one of them has won.
                Item(
                    icon = FrigoIcons.Reviews,
                    title = "One buzz per happening",
                    supporting = if (alerts.perHappening) {
                        "Your server reports each alert once, so each one is one buzz."
                    } else {
                        "Every thing your server tracks is a buzz, as it happens."
                    },
                    onClick = { save(alerts.copy(perHappening = !alerts.perHappening)) },
                    trailing = {
                        Switch(
                            checked = alerts.perHappening,
                            onCheckedChange = null,
                        )
                    },
                )

                CardDivider()

                Item(
                    icon = FrigoIcons.Tune,
                    title = "Alerts and detections",
                    supporting = "Which cameras and objects buzz. These are your server's settings.",
                    onClick = onOpenAlerts,
                    trailing = { OpenMark() },
                )

                // Quiet without saying why would look broken. Two switches on the
                // server can silence everything, and both are reachable now.
                when {
                    !rules.notificationsOn -> Note(
                        text = "Notifications are off on your server, so nothing will buzz.",
                        colour = MaterialTheme.colorScheme.error,
                    )

                    rules.cameras.values.none { it.notify } -> Note(
                        text = "Every camera is off on your server's notifications page, " +
                            "so nothing will buzz.",
                        colour = MaterialTheme.colorScheme.error,
                    )
                }

                CardDivider()

                Note(
                    text = "Frigo runs in the background to keep the alerts coming. So that " +
                        "Android doesn't shut it down to save battery, turn off battery " +
                        "optimization for Frigo.",
                )

                TextButton(
                    onClick = { context.openBatterySettings() },
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                ) {
                    Text("Battery settings")
                }
            }

            Group("Live view") {
                Item(
                    icon = Icons.Filled.PlayArrow,
                    title = "Enable",
                    supporting = "Plays video on the Cameras tab. It uses more battery and data.",
                    trailing = {
                        Switch(
                            checked = liveTiles,
                            onCheckedChange = { on ->
                                scope.launch { repository.setLiveTiles(on) }
                            },
                        )
                    },
                )
            }

            Group("Cameras") {
                val stale = switches?.readAt
                when {
                    stale != null -> Note(
                        "Your server isn't answering. These are the cameras your phone " +
                            "last read, ${relativeTime(stale).lowercase()}.",
                    )

                    offline -> Note("Your server isn't answering, so the cameras can't be read.")
                    cameras.isEmpty() -> Note("No camera this account can see.")
                }
                cameras.forEachIndexed { index, camera ->
                    if (index > 0) CardDivider()
                    CameraRow(
                        title = camera.title,
                        state = switches?.cameraState(camera.name),
                        onClick = { onOpenCamera(camera.name) },
                    )
                }
            }

            Group("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = theme == mode.ordinal,
                                onClick = { scope.launch { repository.setTheme(mode.ordinal) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size,
                                ),
                            ) {
                                Text(mode.label())
                            }
                        }
                    }
                    Text(
                        text = "Follows the phone unless you pick one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }
            }

            Group("Updates") {
                // Worked out before the branches below, so each of them is one value
                // the screen can hold on to rather than something that can change
                // under it.
                val newer = release?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME) }
                val apk = ready

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Frigo ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    when {
                        fetching -> Text(
                            text = "Downloading… $percent%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        newer != null && apk != null -> {
                            Text(
                                text = "Frigo ${newer.version} is ready to install.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { install(apk) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Install")
                            }
                        }

                        newer != null -> {
                            Text(
                                text = "Frigo ${newer.version} is out.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { fetch(newer) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Download")
                            }
                            if (newer.page.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(newer.page))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    },
                                    modifier = Modifier.padding(start = 0.dp),
                                ) {
                                    Text("What's new")
                                }
                            }
                        }

                        looking -> Text(
                            text = "Looking for a newer version…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> if (looked) Text(
                            text = "This is the newest version.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Said on its own line rather than in place of the version, so a
                    // look that failed once does not hide a download already here.
                    if (lookProblem != null) {
                        Text(
                            text = lookProblem.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (needsPermission) {
                        Text(
                            text = "Android needs your permission to install Frigo. Turn on " +
                                "Allow from this source, then tap Install again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (!looking && !fetching && (newer == null || apk != null)) {
                        TextButton(
                            onClick = { look(fresh = true) },
                            modifier = Modifier.padding(start = 0.dp),
                        ) {
                            Text("Check again")
                        }
                    }
                }
            }

            Group("About") {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Frigo puts your Frigate cameras on your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Fact("Version", BuildConfig.VERSION_NAME)
                    Fact("Author", "Matt Mckenzie")
                    Fact("Copyright", "© 2026 Matt Mckenzie")
                    TextButton(
                        onClick = { scope.launch { repository.setOnboarded(false) } },
                        modifier = Modifier.padding(start = 0.dp),
                    ) {
                        Text("Show the welcome again")
                    }
                }
            }

            // Clear of the bottom of the page, so the last card is not against the bar.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A heading over a card, which is what one thing you can change looks like. */
@Composable
private fun CameraRow(
    title: String,
    state: CameraState?,
    onClick: () -> Unit,
) {
    Item(
        icon = FrigoIcons.Camera,
        title = title,
        supporting = state?.said,
        colour = if (state?.somethingOff == true) attention() else null,
        onClick = onClick,
        trailing = { OpenMark() },
    )
}

/** A line of smaller print inside a card. */
@Composable
private fun Note(text: String, colour: Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = colour ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** A name and what it is set to, one over the other. */
@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the chip says. */
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Day -> "Day"
    ThemeMode.Night -> "Night"
}

/** The address without the scheme, which is the part that tells two servers apart. */
private fun String.shortName(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')
