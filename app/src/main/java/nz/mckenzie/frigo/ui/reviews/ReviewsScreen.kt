package nz.mckenzie.frigo.ui.reviews

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.relativeTime

/**
 * The moments Frigate thought were worth a look, newest first. Each one is a
 * stretch of time rather than a single object, so a visitor who stays a while is
 * one review and not twenty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: ReviewsViewModel = viewModel(factory = ReviewsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<FrigateReview?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Review") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            ReviewKind.entries.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = state.kind == kind,
                    onClick = { viewModel.show(kind) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReviewKind.entries.size),
                ) {
                    Text(kind.label)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.setShowReviewed(!state.showReviewed) }
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Show reviewed",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.showReviewed, onCheckedChange = viewModel::setShowReviewed)
        }

        state.error?.takeIf { state.reviews.isNotEmpty() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading && state.reviews.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.reviews.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) { Text("Try again") }
                }

                state.reviews.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            state.showReviewed -> if (state.kind == ReviewKind.Alerts) {
                                "No alerts yet. Frigate makes one when something you care " +
                                    "about is seen in a zone with alerts on."
                            } else {
                                "No detections yet."
                            }

                            else -> if (state.kind == ReviewKind.Alerts) {
                                "No alerts left to review."
                            } else {
                                "No detections left to review."
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!state.showReviewed) {
                        Text(
                            text = "Anything you've watched is hidden. Turn on Show reviewed " +
                                "to see it again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.reviews, key = { it.id }) { review ->
                        ReviewRow(
                            repository = container.repository,
                            review = review,
                            onClick = { opened = review },
                        )
                    }
                    if (state.hasMore) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    Button(onClick = viewModel::loadMore) { Text("Load older") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { review ->
        ReviewPlayerDialog(
            repository = container.repository,
            review = review,
            onDismiss = { opened = null },
            onHalfWatched = { viewModel.markReviewed(review) },
        )
    }
}

@Composable
private fun ReviewRow(
    repository: FrigoRepository,
    review: FrigateReview,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CachedImage(
                cacheKey = "review-${review.id}",
                modifier = Modifier
                    .padding(8.dp)
                    .size(width = 112.dp, height = 63.dp)
                    .aspectRatio(16f / 9f),
                contentScale = ContentScale.Crop,
                load = { repository.reviewThumbnail(review) },
            )
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = review.title(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = prettyCameraName(review.camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(relativeTime(review.startedAtMillis))
                        if (review.hasBeenReviewed) append(" · Reviewed")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `person`, `person-verified` and the like, said the way a person would. */
internal fun FrigateReview.title(): String {
    val seen = (data.objects + data.subLabels)
        .map { it.removeSuffix("-verified").replace('_', ' ') }
        .filter { it.isNotBlank() }
        .distinct()
    if (seen.isEmpty()) return "Motion"
    return seen.joinToString(", ") { it.replaceFirstChar { first -> first.uppercase() } }
}
