package nz.mckenzie.frigo.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.prettyLabel

/**
 * The objects this phone lets buzz: an Everything row, a search box, and a
 * checkbox a row.
 *
 * The list is the phone's own, so it stays editable with the server away, and a
 * tap here calls nothing. Done writes it and closes, and Everything clears it and
 * closes: the empty list means everything, so it is only ever reached on purpose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertObjectsScreen(
    repository: FrigoRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: AlertObjectsViewModel = viewModel(
        factory = AlertObjectsViewModel.factory(repository),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // The write happens when you leave the page rather than on every tap: the list
    // is written once, and unticking the last object can never mean "everything".
    fun keep(labels: Set<String>) {
        scope.launch { viewModel.save(labels) }
        onBack()
    }

    BackHandler(onBack = onBack)

    val text = query.trim()
    val listed = remember(state.offered, text) {
        if (text.isEmpty()) {
            state.offered
        } else {
            state.offered.filter { it.contains(text, ignoreCase = true) }
        }
    }
    val nothingTicked = state.chosen.isEmpty()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.alert_objects_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                TextButton(
                    onClick = { keep(state.chosen) },
                    enabled = !nothingTicked,
                ) {
                    Text(stringResource(R.string.revset_done))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // Everything is the whole of what the server sends, which is what an empty
        // list means. It clears the list rather than adding to it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { keep(emptySet()) }
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(
                checked = nothingTicked,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.alert_objects_all),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (state.noAnswer) {
            Note(stringResource(R.string.alert_objects_cant_read))
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.revset_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        if (nothingTicked) {
            Text(
                text = stringResource(R.string.alert_objects_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Nothing to say about a list that is still on its way.
        if (!state.loading && listed.isEmpty()) {
            Text(
                text = stringResource(R.string.revset_no_match),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(listed, key = { it }) { label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggle(label) }
                        .padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = label in state.chosen,
                        onCheckedChange = { viewModel.toggle(label) },
                    )
                    Text(
                        text = prettyLabel(label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item("phone") {
                Note(stringResource(R.string.alert_objects_phone_only))
            }
        }
    }
}

/**
 * What the Settings row says under Only these objects: everything, or the objects
 * by name.
 */
@Composable
internal fun AlertObjects.said(): String {
    if (everything) return stringResource(R.string.alert_objects_everything)
    val names = labels.sorted().map { prettyLabel(it) }
    return when (names.size) {
        1 -> names[0]
        2 -> stringResource(R.string.alerts_two_objects, names[0], names[1].lowerFirst())
        else -> stringResource(
            R.string.alerts_more_objects,
            names[0],
            names[1].lowerFirst(),
            names.size - 2,
        )
    }
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
