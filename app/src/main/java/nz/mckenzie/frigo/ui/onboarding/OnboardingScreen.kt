package nz.mckenzie.frigo.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.Gate
import nz.mckenzie.frigo.data.ServerPrefs
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.FrigoTabs
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.components.prettyLabel
import nz.mckenzie.frigo.ui.batteryIsFree
import nz.mckenzie.frigo.ui.friendlyMessage
import nz.mckenzie.frigo.ui.notificationsAllowed
import nz.mckenzie.frigo.ui.openBatterySettings
import nz.mckenzie.frigo.ui.openNotificationSettings
import nz.mckenzie.frigo.ui.login.SignInForm

/** Where a new phone is in the walkthrough. */
private enum class Step { Welcome, Server, Alerts, Battery, Tour, Ready }

/** Whether Android lets Frigo put something in the shade. */
private enum class Shade { Unknown, Allowed, Refused }

/**
 * The walkthrough a phone sees once: what the app is, the server it talks to,
 * the two things Android has to allow, and a page for each tab.
 *
 * It hands over to the tabs when the last page is done, and Settings can put it
 * back on the list for anyone who wants to see it again.
 */
@Composable
fun OnboardingScreen(container: AppContainer) {
    val repository = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate by repository.gate.collectAsStateWithLifecycle()
    val server = gate as? Gate.SignedIn

    var step by rememberSaveable { mutableStateOf(Step.Welcome) }

    // What the alerts page needs, all of it kept here so a step change does not
    // lose a tick. The walkthrough can be asked for again from Settings, so the
    // picks start from what is already saved rather than pretending nothing is
    // set. The saved answer may not be in memory yet, so it is read again below.
    var cameras by remember { mutableStateOf(emptyList<Camera>()) }
    var labels by remember { mutableStateOf(emptyList<String>()) }
    var pickedCameras by remember { mutableStateOf(emptySet<String>()) }
    var pickedLabels by remember { mutableStateOf(emptySet<String>()) }
    var asking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    var shade by remember {
        mutableStateOf(if (context.notificationsAllowed()) Shade.Allowed else Shade.Unknown)
    }
    var alertsOn by remember { mutableStateOf(false) }
    var batteryFree by remember { mutableStateOf(context.batteryIsFree()) }

    LaunchedEffect(Unit) {
        repository.loadAlerts()
        val saved = repository.alerts.value
        pickedCameras = saved.cameras
        pickedLabels = saved.labels
        alertsOn = saved.on && saved.cameras.isNotEmpty() && saved.labels.isNotEmpty()
    }

    fun saveAlerts(on: Boolean) {
        scope.launch {
            runCatching {
                repository.setAlerts(
                    ServerPrefs.Alerts(on = on, cameras = pickedCameras, labels = pickedLabels),
                )
            }.onFailure { problem = it.friendlyMessage() }
        }
    }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        if (allowed) {
            shade = Shade.Allowed
            alertsOn = true
            saveAlerts(on = true)
        } else {
            // The picks are kept, so allowing it in Android's settings later
            // costs nothing but the one switch.
            shade = Shade.Refused
            saveAlerts(on = false)
        }
    }

    // Android's battery screen and its notification switch both live outside the
    // app, so the answer is read again every time the walkthrough comes back.
    LifecycleResumeEffect(Unit) {
        batteryFree = context.batteryIsFree()
        if (shade == Shade.Refused && context.notificationsAllowed()) {
            shade = Shade.Allowed
            if (pickedCameras.isNotEmpty() && pickedLabels.isNotEmpty()) {
                alertsOn = true
                saveAlerts(on = true)
            }
        }
        onPauseOrDispose { }
    }

    LaunchedEffect(step, server?.baseUrl) {
        if (step != Step.Alerts || server == null || cameras.isNotEmpty() || asking) {
            return@LaunchedEffect
        }
        asking = true
        runCatching {
            cameras = repository.cameras()
            labels = repository.labels()
        }.onFailure { problem = it.friendlyMessage() }
        asking = false
    }

    // Back goes one step at a time, so a thumb reaching for the arrow does not
    // leave a half-finished phone.
    BackHandler(enabled = step != Step.Welcome) {
        step = when (step) {
            Step.Server -> Step.Welcome
            Step.Alerts -> Step.Server
            Step.Battery -> Step.Alerts
            Step.Tour -> Step.Battery
            Step.Ready -> Step.Tour
            Step.Welcome -> Step.Welcome
        }
    }

    // The steps keep clear of the bars themselves, so the tour can draw its own
    // bottom bar down to the edge of the screen the way the tabs do.
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
        Box(Modifier.fillMaxSize()) {
            when (step) {
                Step.Welcome -> WelcomeStep(onStart = { step = Step.Server })

                Step.Server -> ServerStep(
                    server = server,
                    repository = repository,
                    onNext = { step = Step.Alerts },
                )

                Step.Alerts -> AlertsStep(
                    loading = asking,
                    problem = problem,
                    cameras = cameras,
                    labels = labels,
                    pickedCameras = pickedCameras,
                    pickedLabels = pickedLabels,
                    onPickCamera = { name, ticked ->
                        pickedCameras = if (ticked) pickedCameras + name else pickedCameras - name
                    },
                    onPickLabel = { name, ticked ->
                        pickedLabels = if (ticked) pickedLabels + name else pickedLabels - name
                    },
                    shade = shade,
                    alertsOn = alertsOn,
                    onTurnOn = {
                        if (shade == Shade.Allowed) {
                            alertsOn = true
                            saveAlerts(on = true)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            shade = Shade.Allowed
                            alertsOn = true
                            saveAlerts(on = true)
                        }
                    },
                    onOpenNotificationSettings = { context.openNotificationSettings() },
                    onNext = { step = Step.Battery },
                    onNotNow = { step = Step.Battery },
                )

                Step.Battery -> BatteryStep(
                    free = batteryFree,
                    onOpenSettings = { context.openBatterySettings() },
                    onNext = { step = Step.Tour },
                )

                Step.Tour -> TourStep(onDone = { step = Step.Ready })

                Step.Ready -> ReadyStep(
                    address = server?.baseUrl.orEmpty(),
                    onOpen = { scope.launch { repository.setOnboarded(true) } },
                )
            }
        }
    }
}

