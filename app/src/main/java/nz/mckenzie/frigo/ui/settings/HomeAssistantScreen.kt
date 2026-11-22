package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.components.Group
import nz.mckenzie.frigo.ui.components.Note
import nz.mckenzie.frigo.ui.login.Field

/**
 * Where Home Assistant is, and how to ask it.
 *
 * A camera's floodlight is a light in Home Assistant rather than anything Frigate
 * knows about, so the phone has to be told about the other box in the house once.
 * It is asked for here and kept on this phone: the token is not carried in a
 * settings backup, and nothing about it goes to the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAssistantScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: HomeAssistantViewModel = viewModel(
        factory = HomeAssistantViewModel.factory(container),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.ha_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Group(stringResource(R.string.ha_title)) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Field(
                        value = state.address,
                        onValueChange = viewModel::setAddress,
                        label = stringResource(R.string.ha_address),
                        icon = FrigoIcons.Server,
                        placeholder = stringResource(R.string.ha_address_placeholder),
                        supporting = stringResource(R.string.ha_address_note),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    Field(
                        value = state.token,
                        onValueChange = viewModel::setToken,
                        label = stringResource(R.string.ha_token),
                        icon = FrigoIcons.Key,
                        supporting = stringResource(R.string.ha_token_note),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (shown) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IconButton(onClick = { shown = !shown }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    imageVector = if (shown) FrigoIcons.EyeOff else FrigoIcons.Eye,
                                    contentDescription = stringResource(
                                        if (shown) {
                                            R.string.login_hide_password
                                        } else {
                                            R.string.login_show_password
                                        },
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = viewModel::save,
                            enabled = !state.saving,
                        ) {
                            Text(
                                stringResource(
                                    if (state.saving) R.string.ha_saving else R.string.ha_save,
                                ),
                            )
                        }
                    }
                }
            }

            Note(stringResource(R.string.ha_note))

            // A token is a secret, and one on the phone with no way off it is a
            // secret that stays there. It sits below the fields, out of the way of
            // the button that is pressed every other time.
            if (state.set) {
                TextButton(
                    onClick = viewModel::forget,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(stringResource(R.string.ha_forget))
                }
            }
        }
    }
}
