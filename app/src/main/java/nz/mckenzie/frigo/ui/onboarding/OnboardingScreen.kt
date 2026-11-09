package nz.mckenzie.frigo.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.Gate
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.FrigoTabs
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.batteryIsFree
import nz.mckenzie.frigo.ui.lock.rememberAskForLock
import nz.mckenzie.frigo.ui.lock.rememberLockAvailable
import nz.mckenzie.frigo.ui.notificationsAllowed
import nz.mckenzie.frigo.ui.openBatterySettings
import nz.mckenzie.frigo.ui.openNotificationSettings
import nz.mckenzie.frigo.ui.login.SignInForm

/** Where a new phone is in the walkthrough. */
private enum class Step { Welcome, Server, Alerts, Battery, Lock, Tour, Ready }

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

    // The lock, offered on the same terms as the switch in Settings: the phone
    // has to say who is holding it before it can be turned on.
    val lockOn by repository.lock.collectAsStateWithLifecycle()
    var lockProblem by remember { mutableStateOf(false) }
    val canLock = rememberLockAvailable()
    val askForLock = rememberAskForLock {
        lockProblem = false
        scope.launch { repository.setLock(true) }
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
            Step.Lock -> Step.Battery
            Step.Tour -> Step.Lock
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
                    onNext = { step = Step.Lock },
                )

                Step.Lock -> LockStep(
                    lockOn = lockOn == true,
                    canLock = canLock,
                    problem = lockProblem,
                    onChange = { on ->
                        lockProblem = false
                        when {
                            !on -> scope.launch { repository.setLock(false) }
                            !canLock -> lockProblem = true
                            else -> askForLock()
                        }
                    },
                    onNext = { step = Step.Tour },
                    onNotNow = { step = Step.Tour },
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
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_welcome_lead),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.onboard_get_started))
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
        Text(
            text = stringResource(R.string.onboard_server_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_server_lead),
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
                        title = stringResource(R.string.onboard_server_address),
                        value = server.baseUrl,
                    )
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Person,
                        title = stringResource(R.string.onboard_server_signed_in_as),
                        value = server.username,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.onboard_next))
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
        Text(
            text = stringResource(R.string.onboard_alerts_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_alerts_lead),
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
                            text = if (alertsOn) stringResource(R.string.onboard_alerts_allowed)
                            else stringResource(R.string.onboard_alerts_notifications),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Text(
                        text = stringResource(R.string.onboard_alerts_where),
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
                    text = stringResource(R.string.onboard_alerts_refused_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.onboard_next))
                }
                TextButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboard_alerts_open_settings))
                }
            }

            alertsOn -> {
                Text(
                    text = stringResource(R.string.onboard_alerts_change_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.onboard_next))
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.onboard_alerts_asks_once),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onTurnOn,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.onboard_alerts_turn_on))
                }
                TextButton(
                    onClick = onNotNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboard_alerts_not_now))
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
            text = stringResource(R.string.onboard_alerts_off_in_android),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.onboard_alerts_refused_card),
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
        Text(
            text = stringResource(R.string.onboard_battery_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_battery_lead),
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
                    text = stringResource(R.string.onboard_battery_optimization),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (free) stringResource(R.string.onboard_battery_off)
                    else stringResource(R.string.onboard_battery_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (free) MaterialTheme.colorScheme.primary else attention(),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (free) {
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.onboard_next))
            }
        } else {
            Text(
                text = stringResource(R.string.onboard_battery_android_screen),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.onboard_battery_open_settings))
            }
            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboard_skip))
            }
        }
    }
}

/* ------------------------------------------------------------------- lock */

/**
 * The lock, offered once. It asks for the same fingerprint the switch in Settings
 * does, so turning it on here and turning it on there are the same act.
 */
@Composable
private fun LockStep(
    lockOn: Boolean,
    canLock: Boolean,
    problem: Boolean,
    onChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.onboard_lock_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboard_lock_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Item(
                    icon = FrigoIcons.Lock,
                    title = stringResource(R.string.onboard_lock_title),
                    supporting = stringResource(R.string.onboard_lock_row),
                    trailing = {
                        Switch(checked = lockOn, onCheckedChange = onChange)
                    },
                )
                if (problem) {
                    CardDivider()
                    Note(
                        text = stringResource(R.string.settings_lock_no_screen_lock),
                        colour = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.onboard_next))
        }
        TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboard_lock_not_now))
        }
    }
}

/* ------------------------------------------------------------------- tour */

/** One page of the walkthrough: its heading, its words, and the tab it lights. */
private data class TourStop(
    @StringRes val title: Int,
    val tab: Int,
    @StringRes val words: Int,
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
        title = R.string.onboard_tour_cameras,
        tab = 0,
        words = R.string.onboard_tour_cameras_words,
    ),
    TourStop(
        title = R.string.onboard_tour_live,
        tab = 0,
        words = R.string.onboard_tour_live_words,
        camera = true,
    ),
    TourStop(
        title = R.string.onboard_tour_review,
        tab = 1,
        words = R.string.onboard_tour_review_words,
    ),
    TourStop(
        title = R.string.onboard_tour_explore,
        tab = 2,
        words = R.string.onboard_tour_explore_words,
    ),
    TourStop(
        title = R.string.onboard_tour_clips,
        tab = 3,
        words = R.string.onboard_tour_clips_words,
    ),
    TourStop(
        title = R.string.onboard_tour_settings,
        tab = 4,
        words = R.string.onboard_tour_settings_words,
    ),
)

/**
 * The last page, shown only when the server is already set up to describe what
 * it sees. A tour is not the place to send someone into Frigate's settings.
 */
private val AiTourStop = TourStop(
    title = R.string.onboard_tour_ai,
    tab = 4,
    words = R.string.onboard_tour_ai_words,
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
            TextButton(onClick = onSkip) { Text(stringResource(R.string.onboard_skip)) }
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
            Text(stringResource(stop.title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(stop.words),
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
                Text(
                    if (last) stringResource(R.string.onboard_done)
                    else stringResource(R.string.onboard_next),
                )
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
                        text = stringResource(tab.label),
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
        Text(
            text = stringResource(R.string.onboard_ready_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (address.isNotBlank()) {
                stringResource(R.string.onboard_ready_signed_in_to, address)
            } else {
                stringResource(R.string.onboard_ready_signed_in)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.onboard_ready_change_later),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.onboard_ready_open))
        }
    }
}
