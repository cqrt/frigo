package nz.mckenzie.frigo.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ServerPrefs
import nz.mckenzie.frigo.data.Update
import nz.mckenzie.frigo.data.Updater
import nz.mckenzie.frigo.data.askToInstallApps
import nz.mckenzie.frigo.data.canInstallApps
import nz.mckenzie.frigo.data.installUpdate
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.theme.ThemeMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: FrigoRepository,
    updater: Updater,
    baseUrl: String,
    username: String,
    modifier: Modifier = Modifier,
    onOpenCamera: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alerts by repository.alerts.collectAsStateWithLifecycle()
    val liveTiles by repository.liveTiles.collectAsStateWithLifecycle(initialValue = false)
    val theme by repository.theme.collectAsStateWithLifecycle(
        initialValue = ThemeMode.System.ordinal,
    )

    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var blocked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { repository.cameras() }.onSuccess { cameras = it }
        runCatching { repository.labels() }.onSuccess { labels = it }
    }

    fun save(next: ServerPrefs.Alerts) {
        scope.launch { repository.setAlerts(next) }
    }

    // The first time you switch this on, everything is ticked: one thing to
    // narrow down rather than an empty list that buzzes for nothing.
    fun switchOn() {
        val pickedCameras = alerts.cameras.ifEmpty { cameras.map { it.name }.toSet() }
        val pickedLabels = alerts.labels.ifEmpty {
            if ("person" in labels) setOf("person") else labels.toSet()
        }
        save(alerts.copy(on = true, cameras = pickedCameras, labels = pickedLabels))
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        blocked = !granted
        if (granted) switchOn()
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleSmall)
            Row(label = "Address", value = baseUrl)
            Row(label = "Signed in as", value = username)

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Notifications", style = MaterialTheme.typography.titleSmall)

            Text(
                text = "You'll be alerted when Frigate spots something. Tap the notification for live view.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Enable(
                checked = alerts.on,
                onCheckedChange = { wanted ->
                    blocked = false
                    if (!wanted) {
                        save(alerts.copy(on = false))
                        return@Enable
                    }
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        switchOn()
                    }
                },
            )

            if (blocked) {
                Text(
                    text = "Notifications are off for Frigo in Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Pick(
                title = "Cameras",
                options = cameras.map { it.name to it.title },
                chosen = alerts.cameras,
                onToggle = { name, ticked ->
                    val next = if (ticked) alerts.cameras + name else alerts.cameras - name
                    save(alerts.copy(cameras = next))
                },
            )

            Pick(
                title = "Objects",
                options = labels.map { it to it.replaceFirstChar { c -> c.uppercase() } },
                chosen = alerts.labels,
                onToggle = { name, ticked ->
                    val next = if (ticked) alerts.labels + name else alerts.labels - name
                    save(alerts.copy(labels = next))
                },
            )

            if (alerts.on && (alerts.cameras.isEmpty() || alerts.labels.isEmpty())) {
                Text(
                    text = "Nothing ticked in one list, so nothing will buzz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = "Frigo runs in the background to keep the alerts coming. To prevent " +
                    "Android from shutting down Frigo to save battery, tap below to turn off " +
                    "battery optimization for Frigo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) {
                Text("Battery Settings")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Live View", style = MaterialTheme.typography.titleSmall)

            Text(
                text = "Show live video on the Cameras tab. Will require more device and " +
                    "network resources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Enable(
                checked = liveTiles,
                onCheckedChange = { on -> scope.launch { repository.setLiveTiles(on) } },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Cameras", style = MaterialTheme.typography.titleSmall)

            cameras.forEach { camera ->
                CameraRow(title = camera.title) { onOpenCamera(camera.name) }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Appearance", style = MaterialTheme.typography.titleSmall)

            Text(
                text = "Theme",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode.ordinal,
                        onClick = { scope.launch { repository.setTheme(mode.ordinal) } },
                        label = { Text(mode.label()) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Updates", style = MaterialTheme.typography.titleSmall)

            Text(
                text = "Frigo ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )

            // Worked out before the branch below, so each of them is one value the
            // screen can hold on to rather than something that can change under it.
            val newer = release?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME) }
            val apk = ready

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
                    Button(onClick = { install(apk) }, modifier = Modifier.fillMaxWidth()) {
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
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(newer.page))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) {
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

            // Said on its own line rather than in place of the version, so a look
            // that failed once does not hide a download that is already here.
            if (lookProblem != null) {
                Text(
                    text = lookProblem.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (needsPermission) {
                Text(
                    text = "Android needs your permission to install Frigo. Turn on Allow " +
                        "from this source, then tap Install again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!looking && !fetching && (newer == null || apk != null)) {
                TextButton(onClick = { look(fresh = true) }) { Text("Check again") }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Button(
                onClick = { scope.launch { repository.signOut() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }
        }
    }
}

/** A camera's name with a chevron, opening that camera's switches. */
@Composable
private fun CameraRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = FrigoIcons.Chevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An "Enable" label with its switch on the right. */@Composable
private fun Enable(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Enable",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A heading and a row of chips that tick and untick one thing each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pick(
    title: String,
    options: List<Pair<String, String>>,
    chosen: Set<String>,
    onToggle: (name: String, ticked: Boolean) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (name, shown) ->
                val ticked = name in chosen
                FilterChip(
                    selected = ticked,
                    onClick = { onToggle(name, !ticked) },
                    label = { Text(shown) },
                )
            }
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
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
