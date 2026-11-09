package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Item
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.components.OpenMark
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.relativeTime

/**
 * What the server does with the pictures it already has: describe what it saw.
 *
 * The switch here is the server's own setting, saved as you tap it. The model
 * behind it is set up on the page below, and the pictures go from the server to
 * that model rather than from this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDescriptionsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenModel: () -> Unit,
    viewModel: AiDescriptionsViewModel = viewModel(
        factory = AiDescriptionsViewModel.factory(container),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val setup = state.setup
    val service = state.service

    // Coming back from the model page is a reason to ask the server again.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ai_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        when {
            setup == null && state.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            setup == null -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.error ?: stringResource(R.string.ai_cant_read_settings),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = viewModel::load) { Text(stringResource(R.string.ai_try_again)) }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The phone's own copy, so the page opens with what is set while
                // the server is away. Nothing can be sent, so nothing can be tapped.
                val stale = setup.readAt
                if (stale != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(
                                R.string.ai_stale,
                                relativeTime(stale).lowercase(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = attention(),
                        )
                        TextButton(onClick = viewModel::load) {
                            Text(stringResource(R.string.ai_try_again))
                        }
                    }
                }

                if (!setup.admin) {
                    Text(
                        text = stringResource(R.string.ai_only_admin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Group(stringResource(R.string.ai_alerts)) {
                    Item(
                        icon = FrigoIcons.Sparkle,
                        title = stringResource(R.string.ai_describe_alerts),
                        supporting = if (service == null) {
                            stringResource(R.string.ai_describe_no_model)
                        } else {
                            null
                        },
                        // With nothing set up there is nothing to switch, so the
                        // line leads to the model instead.
                        onClick = if (service == null) onOpenModel else null,
                        trailing = if (service != null) {
                            {
                                Switch(
                                    checked = state.on,
                                    onCheckedChange = { on ->
                                        if (state.editable) viewModel.setOn(on)
                                    },
                                    enabled = state.editable,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }

                Group(stringResource(R.string.ai_model)) {
                    Item(
                        icon = FrigoIcons.Server,
                        title = service?.model?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.ai_set_up_model),
                        value = service?.where(LocalContext.current)?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.ai_no_model),
                        onClick = onOpenModel,
                        trailing = { OpenMark() },
                    )
                }

                if (service != null) {
                    Note(stringResource(R.string.ai_pictures_note))
                }
            }
        }
    }
}

/**
 * The address and the model your server describes with.
 *
 * A server with nothing set up gets one made for it. One already set up keeps
 * what it had and only changes these two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AiModelViewModel = viewModel(factory = AiModelViewModel.factory(container)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ai_model)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.editable) {
                    Text(
                        text = stringResource(R.string.ai_only_admin),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Group(stringResource(R.string.ai_ollama_heading)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Field(
                            value = state.address,
                            onValueChange = viewModel::setAddress,
                            label = stringResource(R.string.ai_field_address),
                            placeholder = "http://192.168.1.10:11434",
                            keyboardType = KeyboardType.Uri,
                            enabled = state.editable,
                        )
                        Field(
                            value = state.model,
                            onValueChange = viewModel::setModel,
                            label = stringResource(R.string.ai_model),
                            placeholder = "llava",
                            enabled = state.editable,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = viewModel::save,
                                enabled = state.editable && !state.saving,
                            ) {
                                Text(
                                    stringResource(
                                        if (state.saving) R.string.ai_saving
                                        else R.string.ai_save,
                                    ),
                                )
                            }
                        }
                    }
                }

                Note(stringResource(R.string.ai_ollama_note))

                Group(stringResource(R.string.ai_or)) {
                    Item(
                        icon = FrigoIcons.Server,
                        title = stringResource(R.string.ai_cloud),
                        supporting = stringResource(R.string.ai_cloud_supporting),
                    )
                }
            }
        }
    }
}

/**
 * One line of the form: the name of the thing and what is typed in it.
 *
 * A filled block rather than a box with an outline, which is how every other
 * card in the app is drawn.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.surfaceContainerHighest)
            .border(
                width = 2.dp,
                color = if (focused) ink.primary else Color.Transparent,
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) ink.primary else ink.onSurfaceVariant,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink.onSurface),
            cursorBrush = SolidColor(ink.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            },
        )
    }
}
