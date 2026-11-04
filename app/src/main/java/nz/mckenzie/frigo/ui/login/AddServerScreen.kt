package nz.mckenzie.frigo.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nz.mckenzie.frigo.data.FrigoRepository

/**
 * Another server, added from the list in Settings.
 *
 * It is the same form as the first sign-in, because signing in to a server is
 * the same thing wherever it is done. The server is added and becomes the one
 * you are on, so the tabs come back showing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    repository: FrigoRepository,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Add a server") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Sign in to another Frigate server. The one you are on keeps its sign-in, " +
                    "so you can switch back from Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            SignInForm(
                repository = repository,
                buttonLabel = "Add",
            )
        }
    }
}
