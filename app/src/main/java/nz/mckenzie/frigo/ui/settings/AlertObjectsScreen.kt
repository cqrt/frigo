package nz.mckenzie.frigo.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.CameraMenu
import nz.mckenzie.frigo.ui.components.CardDivider
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Narrowing by a camera changes what is on show, not what a tick means:
            // there is one list, and this is how you find one camera's part of it.
            if (state.cameras.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    CameraMenu(
                        cameras = state.cameras,
                        chosen = state.scope,
                        onChoose = viewModel::show,
                    )
                }
            }

            SearchField(value = query, onValueChange = { query = it })

            Group(stringResource(R.string.revset_objects)) {
                if (state.noAnswer) {
                    Note(stringResource(R.string.alert_objects_cant_read))
                }

                // Everything is the whole of what the server sends, which is what
                // an empty list means. It clears the list rather than adding to it.
                Item(
                    icon = FrigoIcons.Grid,
                    title = stringResource(R.string.alert_objects_all),
                    onClick = { keep(emptySet()) },
                    trailing = {
                        Checkbox(checked = nothingTicked, onCheckedChange = null)
                    },
                )

                // Nothing ticked is a state Done won't write, so the card says what
                // to do about it rather than leaving the button greyed with no reason.
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
 * A search box drawn the way the app's fields are: a filled block with a ring
 * while the cursor is in it, rather than an outlined box.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.surfaceContainerHighest)
            .border(
                width = 2.dp,
                color = if (focused) ink.primary else Color.Transparent,
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = FrigoIcons.Search,
            contentDescription = null,
            tint = if (focused) ink.primary else ink.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.revset_search),
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) ink.primary else ink.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink.onSurface),
                cursorBrush = SolidColor(ink.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.alert_objects_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ink.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
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
