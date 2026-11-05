package nz.mckenzie.frigo.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.FrigoTabs

/**
 * The cover over everything, until the phone says who is holding it.
 *
 * It is drawn over the app rather than instead of it, so the cameras underneath
 * have already loaded when the cover goes. The cover itself is solid, in the
 * app's own colours, with the tab bar drawn faintly at the bottom.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val canAsk = rememberLockAvailable()
    val ask = rememberAskForLock(onUnlock)

    // The prompt comes up by itself, so the cover is never a button you have to
    // find first. Cancelled, it stays, and Unlock asks again.
    LaunchedEffect(Unit) { if (canAsk) ask() }

    // Back is not a way past the cover. Home still leaves the app, which is the
    // way out of any app.
    BackHandler { }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .blocksTouches(),
        )

        // The tabs, drawn rather than shown. The real bar is behind the cover,
        // and a cover that lets what is behind it be read is not a cover.
        TabBarPreview(Modifier.align(Alignment.BottomCenter))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = FrigoIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )

            Spacer(Modifier.height(6.dp))
            Text(
                // A phone that has lost its screen lock has nothing to ask for,
                // and saying so beats a button that can never work.
                text = if (canAsk) {
                    stringResource(R.string.lock_locked)
                } else {
                    stringResource(R.string.lock_no_screen_lock)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    // Asked again rather than trusted: the phone's screen lock can
                    // be taken off while the cover is up, and a prompt with nothing
                    // behind it is a crash.
                    if (lockAvailable(context)) ask() else onUnlock()
                },
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.lock_unlock))
            }
        }
    }
}

/**
 * The five tabs, faintly, where the bar sits.
 *
 * It is a drawing rather than the real bar, because the real one is behind the
 * cover. What is left is the shape of where you are about to land.
 */
@Composable
private fun TabBarPreview(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(80.dp)
            .alpha(0.3f),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrigoTabs.forEach { tab ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(tab.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Eats every touch, so nothing under the cover can be tapped, dragged or
 * scrolled. Being a hit target at all is most of it: the screens below never see
 * the finger.
 */
private fun Modifier.blocksTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}
