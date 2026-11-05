package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.SystemLine
import nz.mckenzie.frigo.data.SystemMeter
import nz.mckenzie.frigo.data.SystemPage
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention

/**
 * How the server is doing: what it is running, how hard the box is working, how
 * each camera is keeping up, how full the disks are, and which of Frigate's
 * processes is taking the time.
 *
 * The numbers are the server's own, read again every few seconds while the page
 * is open. Only an admin is offered the restart at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SystemViewModel = viewModel(factory = SystemViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val page = state.page

    // The reading belongs to the page being looked at: leaving it stops the asking.
    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("System") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        when {
            page == null && state.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            page == null -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.error ?: "Can't read your server's stats.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) { Text("Try again") }
            }

            else -> Numbers(
                page = page,
                restarting = state.restarting,
                error = state.error,
                onRestart = viewModel::askToRestart,
            )
        }
    }

    if (state.asking) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRestart,
            title = { Text("Restart Frigate?") },
            text = {
                Text(
                    "Everything stops for about half a minute, and alerts with it. " +
                        "Recordings and settings are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::restart) {
                    Text("Restart", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestart) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Numbers(
    page: SystemPage,
    restarting: Boolean,
    error: String?,
    onRestart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (restarting) {
            Group("This server") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Restarting. It will be back in about half a minute.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Group("Server") {
            Fact("Version", page.version)
            LineDivider()
            Fact("Up for", page.uptime)
            page.newer?.let {
                LineDivider()
                Note("$it is out. Updating is done on the server, not from here.")
            }
        }

        if (page.processor != null || page.memory != null) {
            Group("Load") {
                val meters = listOfNotNull(page.processor, page.memory)
                meters.forEachIndexed { index, meter ->
                    if (index > 0) LineDivider()
                    MeterRow(meter)
                }
                page.gpu.forEach { line ->
                    LineDivider()
                    LineRow(line)
                }
            }
        }

        if (page.cameras.isNotEmpty()) {
            Group("Cameras") {
                Lines(page.cameras)
            }
        }

        if (page.storage.isNotEmpty()) {
            Group("Storage") {
                page.storage.forEachIndexed { index, meter ->
                    if (index > 0) LineDivider()
                    MeterRow(meter)
                }
            }
        }

        if (page.detectors.isNotEmpty() || page.processes.isNotEmpty()) {
            Group("Processes") {
                Lines(page.detectors + page.processes)
            }
        }

        if (page.restartable) {
            Group("This server") {
                Item(
                    icon = FrigoIcons.Server,
                    title = "Restart Frigate",
                    supporting = "Alerts stop for about half a minute",
                    colour = MaterialTheme.colorScheme.error,
                    onClick = onRestart,
                    trailing = { OpenMark() },
                )
            }
        }

        // Clear of the bottom bar, so the last card is not against it.
        Spacer(Modifier.height(24.dp))
    }
}

/** One line of a card: a name, what it is doing, and what it comes to. */
@Composable
private fun LineRow(line: SystemLine) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = line.title, style = MaterialTheme.typography.bodyLarge)
            if (line.said.isNotBlank()) {
                Text(
                    text = line.said,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.warn) attention() else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        line.value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A line with a bar under it. Amber once it is the kind of full worth noticing. */
@Composable
private fun MeterRow(meter: SystemMeter) {
    val colour = if (meter.warn) attention() else MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = meter.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = meter.said,
                style = MaterialTheme.typography.bodyMedium,
                color = if (meter.warn) attention() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val filled = meter.fraction.coerceIn(0f, 1f)
            if (filled > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(filled)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(colour),
                )
            }
        }
        meter.detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (meter.warn) attention() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A name and what it is set to, on one line. */
@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Lines(lines: List<SystemLine>) {
    Column {
        lines.forEachIndexed { index, line ->
            if (index > 0) LineDivider()
            LineRow(line)
        }
    }
}

@Composable
private fun LineDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}
