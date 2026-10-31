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
import nz.mckenzie.frigo.data.FrigateReview
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.friendlyMessage

/** The two kinds of review worth showing. Motion on its own is not one of them. */
enum class ReviewKind(val apiValue: String, val label: String) {
    Alerts("alert", "Alerts"),
    Detections("detection", "Detections"),
}

data class ReviewsUiState(
    val reviews: List<FrigateReview> = emptyList(),
    val kind: ReviewKind = ReviewKind.Alerts,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    /** Whether the ones you've watched are listed too. */
    val showReviewed: Boolean = false,
)

class ReviewsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewsUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(showReviewed = repository.readShowReviewed()) }
            refresh()
        }
    }

    fun show(kind: ReviewKind) {
        if (_state.value.kind == kind) return
        _state.update { it.copy(kind = kind, reviews = emptyList(), hasMore = true) }
        refresh()
    }

    fun setShowReviewed(on: Boolean) {
        if (_state.value.showReviewed == on) return
        _state.update { it.copy(showReviewed = on, reviews = emptyList(), hasMore = true) }
        viewModelScope.launch { runCatching { repository.setShowReviewed(on) } }
        refresh()
    }

    fun refresh() {
        val showReviewed = _state.value.showReviewed
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.reviews(
                    severity = _state.value.kind.apiValue,
                    limit = PAGE,
                    showReviewed = showReviewed,
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
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val before = current.reviews.last().startTime
            runCatching {
                repository.reviews(
                    severity = current.kind.apiValue,
                    limit = PAGE,
                    before = before,
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

    companion object {
        private const val PAGE = 30

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReviewsViewModel(container.repository) }
        }
    }
}
