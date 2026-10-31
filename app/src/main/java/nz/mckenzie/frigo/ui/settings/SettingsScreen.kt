package nz.mckenzie.frigo.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.BuildConfig
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ServerPrefs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: FrigoRepository,
    baseUrl: String,
    username: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alerts by repository.alerts.collectAsStateWithLifecycle()
    val liveTiles by repository.liveTiles.collectAsStateWithLifecycle(initialValue = false)

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

            Text("Alerts", style = MaterialTheme.typography.titleSmall)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Enable",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = alerts.on,
                    onCheckedChange = { wanted ->
                        blocked = false
                        if (!wanted) {
                            save(alerts.copy(on = false))
                            return@Switch
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
            }

            if (alerts.on) {
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

                if (alerts.cameras.isEmpty() || alerts.labels.isEmpty()) {
                    Text(
                        text = "Nothing ticked in one list, so nothing will buzz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) {
                    Text("Battery settings")
                }
            }

            if (blocked) {
                Text(
                    text = "Notifications are off for Frigo in Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Live pictures",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = liveTiles,
                    onCheckedChange = { on -> scope.launch { repository.setLiveTiles(on) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Button(
                onClick = { scope.launch { repository.signOut() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }

            Text(
                text = "Frigo ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
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
