package nz.mckenzie.frigo.ui.reviews

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.ClipSource
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.data.aiTitle
import nz.mckenzie.frigo.data.description
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.data.uptimeText
import nz.mckenzie.frigo.ui.cameras.ClipFetch
import nz.mckenzie.frigo.ui.cameras.SaveClipButton
import nz.mckenzie.frigo.ui.cameras.clock
import nz.mckenzie.frigo.ui.cameras.stamp
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.components.ClipSubject
import nz.mckenzie.frigo.ui.components.ClipViewer
import nz.mckenzie.frigo.ui.components.attention
import nz.mckenzie.frigo.ui.components.prettyLabel
import nz.mckenzie.frigo.ui.relativeTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One review, on a page of its own: the clip of the time it covers in a frame,
 * the other cameras at that same moment under it, and what Frigate made of it on
 * the card below. Frigate cuts that clip from your recordings when it is asked for
 * one.
 *
 * The card appears when the server has written about the review. A review it
 * hasn't described yet opens on the clip and the other cameras, and the words fill
 * the card in if they arrive while you are watching.
 *
 * Past halfway the review is marked watched on the server, which is what the
 * Frigate web page does, so it leaves the list there too.
 *
 * The clip can be kept from here, beside Close: as a file on the phone, or on the
 * server where Frigate's clean-up cannot reach it. What is kept is the stretch the
 * review covers, so what you watched is what you get.
 */
