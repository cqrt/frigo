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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.UntrustedCertificateException
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
    buttonLabel: String = stringResource(R.string.login_sign_in),
    onSignedIn: () -> Unit = {},
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var shown by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(notice) { mutableStateOf(notice) }
    // The one certificate the server offered, waiting on a yes or no.
    var untrusted by remember { mutableStateOf<UntrustedCertificateException?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Only the address has to be there. A server with sign-in turned off has
    // nothing to type in the other two, and it is the first thing a stranger meets.
    val ready = address.isNotBlank() && !busy

    fun submit(trust: Pair<String, String>? = null) {
        if (!ready) return
        scope.launch {
            busy = true
            error = null
            runCatching { repository.signIn(address, username, password, trust) }
                .onSuccess { onSignedIn() }
                .onFailure { failure ->
                    if (failure is UntrustedCertificateException) {
                        untrusted = failure
                    } else {
                        error = failure.friendlyMessage(context)
                    }
                }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field(
            value = address,
            onValueChange = { address = it },
            label = stringResource(R.string.login_address),
            icon = FrigoIcons.Server,
            placeholder = stringResource(R.string.login_address_placeholder),
            supporting = stringResource(R.string.login_address_note),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )

        Field(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.login_username),
            icon = FrigoIcons.Person,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        Field(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.login_password),
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
                        contentDescription = if (shown) {
                            stringResource(R.string.login_hide_password)
                        } else {
                            stringResource(R.string.login_show_password)
                        },
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
            onClick = { submit() },
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
            text = stringResource(R.string.login_keeps_sign_in),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The two things a stranger meets and the page can save them looking up:
        // a server that wants no account, and Frigate reached through the add-on.
        Text(
            text = stringResource(R.string.login_no_account_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.login_addon_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // A certificate the phone won't take is the common case at home, and the one
    // thing a browser does that an app can't do quietly. It is asked once, for the
    // one address, and the fingerprint is what makes that safe.
    untrusted?.let { offered ->
        AlertDialog(
            onDismissRequest = { untrusted = null },
            title = { Text(stringResource(R.string.login_certificate_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.login_certificate_detail, offered.address))
                    Text(
                        text = offered.fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val offeredAddress = offered.address
                        val fingerprint = offered.fingerprint
                        untrusted = null
                        submit(offeredAddress to fingerprint)
                    },
                ) {
                    Text(stringResource(R.string.login_certificate_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { untrusted = null }) {
                    Text(stringResource(R.string.login_certificate_no))
                }
            },
        )
    }
}

/**
 * One line of the form: an icon, the name of the thing, and what is typed in it.
 * It is a filled block rather than a box with an outline, which is how every
 * other card in the app is drawn.
 *
 * The password question under Stay signed in asks with the same block, so a
 * password is typed in one place and looks the same wherever it is asked for.
 */
@Composable
internal fun Field(
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
