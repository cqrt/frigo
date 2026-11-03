package nz.mckenzie.frigo.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.friendlyMessage

/**
 * The server address, the username and the password, and the button that signs
 * in. The first run walks past this, and the app shows it on its own when a
 * session runs out, so the two are one piece of code rather than two.
 */
@Composable
fun SignInForm(
    repository: FrigoRepository,
    notice: String? = null,
    initialAddress: String = "",
    initialUsername: String = "",
    buttonLabel: String = "Sign in",
    onSignedIn: () -> Unit = {},
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(notice) { mutableStateOf(notice) }
    val scope = rememberCoroutineScope()

    val ready = address.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !busy

    fun submit() {
        if (!ready) return
        scope.launch {
            busy = true
            error = null
            runCatching { repository.signIn(address, username, password) }
                .onSuccess { onSignedIn() }
                .onFailure { error = it.friendlyMessage() }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            value = address,
            onValueChange = { address = it },
            label = "Server address",
            icon = FrigoIcons.Server,
            placeholder = "https://frigate.example.com",
            supporting = "The address you use in a browser. Add the port if there is one, such as :8971.",
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        Field(
            value = username,
            onValueChange = { username = it },
            label = "Username",
            icon = FrigoIcons.Person,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        Field(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            icon = FrigoIcons.Lock,
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
                        contentDescription = if (shown) "Hide the password" else "Show the password",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )

        if (error != null) {
            Text(
                text = error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(2.dp))

        Button(
            onClick = ::submit,
            enabled = ready,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(buttonLabel)
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = "Frigo keeps your sign-in on this phone only. It talks to your server and nothing else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One line of the form: an icon, the name of the thing, and what is typed in it.
 * It is a filled block rather than a box with an outline, which is how every
 * other card in the app is drawn.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardOptions: KeyboardOptions,
    placeholder: String? = null,
    supporting: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.surfaceContainerHighest)
            // The ring the line wears while you are in it. It is there all the
            // time and merely clear, so the text doesn't shift by two pixels
            // the moment the field takes the cursor.
            .border(
                width = 2.dp,
                color = if (focused) ink.primary else Color.Transparent,
                shape = shape,
            )
            .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) ink.primary else ink.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) ink.primary else ink.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink.onSurface),
                cursorBrush = SolidColor(ink.primary),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = ink.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.align(Alignment.CenterVertically)) { trailing() }
        }
    }
}