@Composable
fun ReviewPlayerDialog(
    repository: FrigoRepository,
    review: FrigateReview,
    /** The cameras this phone shows, so the strip under the clip can leave out its own. */
    cameras: List<Camera>,
    onDismiss: () -> Unit,
    onHalfWatched: () -> Unit,
    onOpenCamera: (String, Double) -> Unit,
    onOpenClips: () -> Unit = {},
) {
    val watched = rememberUpdatedState(onHalfWatched)
    val title = review.title()
    val zone = remember { ZoneId.systemDefault() }
    val subtitle = stringResource(
        R.string.reviews_player_subtitle,
        prettyCameraName(review.camera),
        relativeTime(review.startedAtMillis),
    )
    // Where the clip comes from, asked for once: the viewer fetches it, and saving it
    // to the phone copies off the same file rather than asking for it again.
    val source = remember(review.id) {
        runCatching { repository.reviewClipSource(review) }.getOrNull()
    }
    val subject = remember(review.id) {
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

    val others = remember(cameras, review.camera) {
        cameras.filterNot { it.name == review.camera }
    }
    val around: (@Composable () -> Unit)? = if (others.isEmpty()) {
        null
    } else {
        { OtherCamerasAt(repository, review, others, onOpenCamera) }
    }

    // No AI text, no card: the clip, the other cameras, and the object names in
    // the line above the picture.
    val description = review.description()
    val aiTitle = review.aiTitle()
    val details: (@Composable () -> Unit)? = if (description == null) {
        null
    } else {
        { ReviewWords(review = review, title = aiTitle, description = description) }
    }

    // Save is offered only when Frigate has a clip to save. What it saves is the
    // stretch the review covers, which for a review still going is up to now.
    val save = if (source == null) {
        null
    } else {
        rememberSaveForReview(repository = repository, review = review, source = source, zone = zone)
    }

    ClipViewer(
        subject = subject,
        onDismiss = onDismiss,
        sheet = true,
        around = around,
        details = details,
        headerAction = save?.let { saving ->
            { ready ->
                SaveClipButton(
                    name = saving.fileName,
                    stretch = stringResource(
                        R.string.player_stretch,
                        clock(review.startTime, zone),
                        clock(saving.endsAt, zone),
                    ),
                    enabled = ready,
                    fetch = saving.fetch,
                    keep = saving.keep,
                    onOpenClips = {
                        onDismiss()
                        onOpenClips()
                    },
                    compact = true,
                )
            }
        },
    )
}

/** How the clip of this review is saved, as what it is called and the two ways to keep it. */
private class ReviewSave(
    val fileName: String,
    val endsAt: Double,
    val fetch: ClipFetch,
    val keep: suspend () -> Unit,
)

/**
 * Saving the clip of one review: the file it is offered under, and the stretch the
 * server is asked to keep.
 *
 * A review Frigate is still watching has no end yet, so its clip runs to now, which
 * is the same stretch the viewer plays.
 */
@Composable
private fun rememberSaveForReview(
    repository: FrigoRepository,
    review: FrigateReview,
    source: ClipSource,
    zone: ZoneId,
): ReviewSave {
    val locale = LocalConfiguration.current.locales[0]
    val stampFormat = remember(locale) { DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm", locale) }
    val endsAt = review.endTime?.takeIf { it > review.startTime }
        ?: (System.currentTimeMillis() / 1000.0)

    return remember(review.id, endsAt) {
        val at = LocalDateTime.ofInstant(Instant.ofEpochSecond(review.startTime.toLong()), zone)
        ReviewSave(
            // The camera and the moment it starts, so a folder of them sorts itself
            // by date and time, the same as a clip saved from Playback.
            fileName = "frigo-${review.camera}-${at.format(stampFormat)}.mp4",
            endsAt = endsAt,
            fetch = { onProgress ->
                repository.clipFile(source, "review-${review.id}", onProgress)
            },
            keep = {
                repository.keepClip(
                    camera = review.camera,
                    start = review.startTime,
                    end = endsAt,
                    name = "${prettyCameraName(review.camera)} ${stamp(review.startTime, zone)}",
                )
            },
        )
    }
}

/**
 * The other cameras at the moment the review starts, a picture each. A camera that
 * recorded nothing then comes back blank, which says something in itself. Tapping
 * one opens that camera at the same moment.
 */
@Composable
private fun OtherCamerasAt(
    repository: FrigoRepository,
    review: FrigateReview,
    cameras: List<Camera>,
    onOpenCamera: (String, Double) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cameras.forEach { other ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        onClickLabel = stringResource(R.string.playback_show_camera, other.title),
                    ) { onOpenCamera(other.name, review.startTime) },
            ) {
                CachedImage(
                    cacheKey = "review-other-${review.id}-${other.name}",
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop,
                    load = { repository.recordingStill(other.name, review.startTime, height = 360) },
                )
                Text(
                    text = other.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * The card under a review's clip: the name Frigate gave it, the story under that
 * name, and what else the server worked out. A heading over the lot would be the
 * app explaining itself.
 */
@Composable
private fun ReviewWords(
    review: FrigateReview,
    title: String?,
    description: String,
) {
    val context = LocalContext.current
    val seen = (review.data.objects + review.data.subLabels)
        .map { prettyLabel(it) }
        .filter { it.isNotBlank() }
        .distinct()
    val zones = review.data.zones
        .map { prettyLabel(it) }
        .filter { it.isNotBlank() }
        .distinct()
    val length = review.endTime
        ?.takeIf { it > review.startTime }
        ?.let { uptimeText(context, it - review.startTime) }
    val kind = stringResource(
        if (review.severity == ReviewKind.Alerts.apiValue) {
            R.string.reviews_alert
        } else {
            R.string.reviews_detection
        },
    )
    val meta = listOfNotNull(kind, length).joinToString(" · ")
    val threat = review.data.metadata?.threatLevel ?: 0
    val concerns = review.data.metadata?.otherConcerns.orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        }
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
        if (seen.isNotEmpty() || zones.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                seen.forEach { Fact(it) }
                zones.forEach { Fact(it) }
            }
        }
        if (threat > 0 || meta.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (threat > 0) ThreatBadge(threat)
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (concerns.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.reviews_other_concerns,
                    concerns.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One thing the review saw, or one place it happened, as a small filled chip. */
@Composable
private fun Fact(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Suspicious in amber, a critical threat in the app's red. Nothing for ordinary. */
@Composable
private fun ThreatBadge(level: Int) {
    val critical = level >= 2
    val ink = if (critical) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        attention()
    }
    Surface(
        color = if (critical) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            attention().copy(alpha = 0.16f)
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = stringResource(
                if (critical) R.string.reviews_threat_critical else R.string.reviews_threat_suspicious,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = ink,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