/* ---------------------------------------------------------------- welcome */

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            PictureTile(name = "Front door")
        }
        Text("Frigo", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your Frigate cameras on your phone.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "It talks to the server you already run. Your sign-in stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Get started")
        }
    }
}

/** A camera picture with its name on it, the way the wide look draws one. */
@Composable
private fun PictureTile(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.dp)),
    ) {
        SceneArt(Scene.FrontDoor, Modifier.fillMaxSize())
        NameScrim(52.dp, Modifier.align(Alignment.BottomCenter))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE8EEF7),
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
        )
    }
}

/* ----------------------------------------------------------------- server */

@Composable
private fun ServerStep(
    server: Gate.SignedIn?,
    repository: FrigoRepository,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("Your server", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The address you use in a browser, and your Frigate sign-in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        if (server == null) {
            SignInForm(repository = repository, onSignedIn = onNext)
        } else {
            // Signed in already, which is what Settings asking for the walkthrough
            // again looks like. Asking for the password twice would be rude.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Item(
                        icon = FrigoIcons.Server,
                        title = "Address",
                        value = server.baseUrl,
                    )
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Person,
                        title = "Signed in as",
                        value = server.username,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Next")
            }
        }
    }
}

/* ----------------------------------------------------------------- alerts */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertsStep(
    loading: Boolean,
    problem: String?,
    cameras: List<Camera>,
    labels: List<String>,
    pickedCameras: Set<String>,
    pickedLabels: Set<String>,
    onPickCamera: (String, Boolean) -> Unit,
    onPickLabel: (String, Boolean) -> Unit,
    shade: Shade,
    alertsOn: Boolean,
    onTurnOn: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onNext: () -> Unit,
    onNotNow: () -> Unit,
) {
    val picked = pickedCameras.isNotEmpty() && pickedLabels.isNotEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (alertsOn || shade == Shade.Refused) {
                "Your phone tells you when Frigate sees something."
            } else {
                "Your phone tells you when Frigate sees something. Tick what's worth a buzz."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        // A server with a long list of objects would push the card past the
        // bottom of the screen, so the card scrolls and the buttons stay put.
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    // Android's own switch wins: it can be turned off after the
                    // walkthrough allowed it, and the page should say so.
                    shade == Shade.Refused -> RefusedCard()
                    alertsOn -> AllowedCard(cameras, labels, pickedCameras, pickedLabels)
                    else -> Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "Cameras",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                        )
                        if (cameras.isEmpty()) {
                            Text(
                                text = if (loading) "Asking your server…" else "No camera this account can see.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            cameras.forEach { camera ->
                                val ticked = camera.name in pickedCameras
                                FilterChip(
                                    selected = ticked,
                                    onClick = { onPickCamera(camera.name, !ticked) },
                                    label = { Text(camera.title) },
                                )
                            }
                        }

                        Text(
                            text = "Objects",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                        )
                        if (labels.isEmpty()) {
                            Text(
                                text = if (loading) "" else "No object this account can see.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            labels.forEach { label ->
                                val ticked = label in pickedLabels
                                FilterChip(
                                    selected = ticked,
                                    onClick = { onPickLabel(label, !ticked) },
                                    label = { Text(prettyLabel(label)) },
                                )
                            }
                        }

                        if (problem != null) {
                            Text(
                                text = problem,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            shade == Shade.Refused -> {
                Text(
                    text = "Your picks are kept, so there is nothing to tick again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Next")
                }
                TextButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open notification settings")
                }
            }

            alertsOn -> {
                Text(
                    text = "You can change this later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Next")
                }
            }

            else -> {
                Text(
                    text = when {
                        picked -> "Android asks about notifications once."
                        else -> "Pick a camera and an object first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onTurnOn,
                    enabled = picked && !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("Turn on alerts")
                }
                TextButton(
                    onClick = onNotNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Not now")
                }
            }
        }
    }
}

