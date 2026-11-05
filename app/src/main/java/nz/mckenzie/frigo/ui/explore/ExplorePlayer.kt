package nz.mckenzie.frigo.ui.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.relativeTime

/**
 * One event, filling the screen: the clip if Frigate kept one, and the still if
 * it didn't.
 *
 * Frigate builds an event's clip out of its recordings when it is asked for one,
 * so the clip is only there for as long as the recordings are. An event older
 * than the recording retention has a still and nothing else.
 */
@Composable
fun ExplorePlayerDialog(
    repository: FrigoRepository,
    event: FrigateEvent,
    onDismiss: () -> Unit,
) {
    // The line under the title, and the note for an event with no clip. Both are
    // read here, in a composable body, so the strings come out in the app's language.
    val subtitle = "${prettyCameraName(event.camera)} · ${relativeTime(event.startedAtMillis)}"
    val note = if (event.hasClip) null else stringResource(R.string.explore_no_clip)

    // Frigate hands out the clip's address and the token it needs; the repository
    // fetches it into a file, and the viewer plays that.
    val subject = remember(event.id, event.hasClip) {
        val source = if (event.hasClip) {
            runCatching { repository.clipSource(event.id) }.getOrNull()
        } else {
            null
        }
        ClipSubject(
            title = event.title(),
            subtitle = subtitle,
            clip = source?.let { clip ->
                { onProgress -> repository.clipFile(clip, "event-${event.id}", onProgress) }
            },
            stillKey = "snapshot-${event.id}",
            still = { repository.snapshot(event.id) },
            note = note,
        )
    }

    ClipViewer(subject = subject, onDismiss = onDismiss)
}
