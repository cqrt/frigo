package nz.mckenzie.frigo.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.Gate
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.FrigoTabs
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.batteryIsFree
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

    // The walkthrough can be asked for again from Settings, so the switch starts
    // from what is already saved rather than pretending nothing is set.
    var shade by remember {
        mutableStateOf(if (context.notificationsAllowed()) Shade.Allowed else Shade.Unknown)
    }
    var alertsOn by remember { mutableStateOf(false) }
    var batteryFree by remember { mutableStateOf(context.batteryIsFree()) }

    // Whether the server can describe what it sees, which is the one thing the
    // tour offers to show only when it exists. Asked for once the sign-in is
    // through, since there is no server to ask before that.
    var describes by remember { mutableStateOf(false) }
    LaunchedEffect(server?.baseUrl) {
        if (server != null) {
            describes = runCatching { repository.aiSetup() }.getOrNull()?.ready == true
        }
    }

    LaunchedEffect(Unit) {
        repository.loadAlerts()
        alertsOn = repository.alerts.value.on
    }

    fun saveAlerts(on: Boolean) {
        scope.launch {
            runCatching { repository.setAlerts(repository.alerts.value.copy(on = on)) }
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
            // Nothing is ticked here to lose: which cameras and objects buzz is
            // set on the server. Allowing it later costs the one switch.
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
            alertsOn = true
            saveAlerts(on = true)
        }
        onPauseOrDispose { }
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

                Step.Tour -> TourStep(
                    describes = describes,
                    onDone = { step = Step.Ready },
                )

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

@Composable
private fun AlertsStep(
    shade: Shade,
    alertsOn: Boolean,
    onTurnOn: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onNext: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your phone tells you when your server reports an alert.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (shade == Shade.Refused) RefusedCard() else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .background(
                                    if (alertsOn) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (alertsOn) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(
                            text = if (alertsOn) "Notifications allowed" else "Notifications",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = "Which cameras and objects buzz is set on your server. " +
                            "Its notifications page and its alerts page decide.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        when {
            shade == Shade.Refused -> {
                Text(
                    text = "Alerts stay off until you allow them in Android's settings.",
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
                    text = "Android asks about notifications once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onTurnOn,
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
private data class TourStop(
    val title: String,
    val tab: Int,
    val words: String,
    /** True on the page that draws a camera picture, which keeps its own shape. */
    val camera: Boolean = false,
)

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
        camera = true,
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

/**
 * The last page, shown only when the server is already set up to describe what
 * it sees. A tour is not the place to send someone into Frigate's settings.
 */
private val AiTourStop = TourStop(
    title = "AI Generated Alerts",
    tab = 4,
    words = "Frigo uses Artificial Intelligence to describe what it sees in a notification.",
)

@Composable
private fun TourStep(describes: Boolean, onDone: () -> Unit) {
    val stops = remember(describes) { if (describes) TourStops + AiTourStop else TourStops }
    val pager = rememberPagerState(pageCount = { stops.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            key = { it },
        ) { index ->
            TourPage(
                stop = stops[index],
                index = index,
                count = stops.size,
                last = index == stops.lastIndex,
                onSkip = onDone,
                onNext = {
                    if (index == stops.lastIndex) onDone()
                    else scope.launch { pager.animateScrollToPage(index + 1) }
                },
            )
        }
        TourBar(active = stops[pager.currentPage].tab)
    }
}

@Composable
private fun TourPage(
    stop: TourStop,
    index: Int,
    count: Int,
    last: Boolean,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // A page with a camera picture takes its height from the width, so
                // the picture is a camera's shape rather than whatever the page has
                // left over after the bars: 16:9 inside the 12dp of padding, plus
                // the day strip, the hour bar and the two gaps between them.
                val frame = if (stop.camera) {
                    (maxWidth - 24.dp) * 9f / 16f + 24.dp + 16.dp + 58.dp + 16.dp
                } else {
                    246.dp
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(frame)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(12.dp),
                ) {
                    TourArt(index)
                }
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
                (0 until count).forEach { page ->
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
                Text(if (last) "Done" else "Next")
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
