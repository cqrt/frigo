package nz.mckenzie.frigo.ui.reviews

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.events.relativeTime

/**
 * One review, filling the screen with the clip of the time it covers. Frigate
 * cuts that clip from your recordings when it is asked for one.
 *
 * Past halfway the review is marked watched on the server, which is what the
 * Frigate web page does, so it leaves the list there too.
 */
@Composable
fun ReviewPlayerDialog(
    repository: FrigoRepository,
    review: FrigateReview,
    onDismiss: () -> Unit,
    onHalfWatched: () -> Unit,
) {
    val watched = rememberUpdatedState(onHalfWatched)
    val subject = remember(review.id) {
        val source = runCatching { repository.reviewClipSource(review.id) }.getOrNull()
        ClipSubject(
            title = review.title(),
            subtitle = "${prettyCameraName(review.camera)} · ${relativeTime(review.startedAtMillis)}",
            clip = source?.let { clip ->
                { onProgress -> repository.clipFile(clip, "review-${review.id}", onProgress) }
            },
            stillKey = "review-still-${review.id}",
            still = { repository.reviewThumbnail(review) },
            onHalfWatched = { watched.value() },
        )
    }

    ClipViewer(subject = subject, onDismiss = onDismiss)
}
