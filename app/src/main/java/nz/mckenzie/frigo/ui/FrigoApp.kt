package nz.mckenzie.frigo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.alerts.AlertService
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Gate
import nz.mckenzie.frigo.ui.cameras.CamerasScreen
import nz.mckenzie.frigo.ui.cameras.PlayerScreen
import nz.mckenzie.frigo.ui.clips.ClipsScreen
import nz.mckenzie.frigo.ui.explore.ExploreScreen
import nz.mckenzie.frigo.ui.login.AddServerScreen
import nz.mckenzie.frigo.ui.login.LoginScreen
import nz.mckenzie.frigo.ui.lock.LockScreen
import nz.mckenzie.frigo.ui.onboarding.OnboardingScreen
import nz.mckenzie.frigo.ui.reviews.ReviewSettingsScreen
import nz.mckenzie.frigo.ui.reviews.ReviewsScreen
import nz.mckenzie.frigo.ui.settings.AiDescriptionsScreen
import nz.mckenzie.frigo.ui.settings.AiModelScreen
import nz.mckenzie.frigo.ui.settings.AlertObjectsScreen
import nz.mckenzie.frigo.ui.settings.AlertPeopleScreen
import nz.mckenzie.frigo.ui.settings.CameraSwitchesScreen
import nz.mckenzie.frigo.ui.settings.LanguageScreen
import nz.mckenzie.frigo.ui.settings.LinkTvScreen
import nz.mckenzie.frigo.ui.settings.LogsScreen
import nz.mckenzie.frigo.ui.settings.QuietTimesScreen
import nz.mckenzie.frigo.ui.settings.SettingsScreen
import nz.mckenzie.frigo.ui.settings.SystemScreen

@Composable
fun FrigoApp(container: AppContainer, openCamera: String? = null, onCameraOpened: () -> Unit = {}) {
    val lockOn by container.repository.lock.collectAsStateWithLifecycle()
    val locked by container.lock.locked.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // The app is built under the cover rather than after it, so unlocking
        // lands on cameras that are already there. It also means the sign-in
        // check and the release check are done by the time the cover goes.
        AppBody(container, openCamera, onCameraOpened)

        // Only a launch that started covered, and only while the switch says so.
        if (lockOn == true && locked) {
            LockScreen(onUnlock = container.lock::unlock)
        }
    }
}

@Composable
private fun AppBody(
    container: AppContainer,
    openCamera: String?,
    onCameraOpened: () -> Unit,
) {
    val gate by container.repository.gate.collectAsStateWithLifecycle()
    val alerts by container.repository.alerts.collectAsStateWithLifecycle()
    val onboarded by container.repository.onboarded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { container.repository.restore() }

    // Opening the app and coming back to it are the two moments worth asking GitHub
    // whether there is a newer Frigo. It asks only when the answer on the phone has
    // gone stale, so this is at most one ask an hour however often you come back. It
    // sits above the branches below so a phone on the welcome or sign-in page is told
    // as well, and the dot is right the moment the tabs appear.
    LifecycleResumeEffect(Unit) {
        scope.launch { container.updateWatch.lookIfStale() }
        onPauseOrDispose { }
    }

    // The switch in Settings says what should happen, and this makes it true
    // whenever the app opens: watching while signed in, nothing otherwise. A
    // different server is a different feed, so the connection is made again
    // rather than left pointing at the last one.
    LaunchedEffect(gate, alerts.on) {
        if (gate is Gate.SignedIn && alerts.on) AlertService.restart(context)
        else AlertService.stop(context)
    }

    // The walkthrough comes first on a new phone, and takes over from the tabs
    // whenever Settings asks for it back.
    if (onboarded == false) {
        OnboardingScreen(container)
        return
    }

    when (val state = gate) {
        Gate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is Gate.SignedOut -> LoginScreen(
            repository = container.repository,
            notice = state.message,
            initialAddress = state.address,
            initialUsername = state.username,
        )

        // A different server is a different app: the tabs and the screens they
        // hold start again rather than showing what the last server answered.
        is Gate.SignedIn -> key(state.baseUrl) {
            MainScaffold(container, state, openCamera, onCameraOpened)
        }
    }
}

internal data class FrigoTab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
)

/** The five tabs, in the order the bar shows them. The walkthrough lights them up. */
internal val FrigoTabs = listOf(
    FrigoTab("cameras", R.string.tab_cameras, Icons.Filled.Home),
    FrigoTab("reviews", R.string.tab_reviews, FrigoIcons.Reviews),
    FrigoTab("explore", R.string.tab_explore, FrigoIcons.Search),
    FrigoTab("clips", R.string.tab_clips, FrigoIcons.Clips),
    FrigoTab("settings", R.string.tab_settings, Icons.Filled.Settings),
)

private const val PLAYER_PREFIX = "player/"