/** What the page says once Android has allowed the notifications. */
@Composable
private fun AllowedCard(
    cameras: List<Camera>,
    labels: List<String>,
    pickedCameras: Set<String>,
    pickedLabels: Set<String>,
) {
    val named = cameras.filter { it.name in pickedCameras }.map { it.title }
    val seen = labels.filter { it in pickedLabels }.map { prettyLabel(it) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text("Notifications allowed", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = named.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = seen.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the page says when Android turned the notifications down. */
@Composable
private fun RefusedCard() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Notifications are off in Android",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "Alerts stay off until you allow them in the phone's settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------------------------------------------------------- battery */

@Composable
private fun BatteryStep(
    free: Boolean,
    onOpenSettings: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("Battery", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Android stops apps to save battery, which stops the alerts. Turn " +
                "battery optimization off for Frigo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = FrigoIcons.Battery,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Battery optimization",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (free) "Off for Frigo" else "On for Frigo",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (free) MaterialTheme.colorScheme.primary else attention(),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (free) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Next")
            }
        } else {
            Text(
                text = "That screen belongs to Android. Come back and Frigo checks for itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Open battery settings")
            }
            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text("Skip")
            }
        }
    }
}

/* ------------------------------------------------------------------- tour */

/** One page of the walkthrough: its heading, its words, and the tab it lights. */
private data class TourStop(val title: String, val tab: Int, val words: String)

/**
 * The six pages, in order. The first two are the Cameras tab twice over: the
 * grid, then one camera opened with the day under it. Splitting them is what
 * lets each page say one thing.
 */
private val TourStops = listOf(
    TourStop(
        title = "Cameras",
        tab = 0,
        words = "All four cameras at once, so a glance tells you whether anyone is " +
            "about. Tap one for live view.",
    ),
    TourStop(
        title = "Live and playback",
        tab = 0,
        words = "Tap a camera and the picture goes live. The bar underneath is the day; " +
            "drag it to go back through it. Turn the phone on its side for the whole screen.",
    ),
    TourStop(
        title = "Review",
        tab = 1,
        words = "The moments Frigate thought were worth a look, newest first. Tap one to " +
            "watch it, and it leaves the list once you've seen it.",
    ),
    TourStop(
        title = "Explore",
        tab = 2,
        words = "Everything Frigate recorded. Narrow it by camera, by what was seen, or by day.",
    ),
    TourStop(
        title = "Clips",
        tab = 3,
        words = "The clips you kept. Watch one, save it to your phone, or delete it " +
            "from the server.",
    ),
    TourStop(
        title = "Settings",
        tab = 4,
        words = "Your server, your alerts, and how the app looks.",
    ),
)

@Composable
private fun TourStep(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { TourStops.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            key = { it },
        ) { index ->
            TourPage(
                stop = TourStops[index],
                index = index,
                onSkip = onDone,
                onNext = {
                    if (index == TourStops.lastIndex) onDone()
                    else scope.launch { pager.animateScrollToPage(index + 1) }
                },
            )
        }
        TourBar(active = TourStops[pager.currentPage].tab)
    }
}

@Composable
private fun TourPage(stop: TourStop, index: Int, onSkip: () -> Unit, onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSkip) { Text("Skip") }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    // Tall enough for a 16:9 clip and the lines under it, which
                    // is the page that needs the most room.
                    .height(246.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
            ) {
                TourArt(index)
            }
            Spacer(Modifier.height(24.dp))
            Text(stop.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stop.words,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TourStops.indices.forEach { page ->
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(if (page == index) 18.dp else 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (page == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            ),
                    )
                }
            }
            TextButton(onClick = onNext) {
                Text(if (index == TourStops.lastIndex) "Done" else "Next")
            }
        }
    }
}

/** The bottom bar, drawn but not tappable: it says where this page lives. */
@Composable
private fun TourBar(active: Int) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FrigoTabs.forEachIndexed { index, tab ->
                val chosen = index == active
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (chosen) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            tint = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


/* ------------------------------------------------------------------ ready */

@Composable
private fun ReadyStep(address: String, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        Text("You're set.", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (address.isNotBlank()) "Frigo is signed in to $address."
            else "Frigo is signed in.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You can change all of this in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Open Frigo")
        }
    }
}
