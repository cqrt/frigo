package nz.mckenzie.frigo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import nz.mckenzie.frigo.alerts.AlertService
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Gate
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.cameras.CamerasScreen
import nz.mckenzie.frigo.ui.cameras.PlayerScreen
import nz.mckenzie.frigo.ui.events.EventsScreen
import nz.mckenzie.frigo.ui.login.LoginScreen
import nz.mckenzie.frigo.ui.settings.SettingsScreen

@Composable
fun FrigoApp(container: AppContainer, openCamera: String? = null) {
    val gate by container.repository.gate.collectAsStateWithLifecycle()
    val alerts by container.repository.alerts.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { container.repository.restore() }

    // The switch in Settings says what should happen, and this makes it true
    // whenever the app opens: watching while signed in, nothing otherwise.
    LaunchedEffect(gate, alerts.on) {
        if (gate is Gate.SignedIn && alerts.on) AlertService.start(context)
        else AlertService.stop(context)
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

        is Gate.SignedIn -> MainScaffold(container, state, openCamera)
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("cameras", "Cameras", Icons.Filled.Home),
    Tab("events", "Events", Icons.AutoMirrored.Filled.List),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

private const val PLAYER_PREFIX = "player/"

@Composable
private fun MainScaffold(container: AppContainer, server: Gate.SignedIn, openCamera: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val watching = currentRoute?.startsWith(PLAYER_PREFIX) == true

    Scaffold(
        bottomBar = {
            if (!watching) {
                NavigationBar {
                    Tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { navController.switchTo(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = openCamera?.let { "$PLAYER_PREFIX$it" } ?: "cameras",
        ) {
            composable("cameras") {
                CamerasScreen(
                    container = container,
                    modifier = Modifier.padding(padding),
                ) { camera ->
                    navController.navigate("$PLAYER_PREFIX${camera.name}")
                }
            }
            composable("events") {
                EventsScreen(container, Modifier.padding(padding))
            }
            composable("settings") {
                SettingsScreen(
                    repository = container.repository,
                    baseUrl = server.baseUrl,
                    username = server.username,
                    modifier = Modifier.padding(padding),
                )
            }
            composable("$PLAYER_PREFIX{camera}") { entry ->
                val camera = entry.arguments?.getString("camera") ?: return@composable
                // The player draws its own background under the system bars, so
                // it deliberately ignores the scaffold padding.
                PlayerScreen(
                    repository = container.repository,
                    cameraName = camera,
                    title = prettyCameraName(camera),
                    onBack = { navController.popBackStack() },
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