@Composable
private fun MainScaffold(
    container: AppContainer,
    server: Gate.SignedIn,
    openCamera: String?,
    onCameraOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val watching = currentRoute?.startsWith(PLAYER_PREFIX) == true
    val updateWaiting by container.updateWatch.waiting.collectAsStateWithLifecycle()

    // A tapped alert asks for one camera, and it sits on top of the grid rather
    // than replacing it. Back from there lands on the grid with the tabs back,
    // which is where you want to be after looking.
    LaunchedEffect(openCamera) {
        val camera = openCamera ?: return@LaunchedEffect
        navController.navigate("$PLAYER_PREFIX$camera") { launchSingleTop = true }
        onCameraOpened()
    }

    Scaffold(
        bottomBar = {
            if (!watching) {
                NavigationBar {
                    FrigoTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTo(tab.route) },
                            icon = {
                                // The dot lives only on Settings: it is where the
                                // update is downloaded, and everywhere else would be
                                // a mark with nothing behind it.
                                if (tab.route == "settings" && updateWaiting) {
                                    WithUpdateDot { Icon(tab.icon, contentDescription = null) }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = {
                                Text(
                                    text = stringResource(tab.label),
                                    // A language with longer words keeps the bar one line
                                    // tall: German's "Einstellungen" is the one that would
                                    // otherwise wrap under its own icon.
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "cameras",
        ) {
            composable("cameras") {
                CamerasScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                ) { camera ->
                    navController.navigate("$PLAYER_PREFIX${camera.name}")
                }
            }
            composable("reviews") {
                ReviewsScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onOpenSettings = {
                        navController.navigate("review-settings") { launchSingleTop = true }
                    },
                    onOpenCamera = { camera, moment ->
                        navController.navigate("$PLAYER_PREFIX$camera?at=${momentParam(moment)}")
                    },
                )
            }
            composable("review-settings") {
                ReviewSettingsScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("explore") {
                ExploreScreen(container, Modifier.padding(padding))
            }
            composable("clips") {
                ClipsScreen(container, Modifier.padding(padding))
            }
            composable("settings") {
                SettingsScreen(
                    repository = container.repository,
                    updates = container.updateWatch,
                    baseUrl = server.baseUrl,
                    modifier = Modifier.padding(padding),
                    onOpenCamera = { camera ->
                        navController.navigate("camera-switches/$camera") { launchSingleTop = true }
                    },
                    onOpenAlerts = {
                        navController.navigate("review-settings") { launchSingleTop = true }
                    },
                    onOpenAi = {
                        navController.navigate("ai-descriptions") { launchSingleTop = true }
                    },
                    onOpenAlertObjects = {
                        navController.navigate("alert-objects") { launchSingleTop = true }
                    },
                    onOpenAlertPeople = {
                        navController.navigate("alert-people") { launchSingleTop = true }
                    },
                    onOpenQuiet = {
                        navController.navigate("quiet-times") { launchSingleTop = true }
                    },
                    onOpenLanguage = {
                        navController.navigate("language") { launchSingleTop = true }
                    },
                    onOpenSystem = {
                        navController.navigate("system") { launchSingleTop = true }
                    },
                    onOpenLogs = {
                        navController.navigate("system-logs") { launchSingleTop = true }
                    },
                    onAddServer = {
                        navController.navigate("add-server") { launchSingleTop = true }
                    },
                    onLinkTv = {
                        navController.navigate("link-tv") { launchSingleTop = true }
                    },
                )
            }
            composable("system") {
                SystemScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("system-logs") {
                LogsScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("quiet-times") {
                QuietTimesScreen(
                    repository = container.repository,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("alert-objects") {
                AlertObjectsScreen(
                    repository = container.repository,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("alert-people") {
                AlertPeopleScreen(
                    repository = container.repository,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("language") {
                LanguageScreen(
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("ai-descriptions") {
                AiDescriptionsScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                    onOpenModel = {
                        navController.navigate("ai-model") { launchSingleTop = true }
                    },
                )
            }
            composable("ai-model") {
                AiModelScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable("add-server") {
                AddServerScreen(
                    repository = container.repository,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("link-tv") {
                LinkTvScreen(
                    container = container,
                    baseUrl = server.baseUrl,
                    username = server.username,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("camera-switches/{camera}") { entry ->
                val camera = entry.arguments?.getString("camera") ?: return@composable
                CameraSwitchesScreen(
                    container = container,
                    cameraName = camera,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("$PLAYER_PREFIX{camera}?at={at}") { entry ->
                val camera = entry.arguments?.getString("camera") ?: return@composable
                // The moment is sent by a review's other-camera tile, so the
                // camera opens on what happened then rather than on the stream.
                val at = entry.arguments?.getString("at")?.toDoubleOrNull()
                // The player draws its own background under the system bars, so
                // it deliberately ignores the scaffold padding.
                PlayerScreen(
                    repository = container.repository,
                    cameraName = camera,
                    startMoment = at,
                    onBack = { navController.popBackStack() },
                    onOpenClips = { navController.switchTo("clips") },
                )
            }
        }
    }
}

/** Moves between tabs without stacking them up, keeping each tab's scroll. */
private fun NavController.switchTo(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A moment written the way the player's route reads it: digits and a dot, no exponent. */
private fun momentParam(seconds: Double): String =
    String.format(java.util.Locale.ROOT, "%.3f", seconds)

/**
 * A tab's icon with a red dot on its corner, meaning a newer Frigo is waiting.
 *
 * Drawn here rather than taken from Material's badge because that dot is 6dp and this
 * one is 12dp. The dot's middle sits on the corner of the icon, so half of it hangs
 * outside, which is what makes it read as a mark on the tab rather than a smaller icon.
 */
@Composable
private fun WithUpdateDot(content: @Composable () -> Unit) {
    val updateAvailable = stringResource(R.string.update_available)
    Box {
        content()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .semantics { contentDescription = updateAvailable },
        )
    }
}
