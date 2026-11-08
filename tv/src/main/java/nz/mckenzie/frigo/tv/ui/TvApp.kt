package nz.mckenzie.frigo.tv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.tv.BuildConfig
import nz.mckenzie.frigo.tv.R
import nz.mckenzie.frigo.tv.data.Alert
import nz.mckenzie.frigo.tv.data.Camera
import nz.mckenzie.frigo.tv.data.Ring
import nz.mckenzie.frigo.tv.data.TvRepository
import nz.mckenzie.frigo.tv.pair.PairingCode
import nz.mckenzie.frigo.tv.pair.PairingServer
import nz.mckenzie.frigo.tv.pair.localIpv4
import nz.mckenzie.frigo.tv.pair.qrBitmap

/**
 * The television app, from the pairing code to the cameras.
 *
 * The gate is one state rather than a set of flags: there is no moment where the
 * screen is both pairing and showing a camera, so the screen cannot be drawn in
 * one.
 */
@Composable
fun TvApp(repository: TvRepository) {
    val gate by repository.gate.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { repository.start() }

    // A camera added on the server reaches the television without it being
    // restarted, and so does the dot that says something has been flagged. A
    // minute is often enough for something you set up once.
    LaunchedEffect(gate) {
        if (gate is TvRepository.Gate.Ready) {
            repository.refreshUnseenAlerts()
            while (isActive) {
                delay(60_000)
                repository.refreshCameras()
                repository.refreshUnseenAlerts()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val showing = gate) {
            TvRepository.Gate.Loading -> SigningIn()
            TvRepository.Gate.Pairing -> PairingScreen(repository)
            is TvRepository.Gate.Ready -> ReadyScreens(repository, showing.cameras)
            is TvRepository.Gate.Problem -> ProblemScreen(repository, showing.refused)
        }
    }
}

/** Where the television is: the cameras, the alerts, or one alert's clip. */
private sealed interface TvPage {
    data object Cameras : TvPage
    data object Alerts : TvPage
    data class Clip(val alert: Alert) : TvPage
}

@Composable
private fun ReadyScreens(repository: TvRepository, cameras: List<Camera>) {
    var page by remember { mutableStateOf<TvPage>(TvPage.Cameras) }
    val unseen by repository.unseenAlerts.collectAsStateWithLifecycle()
    var ring by remember { mutableStateOf<Ring?>(null) }

    // A bell is louder than whatever is on: it puts the cameras back up, where
    // the picture that answers it lives. Anywhere else on the app, a ring that
    // left the screen on the alerts page would look like it did nothing.
    LaunchedEffect(Unit) {
        repository.rings.collect { pressed ->
            ring = pressed
            page = TvPage.Cameras
        }
    }

    when (val showing = page) {
        TvPage.Cameras -> CamerasScreen(
            repository = repository,
            cameras = cameras,
            newAlerts = unseen,
            onOpenAlerts = { page = TvPage.Alerts },
            ring = ring,
        )

        TvPage.Alerts -> AlertsScreen(
            repository = repository,
            onBack = { page = TvPage.Cameras },
            onPlay = { alert -> page = TvPage.Clip(alert) },
        )

        is TvPage.Clip -> AlertClipScreen(
            repository = repository,
            alert = showing.alert,
            onClose = { page = TvPage.Alerts },
        )
    }
}

@Composable
private fun SigningIn() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.tv_loading),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The code the phone reads. The television has no camera, so it is the one that
 * shows; the phone is the one that looks.
 */
@Composable
private fun PairingScreen(repository: TvRepository) {
    var attempt by remember { mutableIntStateOf(0) }
    val host = remember(attempt) { localIpv4() }
    var code by remember { mutableStateOf<PairingCode?>(null) }
    var trouble by remember { mutableStateOf(false) }
    val server = remember { mutableStateOf<PairingServer?>(null) }

    LaunchedEffect(host, attempt) {
        val address = host ?: run {
            trouble = false
            return@LaunchedEffect
        }
        trouble = false
        code = null
        val created = PairingServer(
            onPair = { baseUrl, username, password ->
                repository.accept(baseUrl, username, password)
            },
            onPaired = {},
        )
        server.value = created
        try {
            val opened = withContext(Dispatchers.IO) { runCatching { created.open(address) } }
                .getOrNull()
            if (opened == null) {
                trouble = true
                return@LaunchedEffect
            }
            // A debug build says the code in the log as well as on the glass, so
            // the pairing can be tried from a computer with no phone at hand.
            if (BuildConfig.DEBUG) {
                android.util.Log.i("FrigoPairing", opened.text())
            }
            code = opened
            withContext(Dispatchers.IO) { created.serve() }
        } finally {
            server.value = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { server.value?.close() }
    }

    if (host == null) {
        CenteredNote(
            title = stringResource(R.string.pairing_no_network),
            button = stringResource(R.string.pairing_retry),
            onButton = { attempt++ },
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 72.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = androidx.compose.ui.graphics.Color.White,
                shape = RoundedCornerShape(28.dp),
            ) {
                Box(Modifier.padding(20.dp)) {
                    val picture = remember(code) { code?.let { qrBitmap(it.text()) } }
                    if (picture != null) {
                        Image(
                            bitmap = picture.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(248.dp),
                        )
                    } else {
                        Box(Modifier.size(248.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = code?.readOut().orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.pairing_code_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(72.dp))

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                text = "FRIGO",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.pairing_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.pairing_how),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(420.dp),
            )
            if (trouble) {
                Text(
                    text = stringResource(R.string.pairing_trouble),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { attempt++ }) {
                    Text(stringResource(R.string.pairing_retry))
                }
            } else {
                Waiting()
            }
        }
    }
}

/** A dot that breathes, so a screen with nothing moving still says it is awake. */
@Composable
private fun Waiting() {
    val breathe = rememberInfiniteTransition(label = "waiting")
    val alpha by breathe.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_300), RepeatMode.Reverse),
        label = "dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.pairing_waiting),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProblemScreen(repository: TvRepository, refused: Boolean) {
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = if (refused) {
        stringResource(R.string.tv_refused_title)
    } else {
        stringResource(R.string.tv_server_down)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 160.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (refused) {
            Text(
                text = stringResource(R.string.tv_refused_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (working) return@Button
                working = true
                // The repository owns the sign-in; the screen only asks it to
                // try again, or to forget the TV so the code comes back.
                scope.launch {
                    if (refused) repository.forget() else repository.start()
                    working = false
                }
            },
            enabled = !working,
        ) {
            Text(
                stringResource(
                    if (refused) R.string.tv_again else R.string.pairing_retry,
                ),
            )
        }
    }
}

/** For the two screens that say one thing: no network, or no cameras. */
@Composable
private fun CenteredNote(title: String, button: String? = null, onButton: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 160.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (button != null) {
            Spacer(Modifier.height(28.dp))
            Button(onClick = onButton) { Text(button) }
        }
    }
}
