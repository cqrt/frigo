package nz.mckenzie.frigo.ui.reviews

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.data.ReviewSettings
import nz.mckenzie.frigo.data.labelsFor
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.friendlyMessage
import nz.mckenzie.frigo.ui.range

data class ReviewsUiState(
    val reviews: List<FrigateReview> = emptyList(),
    val kind: ReviewKind = ReviewKind.Alerts,
    val cameras: List<Camera> = emptyList(),
    /** The camera being looked at, or null for all of them. */
    val camera: String? = null,
    val chosenLabels: Set<String> = emptySet(),
    val period: Period = Period.All,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    /** Whether the ones you've watched are listed too. */
    val showReviewed: Boolean = false,
    /** Frigate's own lists, which say what this tab can be narrowed to. */
    val settings: ReviewSettings? = null,
) {
    /** The labels this tab offers, for all cameras or the one picked. */
    val labelsOn: List<String>
        get() = settings?.labelsFor(kind, camera).orEmpty()

    /** Whether a filter narrows the list at all. */
    val narrowed: Boolean
        get() = camera != null || chosenLabels.isNotEmpty() || period != Period.All
}

class ReviewsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(showReviewed = repository.readShowReviewed()) }
            refresh()
        }
        loadChoices()
    }

    fun show(kind: ReviewKind) {
        if (_state.value.kind == kind) return
        // The two tabs hold different lists of labels, so a choice made on one
        // doesn't carry to the other.
        _state.update {
            it.copy(kind = kind, chosenLabels = emptySet(), reviews = emptyList(), hasMore = true)
        }
        refresh()
    }

    fun selectCamera(camera: String?) {
        if (_state.value.camera == camera) return
        _state.update { current ->
            val next = current.copy(camera = camera)
            // A label this camera can't have would narrow the list to nothing
            // while the chip had no way to say it was on.
            next.copy(chosenLabels = next.chosenLabels.intersect(next.labelsOn.toSet()))
        }
        refresh()
    }

    fun toggleLabel(label: String) {
        _state.update { current ->
            current.copy(
                chosenLabels = if (label in current.chosenLabels) {
                    current.chosenLabels - label
                } else {
                    current.chosenLabels + label
                },
            )
        }
        refresh()
    }

    fun setPeriod(period: Period) {
        if (_state.value.period == period) return
        _state.update { it.copy(period = period) }
        refresh()
    }

    fun clearFilters() {
        _state.update {
            it.copy(camera = null, chosenLabels = emptySet(), period = Period.All)
        }
        refresh()
    }

    fun setShowReviewed(on: Boolean) {
        if (_state.value.showReviewed == on) return
        _state.update { it.copy(showReviewed = on, reviews = emptyList(), hasMore = true) }
        viewModelScope.launch { runCatching { repository.setShowReviewed(on) } }
        refresh()
    }

    fun refresh() {
        val current = _state.value
        val (after, before) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.reviews(
                    severity = current.kind.apiValue,
                    limit = PAGE,
                    before = before,
                    after = after,
                    cameras = listOfNotNull(current.camera),
                    labels = current.chosenLabels.toList(),
                    showReviewed = current.showReviewed,
                )
            }
                .onSuccess { reviews ->
                    _state.update {
                        it.copy(
                            reviews = reviews,
                            loading = false,
                            hasMore = reviews.size >= PAGE,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.friendlyMessage()) }
                }
        }
    }

    /**
     * Said once a clip is over half watched, which is when Frigate counts a
     * review as seen. The server keeps that, so the web page drops it too.
     */
    fun markReviewed(review: FrigateReview) {
        if (review.hasBeenReviewed) return
        viewModelScope.launch {
            runCatching { repository.markReviewReviewed(review.id) }
                .onSuccess {
                    _state.update { current ->
                        if (current.showReviewed) {
                            current.copy(
                                reviews = current.reviews.map {
                                    if (it.id == review.id) it.copy(hasBeenReviewed = true) else it
                                },
                            )
                        } else {
                            current.copy(reviews = current.reviews.filterNot { it.id == review.id })
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.friendlyMessage()) }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.reviews.isEmpty()) return
        val (after, _) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val before = current.reviews.last().startTime
            runCatching {
                repository.reviews(
                    severity = current.kind.apiValue,
                    limit = PAGE,
                    before = before,
                    after = after,
                    cameras = listOfNotNull(current.camera),
                    labels = current.chosenLabels.toList(),
                    showReviewed = current.showReviewed,
                )
            }
                .onSuccess { older ->
                    _state.update {
                        it.copy(
                            reviews = it.reviews + older,
                            loadingMore = false,
                            hasMore = older.size >= PAGE,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(loadingMore = false, error = error.friendlyMessage()) }
                }
        }
    }

    /** The cameras to offer and the labels each tab can be narrowed to. */
    private fun loadChoices() {
        viewModelScope.launch {
            runCatching { repository.reviewPage() }
                .onSuccess { page ->
                    _state.update { it.copy(cameras = page.cameras, settings = page.settings) }
                }
        }
    }

    companion object {
        private const val PAGE = 30

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReviewsViewModel(container.repository) }
        }
    }
}
