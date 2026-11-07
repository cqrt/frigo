package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.LogLevel
import nz.mckenzie.frigo.data.LogLine
import nz.mckenzie.frigo.data.LogService
import nz.mckenzie.frigo.data.narrowed
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.SearchField
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.dateFormat
import java.time.LocalDate

/**
 * What frigate, go2rtc and nginx have been saying, newest at the bottom.
 *
 * The page reads the whole log once, then follows the server's own feed so new
 * lines arrive as they are written. Following pins the list to the newest line;
 * Paused leaves it where you are reading and lets the lines collect below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: LogsViewModel = viewModel(factory = LogsViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The feed belongs to the page being looked at: leaving it closes the response.
    LifecycleResumeEffect(Unit) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }

    val visible = remember(state.lines, state.level, state.query) {
        state.lines.narrowed(state.level, state.query)
    }
    val filtering = state.level != null || state.query.isNotBlank()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_logs)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                FilterChip(
                    selected = state.following,
                    onClick = { viewModel.setFollowing(!state.following) },
                    label = {
                        Text(
                            stringResource(
                                if (state.following) R.string.logs_following else R.string.logs_paused,
                            ),
                        )
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LogService.entries.forEachIndexed { index, service ->
                    SegmentedButton(
                        selected = state.service == service,
                        onClick = { viewModel.choose(service) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LogService.entries.size,
                        ),
                    ) {
                        // The server's own name for it, lower case, in every language.
                        Text(service.id)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelChip(
                    label = stringResource(R.string.logs_all),
                    selected = state.level == null,
                    onClick = { viewModel.setLevel(null) },
                )
                LevelChip(
                    label = stringResource(R.string.logs_errors),
                    selected = state.level == LogLevel.Error,
                    onClick = { viewModel.setLevel(LogLevel.Error) },
                )
                LevelChip(
                    label = stringResource(R.string.logs_warnings),
                    selected = state.level == LogLevel.Warning,
                    onClick = { viewModel.setLevel(LogLevel.Warning) },
                )
                LevelChip(
                    label = stringResource(R.string.logs_info),
                    selected = state.level == LogLevel.Info,
                    onClick = { viewModel.setLevel(LogLevel.Info) },
                )
                LevelChip(
                    label = stringResource(R.string.logs_debug),
                    selected = state.level == LogLevel.Debug,
                    onClick = { viewModel.setLevel(LogLevel.Debug) },
                )
            }

            SearchField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.logs_search),
            )

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                when {
                    state.error != null && state.lines.isEmpty() -> ErrorPane(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::retry,
                    )

                    state.loading && state.lines.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )

                    else -> LogCard(
                        state = state,
                        visible = visible,
                        filtering = filtering,
                        onClear = viewModel::clearFilters,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry) { Text(stringResource(R.string.system_try_again)) }
    }
}

/**
 * One card around the lines, filling what is left of the page. The notes sit
 * above the list rather than in it, so a feed that has stopped is not scrolled
 * off the bottom the moment a line arrives.
 */
@Composable
private fun LogCard(
    state: LogsUiState,
    visible: List<LogLine>,
    filtering: Boolean,
    onClear: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.dropped) {
                Note(stringResource(R.string.logs_older_gone))
            }
            if (state.feedStopped) {
                Note(stringResource(R.string.logs_feed_stopped))
            }

            if (visible.isEmpty() && filtering) {
                NothingMatches(onClear, Modifier.weight(1f))
            } else {
                LineList(visible, state.following, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NothingMatches(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.logs_no_match),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.logs_clear)) }
    }
}

@Composable
private fun LineList(lines: List<LogLine>, following: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // A day is named once, at its first line. A container line with no date can
    // sit between two dated ones, and it must not put the day back on the screen.
    val dayStarts = remember(lines) {
        var shown: String? = null
        lines.map { line ->
            val start = line.date != null && line.date != shown
            if (line.date != null) shown = line.date
            start
        }
    }

    // Following is a switch rather than something a scroll turns off, so this is
    // the whole of it: while it is on, the newest line is where the list sits.
    LaunchedEffect(following, lines) {
        if (following && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            if (dayStarts[index]) {
                line.date?.let { DayLabel(it) }
            }
            LogRow(line)
        }
    }
}

/** The day between one stretch of lines and the next, in the app's language. */
@Composable
private fun DayLabel(date: String) {
    val format = dateFormat("d MMM")
    val said = remember(date, format) {
        runCatching { format.format(LocalDate.parse(date)) }.getOrDefault(date)
    }
    Text(
        text = said,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** The time and level on one line, the message under it. */
@Composable
private fun LogRow(line: LogLine) {
    val ink = MaterialTheme.colorScheme
    val colour = when (line.level) {
        LogLevel.Error -> ink.error
        LogLevel.Warning -> attention()
        else -> ink.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (line.time != null || line.level != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                line.time?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = ink.onSurfaceVariant,
                    )
                }
                line.level?.let {
                    Text(
                        text = it.said(),
                        style = MaterialTheme.typography.labelSmall,
                        color = when (it) {
                            LogLevel.Error -> ink.error
                            LogLevel.Warning -> attention()
                            else -> ink.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Text(
            text = line.message,
            style = MaterialTheme.typography.bodyMedium,
            color = colour,
        )
    }
}

/** The server's word for a level, as it writes it, in every language. */
private fun LogLevel.said(): String = when (this) {
    LogLevel.Error -> "ERROR"
    LogLevel.Warning -> "WARNING"
    LogLevel.Info -> "INFO"
    LogLevel.Debug -> "DEBUG"
}
