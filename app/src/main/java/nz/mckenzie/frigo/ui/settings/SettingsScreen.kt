package nz.mckenzie.frigo.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.BuildConfig
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.CameraState
import nz.mckenzie.frigo.data.CameraSwitchPage
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.Language
import nz.mckenzie.frigo.data.ServerPrefs
import nz.mckenzie.frigo.data.Update
import nz.mckenzie.frigo.data.UpdateWatch
import nz.mckenzie.frigo.data.askToInstallApps
import nz.mckenzie.frigo.data.canInstallApps
import nz.mckenzie.frigo.data.cameraState
import nz.mckenzie.frigo.data.installUpdate
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.lock.rememberAskForLock
import nz.mckenzie.frigo.ui.lock.rememberLockAvailable
import nz.mckenzie.frigo.ui.openBatterySettings
import nz.mckenzie.frigo.ui.relativeTime
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.theme.ThemeMode
import java.io.File
import kotlin.math.abs

/**
 * The page is a stack of groups: a small heading, and a card under it holding
 * the rows. Every group is one thing you came here to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: FrigoRepository,
    updates: UpdateWatch,
    baseUrl: String,
    modifier: Modifier = Modifier,
    onOpenCamera: (String) -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenAi: () -> Unit = {},
    onOpenAlertObjects: () -> Unit = {},
    onOpenAlertPeople: () -> Unit = {},
    onOpenQuiet: () -> Unit = {},
    onOpenLanguage: () -> Unit = {},
    onOpenSystem: () -> Unit = {},
    onAddServer: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val alerts by repository.alerts.collectAsStateWithLifecycle()
    val rules by repository.alertRules.collectAsStateWithLifecycle()
    val quiet by repository.quietTimes.collectAsStateWithLifecycle()
    val objects by repository.alertObjects.collectAsStateWithLifecycle()
    val people by repository.alertPeople.collectAsStateWithLifecycle()
    val servers by repository.servers.collectAsStateWithLifecycle()
    val liveTiles by repository.liveTiles.collectAsStateWithLifecycle(initialValue = false)
    val theme by repository.theme.collectAsStateWithLifecycle(
        initialValue = ThemeMode.System.ordinal,
    )
    val lockOn by repository.lock.collectAsStateWithLifecycle()

    // Whether this phone can ask for anything, and the prompt itself. The switch
    // only moves once the phone has said who is holding it, so nobody switches on
    // a lock they have no way through.
    var lockProblem by remember { mutableStateOf(false) }
    val canLock = rememberLockAvailable()
    val askForLock = rememberAskForLock {
        lockProblem = false
        scope.launch { repository.setLock(true) }
    }

    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var switches by remember { mutableStateOf<CameraSwitchPage?>(null) }
    var blocked by remember { mutableStateOf(false) }
    // The server couldn't be asked, so the list below is nothing rather than empty.
    var offline by remember { mutableStateOf(false) }

    // This phone's own list of the cameras: which ones it leaves out, what it calls
    // them, and their order. Collected, so a switch here lands on the row at once.
    val list by repository.cameraList.collectAsStateWithLifecycle()
    // The rows as drawn: named, in this phone's order, the ones it turned off still
    // in. While a row is held it is this copy that shuffles, and the order is written
    // when the finger comes up.
    var rows by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var held by remember { mutableStateOf<String?>(null) }
    var drift by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Float>() }

    LaunchedEffect(cameras, list) {
        if (held == null) rows = list.all(cameras)
    }

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

    // What GitHub says about the newest Frigo, kept by the app rather than by this
    // page: the tab draws its dot from the same answer. Opening Settings clears that
    // dot, so this page marks what it found as seen.
    var release by remember { mutableStateOf<Update?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var percent by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf<File?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    var lookProblem by remember { mutableStateOf<String?>(null) }
    val looking by updates.looking.collectAsStateWithLifecycle()
    val answered by updates.answered.collectAsStateWithLifecycle()

    fun look() {
        scope.launch {
            lookProblem = null
            runCatching { updates.look() }
                .onFailure {
                    lookProblem = context.getString(R.string.settings_couldnt_reach_github)
                }
        }
    }

    fun fetch(wanted: Update) {
        scope.launch {
            fetching = true
            percent = 0
            runCatching {
                updates.download(wanted) { got, total ->
                    percent = if (total > 0) (got * 100 / total).toInt() else 0
                }
            }
                .onSuccess { ready = it }
                .onFailure {
                    lookProblem = context.getString(R.string.settings_couldnt_fetch)
                }
            fetching = false
        }
    }

    // The page starts on the answer the app already has, which is usually there before
    // this screen opens. A file fetched on an earlier visit is still on the phone, so
    // it is offered again rather than fetched again.
    LaunchedEffect(Unit) {
        updates.load()
        release = updates.release.value
        ready = updates.release.value
            ?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME) }
            ?.let { updates.downloaded(it.version) }
        // Opening this page is seeing the update, so the dot goes.
        updates.markSeen()
        // A phone that has never been asked gets its first answer here, rather than
        // sitting empty because the launch check had no signal. If one is already in
        // flight, that one is the answer.
        if (!updates.answered.value && !updates.looking.value) updates.look()

        updates.release.collect { found ->
            release = found
            ready = found
                ?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME) }
                ?.let { updates.downloaded(it.version) }
            // A release that arrives while you are on this page is one you are
            // watching arrive, so it must not put a dot on the tab under it.
            updates.markSeen()
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
            .onFailure { lookProblem = context.getString(R.string.settings_no_installer) }
    }

    // Android keeps the switch itself, so the note about it goes away by itself once
    // the switch has been turned on and you come back.
    LifecycleResumeEffect(Unit) {
        if (needsPermission && canInstallApps(context)) needsPermission = false
        onPauseOrDispose { }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group(stringResource(R.string.settings_servers)) {
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
                                    contentDescription = stringResource(R.string.settings_signed_in),
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
                    title = stringResource(R.string.settings_add_server),
                    onClick = onAddServer,
                )

                CardDivider()

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { scope.launch { repository.signOut() } }) {
                        Text(
                            if (servers.size > 1) {
                                stringResource(R.string.settings_sign_out_this)
                            } else {
                                stringResource(R.string.settings_sign_out)
                            },
                        )
                    }
                }
            }

            Group(stringResource(R.string.settings_notifications)) {
                Item(
                    icon = FrigoIcons.Bell,
                    title = stringResource(R.string.action_enable),
                    supporting = stringResource(R.string.settings_notifications_supporting),
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
                        text = stringResource(R.string.settings_notifications_blocked),
                        colour = MaterialTheme.colorScheme.error,
                    )
                }

                CardDivider()

                Item(
                    icon = FrigoIcons.Tag,
                    title = stringResource(R.string.alert_objects_title),
                    supporting = objects.said(),
                    onClick = onOpenAlertObjects,
                    trailing = { OpenMark() },
                )

                CardDivider()

                // The list for the person rather than the object: Frigate names a
                // face on the alert, and this phone can stay quiet about that name.
                Item(
                    icon = FrigoIcons.Person,
                    title = stringResource(R.string.alert_people_title),
                    supporting = people.said(),
                    onClick = onOpenAlertPeople,
                    trailing = { OpenMark() },
                )

                // Quiet without saying why would look broken. Every camera off on
                // the server is the one thing that can silence all of them.
                if (rules.cameras.values.none { it.notify }) {
                    Note(
                        text = stringResource(R.string.settings_all_cameras_off),
                        colour = MaterialTheme.colorScheme.error,
                    )
                }

                CardDivider()

                Item(
                    icon = FrigoIcons.Quiet,
                    title = stringResource(R.string.settings_quiet_times),
                    supporting = quiet.said(context, System.currentTimeMillis()),
                    onClick = onOpenQuiet,
                    trailing = { OpenMark() },
                )

                CardDivider()

                Note(text = stringResource(R.string.settings_battery_note))

                TextButton(
                    onClick = { context.openBatterySettings() },
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                ) {
                    Text(stringResource(R.string.settings_battery_settings))
                }
            }

            Group(stringResource(R.string.settings_live_view)) {
                Item(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.action_enable),
                    supporting = stringResource(R.string.settings_live_supporting),
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

            // Every camera the server has, including the ones this phone turned off:
            // the switch is the way back, so a hidden camera stays on the list with
            // its name read quietly and a line saying so.
            Group(stringResource(R.string.settings_cameras)) {
                val stale = switches?.readAt
                when {
                    stale != null -> Note(
                        stringResource(
                            R.string.settings_cameras_stale,
                            relativeTime(stale).lowercase(),
                        ),
                    )

                    offline -> Note(stringResource(R.string.settings_cameras_offline))
                    cameras.isEmpty() -> Note(stringResource(R.string.settings_no_cameras))
                }
                rows.forEachIndexed { index, camera ->
                    if (index > 0) CardDivider()
                    // Keyed by the camera rather than by its place in the list: a row
                    // that changes places mid-drag keeps its composition, and with it
                    // the gesture the finger is making.
                    key(camera.name) {
                        val dragging = held == camera.name
                        CameraRow(
                            title = camera.title,
                            state = switches?.cameraState(context, camera.name),
                            hidden = list.hides(camera.name),
                            dragging = dragging,
                            onOpen = { onOpenCamera(camera.name) },
                            onShow = { on ->
                                scope.launch { repository.setCameraShown(camera.name, on) }
                            },
                            onMeasured = { heights[camera.name] = it },
                            drag = Modifier.cameraDrag(
                                camera = camera.name,
                                offset = { if (held == camera.name) drift else 0f },
                                rows = { rows },
                                heightOf = { heights[it] ?: 0f },
                                onHold = { held = it },
                                onDrift = { drift = it },
                                onShuffle = { rows = it },
                                onAbandon = { rows = list.all(cameras) },
                                onDrop = { moved, to ->
                                    // Read as the drop happens, not as the row was built:
                                    // the cameras arrive after the first look at this page.
                                    val before = list.all(cameras).map { it.name }
                                    if (before.indexOf(moved) != to) {
                                        scope.launch { repository.moveCamera(moved, to, before) }
                                    }
                                },
                            ),
                        )
                    }
                }
            }

            // Only an admin may change what the server decides, and only an admin
            // may restart it, so these rows are not there for anyone else. What is
            // here belongs to the server: the lists it makes an alert from, how it
            // describes one, and how the box is doing. This phone's own switches
            // stay under Notifications.
            if (switches?.admin == true) {
                Group(stringResource(R.string.settings_server)) {
                    Item(
                        icon = FrigoIcons.Tune,
                        title = stringResource(R.string.settings_alerts_and_detections),
                        supporting = stringResource(R.string.settings_alerts_supporting),
                        onClick = onOpenAlerts,
                        trailing = { OpenMark() },
                    )

                    CardDivider()

                    Item(
                        icon = FrigoIcons.Sparkle,
                        title = stringResource(R.string.settings_ai),
                        supporting = stringResource(R.string.settings_ai_supporting),
                        onClick = onOpenAi,
                        trailing = { OpenMark() },
                    )

                    CardDivider()

                    Item(
                        icon = FrigoIcons.Gauge,
                        title = stringResource(R.string.settings_system),
                        supporting = stringResource(R.string.settings_system_supporting),
                        onClick = onOpenSystem,
                        trailing = { OpenMark() },
                    )
                }
            }

            // The phone's own lock, above the colours because both are about this
            // phone rather than about the server it is looking at.
            Group(stringResource(R.string.settings_privacy)) {
                Item(
                    icon = FrigoIcons.Lock,
                    title = stringResource(R.string.settings_lock),
                    supporting = stringResource(R.string.settings_lock_supporting),
                    trailing = {
                        Switch(
                            checked = lockOn == true,
                            onCheckedChange = { on ->
                                lockProblem = false
                                when {
                                    // Off never asks: you are already inside, which
                                    // means you already got through it.
                                    !on -> scope.launch { repository.setLock(false) }
                                    !canLock -> lockProblem = true
                                    else -> askForLock()
                                }
                            },
                        )
                    },
                )

                if (lockProblem) {
                    Note(
                        text = stringResource(R.string.settings_lock_no_screen_lock),
                        colour = MaterialTheme.colorScheme.error,
                    )
                }

                // Turned on, and the screen lock taken off since. The cover opens
                // on a tap, and this is the line that says why.
                if (lockOn == true && !canLock) {
                    Note(
                        text = stringResource(R.string.settings_lock_gone),
                        colour = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Group(stringResource(R.string.settings_appearance)) {
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
                        text = stringResource(R.string.settings_appearance_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                    )
                }

                CardDivider()

                // The language sits with the colours: both are about how the app
                // looks to you rather than about the server it is looking at.
                Item(
                    icon = FrigoIcons.Globe,
                    title = stringResource(R.string.settings_language),
                    value = Language.current().let { picked ->
                        if (picked == Language.System) {
                            stringResource(R.string.language_follow_phone)
                        } else {
                            picked.ownName
                        }
                    },
                    onClick = onOpenLanguage,
                    trailing = { OpenMark() },
                )
            }

            Group(stringResource(R.string.settings_updates)) {
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
                        text = stringResource(R.string.settings_version_here, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    when {
                        fetching -> Text(
                            text = stringResource(R.string.settings_downloading, percent),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        newer != null && apk != null -> {
                            Text(
                                text = stringResource(
                                    R.string.settings_ready_to_install,
                                    newer.version,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { install(apk) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_install))
                            }
                        }

                        newer != null -> {
                            Text(
                                text = stringResource(R.string.settings_is_out, newer.version),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { fetch(newer) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_download))
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
                                    Text(stringResource(R.string.settings_whats_new))
                                }
                            }
                        }

                        looking -> Text(
                            text = stringResource(R.string.settings_looking),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> if (answered) Text(
                            text = stringResource(R.string.settings_newest),
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
                            text = stringResource(R.string.settings_install_permission),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (!looking && !fetching && (newer == null || apk != null)) {
                        TextButton(
                            onClick = { look() },
                            modifier = Modifier.padding(start = 0.dp),
                        ) {
                            Text(stringResource(R.string.settings_check_again))
                        }
                    }
                }
            }

            Group(stringResource(R.string.settings_about)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Fact(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                    Fact(stringResource(R.string.settings_author), "Matt Mckenzie")
                    Fact(stringResource(R.string.settings_copyright), "© 2026 Matt Mckenzie")
                    TextButton(
                        onClick = { scope.launch { repository.setOnboarded(false) } },
                        modifier = Modifier.padding(start = 0.dp),
                    ) {
                        Text(stringResource(R.string.settings_show_welcome))
                    }
                }
            }

            // Clear of the bottom of the page, so the last card is not against the bar.
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One camera on the manage list: a grip, the camera, a switch that turns it off on
 * this phone, and the chevron into its own page.
 *
 * The name reads quieter while it is turned off, and the line under it says so
 * rather than naming which of Frigate's switches are off: a camera you have hidden
 * is one you have decided not to think about.
 */
