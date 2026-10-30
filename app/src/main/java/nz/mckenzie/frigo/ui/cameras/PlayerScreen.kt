package nz.mckenzie.frigo.ui.cameras

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.MjpegReader
import nz.mckenzie.frigo.ui.components.StatusBarIcons
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.IOException

/** The heights and rates offered in the player. Bigger means more data. */
private val Heights = listOf(1080, 720, 480)
private val Rates = listOf(15, 10, 5, 2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    repository: FrigoRepository,
    cameraName: String,
    title: String,
    onBack: () -> Unit,
) {
    var frame by remember(cameraName) { mutableStateOf<ImageBitmap?>(null) }
    var problem by remember(cameraName) { mutableStateOf<String?>(null) }
    var connecting by remember(cameraName) { mutableStateOf(true) }
    var height by rememberSaveable(cameraName) { mutableIntStateOf(720) }
    var rate by rememberSaveable(cameraName) { mutableIntStateOf(5) }
    var menuOpen by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(cameraName, height, rate) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            connecting = true
            problem = null
            while (isActive) {
                try {
                    repository.openMjpeg(cameraName, height, rate).use { response ->
                        val body = response.body ?: throw IOException("The server sent no stream")
                        val reader = MjpegReader(body.byteStream())
                        withContext(Dispatchers.IO) {
                            while (isActive) {
                                val bytes = reader.nextFrame() ?: break
                                val bitmap = MjpegReader.decode(bytes) ?: continue
                                withContext(Dispatchers.Main.immediate) {
                                    frame = bitmap.asImageBitmap()
                                    connecting = false
                                }
                            }
                        }
                    }
                    problem = "The stream stopped."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    problem = error.friendlyMessage()
                }
                connecting = true
                delay(2_000)
            }
        }
    }

    StatusBarIcons(darkIcons = false)

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Stream quality")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Heights.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("${option}p") },
                            onClick = { height = option; menuOpen = false },
                        )
                    }
                    Rates.forEach { option ->
                        DropdownMenuItem(
                            text = { Text("$option frames a second") },
                            onClick = { rate = option; menuOpen = false },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            frame?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (connecting) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = problem ?: "Connecting…",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (problem != null) {
                        Text(
                            text = "Retrying",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (frame != null) {
                Text(
                    text = "MJPEG · ${height}p · $rate fps",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
