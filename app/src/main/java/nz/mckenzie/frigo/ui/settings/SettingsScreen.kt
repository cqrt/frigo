package nz.mckenzie.frigo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.BuildConfig
import nz.mckenzie.frigo.data.FrigoRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: FrigoRepository,
    baseUrl: String,
    username: String,
) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleSmall)
            Row(label = "Address", value = baseUrl)
            Row(label = "Signed in as", value = username)

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Live view", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Live view asks the server for a stream of still frames over the same connection " +
                    "as the rest of the app. It works wherever your browser works, with no extra ports. " +
                    "You can trade latency for bandwidth in the player's menu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Button(
                onClick = { scope.launch { repository.signOut() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }

            Text(
                text = "Frigo ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
