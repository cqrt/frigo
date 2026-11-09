package nz.mckenzie.frigo.ui.clips

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Export
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.components.megabytes
import nz.mckenzie.frigo.ui.cameras.stamp
import nz.mckenzie.frigo.ui.friendlyMessage
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The clips the server is holding for you, newest first.
 *
 * These are the ones Frigate cut out and kept itself, so they are still here after
 * its clean-up has taken the recordings they came from. Each one plays, can be
 * copied to the phone, can be given another name, and can be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: ClipsViewModel = viewModel(factory = ClipsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val repository = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var opened by remember { mutableStateOf<Export?>(null) }
    var renaming by remember { mutableStateOf<Export?>(null) }
    var deleting by remember { mutableStateOf<Export?>(null) }
    // The clip waiting for a place to be chosen, then the one being copied, and how
    // much of it has been written, so the row says what it is doing rather than
    // going quiet for a moment.
    var pending by remember { mutableStateOf<Export?>(null) }
    var saving by remember { mutableStateOf<Export?>(null) }
    var savedBytes by remember { mutableLongStateOf(0L) }
    var savedId by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    // The list is asked for again whenever the screen comes back, since a clip may
    // have been kept from another tab while this one sat in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    val saveScreen = remember { ActivityResultContracts.CreateDocument("video/mp4") }
    val askPlace = rememberLauncherForActivityResult(saveScreen) { place ->
        val export = pending
        pending = null
        if (place == null || export == null) return@rememberLauncherForActivityResult
        saving = export
        savedBytes = 0L
        scope.launch {
            val outcome = runCatching {
                val file = repository.exportFile(export) { savedBytes = it }
                withContext(Dispatchers.IO) { write(context, file, place) }
            }
            saving = null
            savedBytes = 0L
            outcome
                .onSuccess { savedId = export.id }
                .onFailure { problem = it.friendlyMessage(context) }
        }
    }

    // "Saved" is said in the row for a moment, then the row goes back to itself.
    LaunchedEffect(savedId) {
        if (savedId == null) return@LaunchedEffect
        delay(2_500)
        savedId = null
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.clips_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        val said = problem ?: state.problem
        if (said != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = said,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        problem = null
                        viewModel.dismissProblem()
                    },
                ) {
                    Text(stringResource(R.string.clips_dismiss))
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = state.loading && state.exports.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading && state.exports.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.exports.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.clips_try_again))
                    }
                }

                state.exports.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.clips_nothing_saved),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.exports, key = { it.id }) { export ->
                        ClipRow(
                            repository = repository,
                            export = export,
                            savingBytes = if (saving?.id == export.id) savedBytes else null,
                            saved = savedId == export.id,
                            onClick = { opened = export },
                            onRename = { renaming = export },
                            onSave = {
                                pending = export
                                askPlace.launch(saveName(export))
                            },
                            onDelete = { deleting = export },
                        )
                    }
                }
            }
        }
    }

    opened?.let { export ->
        ClipViewer(
            subject = ClipSubject(
                title = export.name.ifBlank { prettyCameraName(export.camera) },
                subtitle = "${prettyCameraName(export.camera)} · " +
                    stamp(export.date, ZoneId.systemDefault()),
                clip = { onProgress -> repository.exportFile(export, onProgress) },
                stillKey = "kept-${export.id}",
                still = { repository.exportThumbnail(export) },
            ),
            onDismiss = { opened = null },
        )
    }

    renaming?.let { export ->
        RenameDialog(
            export = export,
            onDismiss = { renaming = null },
            onRename = { name ->
                viewModel.rename(export, name)
                renaming = null
            },
        )
    }

    deleting?.let { export ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.clips_delete_title)) },
            text = {
                Text(stringResource(R.string.clips_delete_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(export)
                        deleting = null
                    },
                ) {
                    Text(
                        stringResource(R.string.clips_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.clips_cancel))
                }
            },
        )
    }
}

/** One kept clip: its picture, what it is called, and what can be done with it. */
@Composable
private fun ClipRow(
    repository: FrigoRepository,
    export: Export,
    savingBytes: Long?,
    saved: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            // A clip still being cut has nothing to play yet, so the row waits.
            .clickable(enabled = !export.inProgress, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The same corners as the small camera tiles under the live picture.
            Box(Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp))) {
                CachedImage(
                    cacheKey = "kept-${export.id}",
                    modifier = Modifier
                        .size(width = 112.dp, height = 63.dp)
                        .aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                    load = { repository.exportThumbnail(export) },
                )
                if (export.inProgress) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = export.name.ifBlank { prettyCameraName(export.camera) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = prettyCameraName(export.camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // When the stretch is from, unless its name already says so: a clip
                // the app kept is named after the moment, and the row says it once.
                val at = stamp(export.date, ZoneId.systemDefault())
                val note = when {
                    export.inProgress -> stringResource(R.string.clips_cutting)
                    savingBytes != null && savingBytes > 0 ->
                        stringResource(R.string.clips_saving_bytes, megabytes(savingBytes))
                    savingBytes != null -> stringResource(R.string.clips_saving)
                    saved -> stringResource(R.string.clips_saved)
                    export.name.endsWith(at) -> null
                    else -> at
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!export.inProgress) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.clips_menu),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clips_save_to_phone)) },
                            onClick = {
                                menu = false
                                onSave()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clips_rename)) },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.clips_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            } else {
                Box(Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun RenameDialog(export: Export, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(export.id) { mutableStateOf(export.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clips_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(120) },
                singleLine = true,
                label = { Text(stringResource(R.string.clips_rename_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank() && name.trim() != export.name,
            ) {
                Text(stringResource(R.string.clips_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.clips_cancel))
            }
        },
    )
}

/** Writes a clip into the place the phone's save screen handed back. */
private fun write(context: Context, clip: File, place: Uri) {
    val out = context.contentResolver.openOutputStream(place)
        ?: throw IOException("nowhere to write it")
    out.use { sink -> clip.inputStream().use { it.copyTo(sink) } }
}

/**
 * The name a kept clip is offered under on the phone: the camera and the moment it
 * was kept, the same way a clip saved from a recording is named.
 */
private fun saveName(export: Export, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochSecond(export.date.toLong()).atZone(zone)
    return "frigo-${export.camera}-${at.format(SavedStamp)}.mp4"
}

private val SavedStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm")
