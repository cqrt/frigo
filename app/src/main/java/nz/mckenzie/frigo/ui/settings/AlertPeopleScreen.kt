package nz.mckenzie.frigo.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AlertPeople
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.SearchField

/**
 * The people this phone stays quiet about: a search box, and a row a checkbox in.
 *
 * Frigate names a recognised person on the alert it sends, and this is the list of
 * names the phone holds back. Ticked means left out, so Everyone is the list with
 * nobody on it, and that is what the page starts on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertPeopleScreen(
    repository: FrigoRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: AlertPeopleViewModel = viewModel(
        factory = AlertPeopleViewModel.factory(repository),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // The write happens when you leave the page rather than on every tap: the list
    // is written once, and unticking the last person can never mean "everyone".
    fun keep(names: Set<String>) {
        scope.launch { viewModel.save(names) }
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
    val nobodyTicked = state.chosen.isEmpty()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.alert_people_title)) },
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
                    enabled = !nobodyTicked,
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
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.alert_people_search),
            )

            Group(stringResource(R.string.explore_people)) {
                if (state.noAnswer) {
                    Note(stringResource(R.string.alert_people_cant_read))
                }

                // Everyone is the list with nobody on it, which is how the app
                // behaves without one. It clears the list rather than adding to it.
                Item(
                    icon = FrigoIcons.Grid,
                    title = stringResource(R.string.alert_people_everyone),
                    onClick = { keep(emptySet()) },
                    trailing = {
                        Checkbox(checked = nobodyTicked, onCheckedChange = null)
                    },
                )

                // Nothing ticked is a state Done won't write, so the card says what
                // to do about it rather than leaving the button greyed with no reason.
                if (nobodyTicked) {
                    Note(stringResource(R.string.alert_people_hint))
                }

                listed.forEach { name ->
                    CardDivider()
                    Item(
                        icon = FrigoIcons.Person,
                        title = name,
                        onClick = { viewModel.toggle(name) },
                        // The row does the ticking, so every row is the same height
                        // and a tap anywhere on it counts.
                        trailing = {
                            Checkbox(
                                checked = name in state.chosen,
                                onCheckedChange = null,
                            )
                        },
                    )
                }

                if (!state.loading) {
                    CardDivider()
                    if (!state.noAnswer && state.offered.isEmpty()) {
                        // A server with no faces learned yet has nothing to offer,
                        // and "Everyone" alone would look like a page that failed.
                        Note(stringResource(R.string.explore_no_people))
                    } else {
                        Note(stringResource(R.string.alert_objects_phone_only))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * What the Settings row says under People to leave out: everyone, or the people by
 * name.
 */
@Composable
internal fun AlertPeople.said(): String {
    if (everyone) return stringResource(R.string.alert_people_everyone)
    val names = leftOut.sorted()
    return when (names.size) {
        1 -> names[0]
        2 -> stringResource(
            R.string.alerts_two_objects,
            names[0],
            names[1].replaceFirstChar { it.lowercase() },
        )
        else -> stringResource(
            R.string.alerts_more_objects,
            names[0],
            names[1].replaceFirstChar { it.lowercase() },
            names.size - 2,
        )
    }
}