@Composable
private fun CameraRow(
    title: String,
    state: CameraState?,
    hidden: Boolean,
    dragging: Boolean,
    drag: Modifier,
    onOpen: () -> Unit,
    onShow: (Boolean) -> Unit,
    onMeasured: (Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onMeasured(it.height.toFloat()) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                shadowElevation = if (dragging) 8.dp.toPx() else 0f
                shape = RoundedCornerShape(12.dp)
            }
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .then(drag),
    ) {
        Item(
            icon = FrigoIcons.Camera,
            title = title,
            supporting = if (hidden) {
                stringResource(R.string.settings_camera_hidden)
            } else {
                state?.said
            },
            colour = if (!hidden && state?.somethingOff == true) attention() else null,
            titleColour = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else null,
            onClick = onOpen,
            leading = {
                Icon(
                    imageVector = FrigoIcons.Grip,
                    contentDescription = stringResource(R.string.settings_camera_move, title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Switch(checked = !hidden, onCheckedChange = onShow)
                    OpenMark()
                }
            },
        )
    }
}

/**
 * The long press and drag that moves a row up or down.
 *
 * The row follows the finger on its own offset. When it passes the middle of the
 * row above or below, the two change places and the offset carries over, so it stays
 * under the finger and the rows shuffle around it. Nothing is written until the
 * finger comes up, and not even then if the row ended up where it started.
 *
 * No library: a drag detector, a graphics layer, and a swap.
 */
private fun Modifier.cameraDrag(
    camera: String,
    offset: () -> Float,
    rows: () -> List<Camera>,
    heightOf: (String) -> Float,
    onHold: (String?) -> Unit,
    onDrift: (Float) -> Unit,
    onShuffle: (List<Camera>) -> Unit,
    onAbandon: () -> Unit,
    onDrop: (String, Int) -> Unit,
): Modifier = this
    .graphicsLayer { translationY = offset() }
    .pointerInput(camera) {
        var drift = 0f
        fun carry() = onDrift(drift)
        detectDragGesturesAfterLongPress(
            onDragStart = {
                drift = 0f
                carry()
                onHold(camera)
            },
            onDrag = { change, amount ->
                change.consume()
                drift += amount.y
                carry()
                val at = rows().indexOfFirst { it.name == camera }
                val down = drift > 0
                val to = if (down) at + 1 else at - 1
                val neighbour = rows().getOrNull(to)
                val height = neighbour?.let { heightOf(it.name) } ?: 0f
                if (at >= 0 && neighbour != null && height > 0f && abs(drift) >= height / 2f) {
                    onShuffle(rows().toMutableList().also { it.add(to, it.removeAt(at)) })
                    drift -= if (down) height else -height
                    carry()
                }
            },
            onDragEnd = {
                val to = rows().indexOfFirst { it.name == camera }
                drift = 0f
                carry()
                onHold(null)
                if (to >= 0) onDrop(camera, to)
            },
            onDragCancel = {
                drift = 0f
                carry()
                onHold(null)
                // A gesture taken away mid-move leaves the rows where the phone last
                // drew them, which is nowhere the finger chose. They go back.
                onAbandon()
            },
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
@Composable
private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
    ThemeMode.Day -> stringResource(R.string.settings_theme_day)
    ThemeMode.Night -> stringResource(R.string.settings_theme_night)
}

/** The address without the scheme, which is the part that tells two servers apart. */
private fun String.shortName(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')
