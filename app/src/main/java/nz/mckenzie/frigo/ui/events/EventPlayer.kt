package nz.mckenzie.frigo.ui.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer

/**
 * One event, filling the screen: the clip if Frigate kept one, and the still if
 * it didn't.
 *
 * Frigate builds an event's clip out of its recordings when it is asked for one,
 * so the clip is only there for as long as the recordings are. An event older
 * than the recording retention has a still and nothing else.
 */
@Composable
fun EventPlayerDialog(
    repository: FrigoRepository,
    event: FrigateEvent,
    onDismiss: () -> Unit,
) {
    // Frigate hands out the clip's address and the token it needs; the player then
    // fetches the clip itself.
    val subject = remember(event.id, event.hasClip) {
        ClipSubject(
            title = event.title(),
            subtitle = "${prettyCameraName(event.camera)} · ${relativeTime(event.startedAtMillis)}",
            clip = if (event.hasClip) {
                runCatching { repository.clipSource(event.id) }.getOrNull()
            } else {
                null
            },
            stillKey = "snapshot-${event.id}",
            still = { repository.snapshot(event.id) },
            note = if (event.hasClip) null else "Frigate kept no clip for this one.",
        )
    }

    ClipViewer(subject = subject, onDismiss = onDismiss)
}
