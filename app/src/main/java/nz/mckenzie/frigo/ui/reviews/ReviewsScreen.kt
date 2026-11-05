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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.FrigoIcons
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.components.CachedImage
import nz.mckenzie.frigo.ui.components.CameraMenu
import nz.mckenzie.frigo.ui.components.ChoiceChip
import nz.mckenzie.frigo.ui.components.DayDialog
import nz.mckenzie.frigo.ui.components.PeriodChip
import nz.mckenzie.frigo.ui.components.prettyLabel
import nz.mckenzie.frigo.ui.relativeTime

/**
 * The moments Frigate thought were worth a look, newest first. Each one is a
 * stretch of time rather than a single object, so a visitor who stays a while is
 * one review and not twenty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    val viewModel: ReviewsViewModel = viewModel(factory = ReviewsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<FrigateReview?>(null) }
    var pickingDay by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.reviews_title)) },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        FrigoIcons.Tune,
                        contentDescription = stringResource(R.string.revset_title),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                    val left = state.left(kind)
                    val label = stringResource(kind.labelRes)
                    Text(
                        buildAnnotatedString {
                            append(label)
                            // How much of this half is left. Nothing is said when
                            // there's nothing left, so a cleared tab reads as
                            // plain words again.
                            if (left != null && left > 0) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                    append("  $left")
                                }
                            }
                        },
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                CameraMenu(
                    cameras = state.cameras,
                    chosen = state.camera,
                    onChoose = viewModel::selectCamera,
                )
            }
            item {
                ChoiceChip(
                    title = stringResource(R.string.reviews_labels),
                    choices = state.labelsOn,
                    chosen = state.chosenLabels,
                    name = ::prettyLabel,
                    onToggle = viewModel::toggleLabel,
                    empty = stringResource(R.string.reviews_no_labels),
                )
            }
            item {
                PeriodChip(
                    period = state.period,
                    onChoose = viewModel::setPeriod,
                    onPickDay = { pickingDay = true },
                )
            }
            item {
                FilterChip(
                    selected = state.showReviewed,
                    onClick = { viewModel.setShowReviewed(!state.showReviewed) },
                    label = { Text(stringResource(R.string.reviews_show_reviewed)) },
                )
            }
            if (state.narrowed) {
                item {
                    TextButton(onClick = viewModel::clearFilters) {
                        Text(stringResource(R.string.reviews_clear))
                    }
                }
            }
        }

        state.error?.takeIf { state.reviews.isNotEmpty() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        PullToRefreshBox(
            // The full screen spinner takes over when there is nothing to show,
            // so the pull indicator only appears over a list that has content.
            isRefreshing = state.loading && state.reviews.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading && state.reviews.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.reviews.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.reviews_try_again))
                    }
                }

                state.reviews.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when {
                            // A list you just cleared says so, even when a period
                            // or a label narrows it: "nothing for these filters"
                            // would read like something went wrong.
                            state.justRead.isNotEmpty() -> state.kind.emptyLeft()

                            state.narrowed -> stringResource(R.string.reviews_nothing_filtered)

                            state.showReviewed -> state.kind.emptyAll()

                            else -> state.kind.emptyLeft()
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.narrowed) {
                        Button(onClick = viewModel::clearFilters) {
                            Text(stringResource(R.string.reviews_clear_filters))
                        }
                    } else if (!state.showReviewed) {
                        Text(
                            text = stringResource(R.string.reviews_hidden_reviewed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.justRead.isNotEmpty()) {
                        TextButton(
                            onClick = viewModel::undoMarkAll,
                            enabled = !state.marking,
                        ) {
                            Text(stringResource(R.string.reviews_put_back))
                        }
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
                    if (!state.showReviewed) {
                        item("mark") {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.marking) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(18.dp))
                                        Text(
                                            text = stringResource(R.string.reviews_marking),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                } else {
                                    TextButton(onClick = viewModel::markAllRead) {
                                        Text(stringResource(R.string.reviews_mark_all))
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasMore) {
                        item("more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    Button(onClick = viewModel::loadMore) {
                                        Text(stringResource(R.string.reviews_load_older))
                                    }
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

    if (pickingDay) {
        DayDialog(
            initial = (state.period as? Period.On)?.day,
            marked = state.markedDays,
            onDismiss = { pickingDay = false },
            onPick = { day ->
                viewModel.setPeriod(Period.On(day))
                pickingDay = false
            },
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CachedImage(
                cacheKey = "review-${review.id}",
                modifier = Modifier
                    .padding(8.dp)
                    .size(width = 112.dp, height = 63.dp)
                    .aspectRatio(16f / 9f)
                    // The same corners as the small camera tiles under the live picture.
                    .clip(RoundedCornerShape(8.dp)),
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
                val ago = relativeTime(review.startedAtMillis)
                Text(
                    text = if (review.hasBeenReviewed) {
                        stringResource(R.string.reviews_reviewed_at, ago)
                    } else {
                        ago
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `person`, `person-verified` and the like, said the way a person would. */
@Composable
internal fun FrigateReview.title(): String {
    val seen = (data.objects + data.subLabels)
        .map { it.removeSuffix("-verified").replace('_', ' ') }
        .filter { it.isNotBlank() }
        .distinct()
    if (seen.isEmpty()) return stringResource(R.string.reviews_motion)
    return seen.joinToString(", ") { it.replaceFirstChar { first -> first.uppercase() } }
}
