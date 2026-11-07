package nz.mckenzie.frigo.ui.reviews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.aiTitle
import nz.mckenzie.frigo.data.description
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.relativeTime

/**
 * One review, filling the screen with the clip of the time it covers. Frigate
 * cuts that clip from your recordings when it is asked for one.
 *
 * Under the clip sits what Frigate made of it, when the server has written any:
 * a name for what happened, and the whole story under it. A review it hasn't
 * described yet opens on the clip alone, and the card appears if the words
 * arrive while you are watching.
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
    val title = review.title()
    val subtitle = stringResource(
        R.string.reviews_player_subtitle,
        prettyCameraName(review.camera),
        relativeTime(review.startedAtMillis),
    )
    val subject = remember(review.id) {
        val source = runCatching { repository.reviewClipSource(review.id) }.getOrNull()
        ClipSubject(
            title = title,
            subtitle = subtitle,
            clip = source?.let { clip ->
                { onProgress -> repository.clipFile(clip, "review-${review.id}", onProgress) }
            },
            stillKey = "review-still-${review.id}",
            still = { repository.reviewThumbnail(review) },
            onHalfWatched = { watched.value() },
        )
    }

    // No AI text, no card: a review the server hasn't described opens exactly as
    // it always did, with the object names over the clip.
    val description = review.description()
    val aiTitle = review.aiTitle()
    val words: (@Composable () -> Unit)? = if (description == null) {
        null
    } else {
        { ReviewWords(title = aiTitle, description = description) }
    }

    ClipViewer(subject = subject, onDismiss = onDismiss, details = words)
}

/**
 * The card under a review's clip: the name Frigate gave it, and the story under
 * that name. A heading over the two would be the app explaining itself.
 */
@Composable
private fun ReviewWords(title: String?, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}
