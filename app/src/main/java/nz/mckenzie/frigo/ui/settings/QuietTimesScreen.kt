package nz.mckenzie.frigo.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.QuietTimes
import nz.mckenzie.frigo.data.clockTime
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note

/**
 * The two times this phone keeps quiet, and the cameras it keeps quiet between
 * them.
 *
 * It is the phone's own page rather than one of the server's: what it changes is
 * what this phone tells you, and your server and its other phones don't move.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietTimesScreen(
    repository: FrigoRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val quiet by repository.quietTimes.collectAsStateWithLifecycle()

    var cameras by remember { mutableStateOf<List<Camera>>(emptyList()) }
    var offline by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Edit?>(null) }

    LaunchedEffect(Unit) {
        // The server's answer, or the copy the phone last read, so the list is
        // there with no connection. Telling them apart is the note above it.
        runCatching { repository.cameraSwitchPage() }
            .onSuccess { cameras = it.cameras }
            .onFailure {
                offline = true
                runCatching { repository.cameras() }.onSuccess { cameras = it }
            }
    }

    fun save(next: QuietTimes) {
        scope.launch { repository.setQuietTimes(next) }
    }

    val now = System.currentTimeMillis()
    val hushing = quiet.hushUntil > now

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Quiet times") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("When") {
                Item(
                    icon = FrigoIcons.Quiet,
                    title = "Quiet times",
                    trailing = {
                        Switch(
                            checked = quiet.on,
                            onCheckedChange = { on -> save(quiet.copy(on = on)) },
                        )
                    },
                )

                if (quiet.on) {
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Clock,
                        title = "From",
                        value = clockTime(quiet.from),
                        onClick = { editing = Edit.From },
                    )
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Clock,
                        title = "Until",
                        value = clockTime(quiet.until),
                        onClick = { editing = Edit.Until },
                    )
                    if (quiet.from == quiet.until) {
                        Note("From and Until are the same, so this is quiet all day.")
                    }
                }

                CardDivider()

                // The hush sits with the times because it leaves them alone: it is
                // an hour of silence that ends by itself.
                Item(
                    icon = FrigoIcons.Quiet,
                    title = if (hushing) "Quiet until ${clockTime(quiet.hushUntil)}" else "Quiet for an hour",
                    supporting = if (hushing) {
                        "Every camera is silent until then."
                    } else {
                        "Every camera, for the next hour."
                    },
                    onClick = if (hushing) {
                        null
                    } else {
                        { save(quiet.copy(hushUntil = now + Hour)) }
                    },
                    trailing = {
                        if (hushing) {
                            TextButton(onClick = { save(quiet.copy(hushUntil = 0L)) }) {
                                Text("Stop")
                            }
                        }
                    },
                )
            }

            Group("Cameras") {
                when {
                    offline -> Note(
                        "Your server isn't answering, so this is the list your phone last read."
                    )

                    cameras.isEmpty() -> Note("No camera this account can see.")
                }

                cameras.forEachIndexed { index, camera ->
                    if (index > 0) CardDivider()
                    Item(
                        icon = FrigoIcons.Camera,
                        title = camera.title,
                        trailing = {
                            Switch(
                                checked = camera.name in quiet.cameras,
                                onCheckedChange = { on ->
                                    save(
                                        quiet.copy(
                                            cameras = if (on) {
                                                quiet.cameras + camera.name
                                            } else {
                                                quiet.cameras - camera.name
                                            },
                                        )
                                    )
                                },
                            )
                        },
                    )
                }

                if (cameras.isNotEmpty() && quiet.cameras.isEmpty()) {
                    Note("No camera is picked, so nothing will go quiet.")
                }
            }

            // Clear of the bottom of the page, so the last card is not against the bar.
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { which ->
        TimeDialog(
            initial = if (which == Edit.From) quiet.from else quiet.until,
            onDismiss = { editing = null },
            onPick = { minutes ->
                editing = null
                save(
                    if (which == Edit.From) {
                        quiet.copy(from = minutes)
                    } else {
                        quiet.copy(until = minutes)
                    }
                )
            },
        )
    }
}

/** Which of the two times is being picked. */
private enum class Edit { From, Until }

/**
 * A clock face that gives back minutes after midnight. It is the system's own
 * picker, so it follows the phone's 12 or 24 hour setting without being told.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TimePicker(state = state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

/** An hour, counted the way a hush is. */
private const val Hour = 60 * 60_000L
