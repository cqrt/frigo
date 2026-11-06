package nz.mckenzie.frigo.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CameraMenu
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.SearchField
import nz.mckenzie.frigo.ui.components.prettyLabel

/**
 * The objects this phone lets buzz: a camera to narrow by, a search box, and a
 * row a checkbox in.
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
    var query by remember { mutableStateOf("") }

    // Done writes what is ticked for the camera being set, and closes. Leaving for
    // another camera keeps that choice too, which is what makes this per camera.
    fun keep(labels: Set<String>) {
        viewModel.save(labels)
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The chip names the camera being set. There is one camera at a time:
            // each keeps its own list, so "all cameras" has nothing to mean.
            if (state.cameras.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    CameraMenu(
                        cameras = state.cameras,
                        chosen = state.scope,
                        onChoose = { camera -> camera?.let(viewModel::show) },
                        all = false,
                    )
                }
            }

            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.alert_objects_search),
            )

            Group(stringResource(R.string.revset_objects)) {
                if (state.noAnswer) {
                    Note(stringResource(R.string.alert_objects_cant_read))
                }

                // Every object this camera alerts on is ticked until one is taken
                // off, so the page opens reading as everything, which is what an
                // unset camera does.
                if (nothingTicked) {
                    Note(stringResource(R.string.alert_objects_hint))
                }

                listed.forEach { label ->
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Tag,
                        title = prettyLabel(label),
                        onClick = { viewModel.toggle(label) },
                        // The row does the ticking, so every row is the same height
                        // and a tap anywhere on it counts, as the switches do.
                        trailing = {
                            Checkbox(
                                checked = label in state.chosen,
                                onCheckedChange = null,
                            )
                        },
                    )
                }

                // Said in the card, under the list, because it is about every row
                // above it rather than about one of them.
                if (!state.loading) {
                    CardDivider()
                    Note(stringResource(R.string.alert_objects_phone_only))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What the Settings row says under Only these objects: everything, the objects by
 * name, or that the cameras differ.
 */
@Composable
internal fun AlertObjects.said(): String = when {
    everything -> stringResource(R.string.alert_objects_everything)
    cameras.isNotEmpty() -> stringResource(R.string.alert_objects_per_camera)
    else -> {
        val names = labels.sorted().map { prettyLabel(it) }
        when (names.size) {
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
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }
