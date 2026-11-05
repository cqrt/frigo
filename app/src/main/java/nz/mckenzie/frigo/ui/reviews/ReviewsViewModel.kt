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
import nz.mckenzie.frigo.data.ReviewDayCount
import nz.mckenzie.frigo.data.ReviewKind
import nz.mckenzie.frigo.data.ReviewSettings
import nz.mckenzie.frigo.data.labelsFor
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.friendlyMessage
import nz.mckenzie.frigo.ui.range
import java.time.LocalDate

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
    /** What the server says each day held, narrowed the way this tab is. */
    val counts: List<ReviewDayCount>? = null,
    /** The same with no labels: what the tab you're not on has left. */
    val otherCounts: List<ReviewDayCount>? = null,
    /** Whether a whole list is being marked read, or put back. */
    val marking: Boolean = false,
    /** The reviews "Mark these as read" just cleared, so they can go back. */
    val justRead: List<String> = emptyList(),
) {
    /** The labels this tab offers, for all cameras or the one picked. */
    val labelsOn: List<String>
        get() = settings?.labelsFor(kind, camera).orEmpty()

    /** Whether a filter narrows the list at all. */
    val narrowed: Boolean
        get() = camera != null || chosenLabels.isNotEmpty() || period != Period.All

    /** How much of one tab is left to look at, or null while the server hasn't said. */
    fun left(kind: ReviewKind): Int? {
        val counted = if (kind == this.kind) counts else otherCounts
        return counted?.left(kind, period)
    }

    /** The days this tab still has something on, for the calendar's dots. */
    val markedDays: Set<LocalDate>
        get() = counts.orEmpty().daysWithLeft(kind)
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
            it.copy(
                kind = kind,
                chosenLabels = emptySet(),
                reviews = emptyList(),
                hasMore = true,
                justRead = emptyList(),
            )
        }
        refresh()
    }

    fun selectCamera(camera: String?) {
        if (_state.value.camera == camera) return
        _state.update { current ->
            val next = current.copy(camera = camera, justRead = emptyList())
            // A label this camera can't have would narrow the list to nothing
            // while the chip had no way to say it was on.
            next.copy(chosenLabels = next.chosenLabels.intersect(next.labelsOn.toSet()))
        }
        refresh()
    }

    fun toggleLabel(label: String) {
        _state.update { current ->
            current.copy(
                justRead = emptyList(),
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
        _state.update { it.copy(period = period, justRead = emptyList()) }
        refresh()
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                camera = null,
                chosenLabels = emptySet(),
                period = Period.All,
                justRead = emptyList(),
            )
        }
        refresh()
    }

    fun setShowReviewed(on: Boolean) {
        if (_state.value.showReviewed == on) return
        _state.update {
            it.copy(
                showReviewed = on,
                reviews = emptyList(),
                hasMore = true,
                justRead = emptyList(),
            )
        }
        viewModelScope.launch { runCatching { repository.setShowReviewed(on) } }
        refresh()
    }

    fun refresh() {
        val current = _state.value
        val (after, before) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            count(current)
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
                    _state.update { it.copy(loading = false, error = error.friendlyMessage(repository.context)) }
                }
        }
    }

    /**
     * Asks the server how much is left on each half of the page.
     *
     * This tab is counted with its labels, because a count that ignored them
     * would say there's more left than the list can show. The other tab is
     * counted without labels, since its labels are its own. With no labels
     * chosen the two answers are the same, so the one call serves both.
     */
    private suspend fun count(current: ReviewsUiState = _state.value) {
        val cameras = listOfNotNull(current.camera)
        val labels = current.chosenLabels.toList()
        val mine = runCatching { repository.reviewDayCounts(cameras, labels) }
        val others = if (labels.isEmpty()) mine else runCatching {
            repository.reviewDayCounts(cameras)
        }
        _state.update {
            it.copy(
                counts = mine.getOrNull() ?: it.counts,
                otherCounts = others.getOrNull() ?: it.otherCounts,
            )
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
                    count()
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.friendlyMessage(repository.context)) }
                }
        }
    }

    /**
     * Marks everything the filters would show as reviewed, without playing any
     * of it.
     *
     * The ids come from walking the same list the screen shows, two hundred at a
     * time, because the server takes a whole list and the list is more than one
     * page. The walk stops at a limit, since clearing a phone's worth of alerts
     * in one tap is the point and clearing ten thousand is not.
     */
    fun markAllRead() {
        val current = _state.value
        if (current.marking) return
        val (after, before) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(marking = true, error = null) }
            runCatching {
                val ids = idsToRead(current, after, before)
                if (ids.isNotEmpty()) repository.markReviews(ids, reviewed = true)
                ids
            }
                .onSuccess { ids ->
                    _state.update {
                        it.copy(
                            marking = false,
                            justRead = ids,
                            reviews = when {
                                ids.isEmpty() -> it.reviews
                                it.showReviewed -> it.reviews.map { review ->
                                    review.copy(hasBeenReviewed = true)
                                }
                                else -> emptyList()
                            },
                            hasMore = false,
                        )
                    }
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(marking = false, error = error.friendlyMessage(repository.context)) }
                }
        }
    }

    /** Puts back everything [markAllRead] just cleared. */
    fun undoMarkAll() {
        val ids = _state.value.justRead
        if (ids.isEmpty() || _state.value.marking) return
        viewModelScope.launch {
            _state.update { it.copy(marking = true, error = null) }
            runCatching { repository.markReviews(ids, reviewed = false) }
                .onSuccess {
                    _state.update { it.copy(marking = false, justRead = emptyList()) }
                    refresh()
                }
                .onFailure { error ->
                    _state.update { it.copy(marking = false, error = error.friendlyMessage(repository.context)) }
                }
        }
    }

    /** Every id the current filters would show, oldest page last, up to a limit. */
    private suspend fun idsToRead(
        state: ReviewsUiState,
        after: Double?,
        before: Double?,
    ): List<String> {
        val ids = mutableListOf<String>()
        var cursor = before
        while (ids.size < MARK_LIMIT) {
            val page = repository.reviews(
                severity = state.kind.apiValue,
                limit = MARK_PAGE,
                before = cursor,
                after = after,
                cameras = listOfNotNull(state.camera),
                labels = state.chosenLabels.toList(),
                showReviewed = false,
            )
            ids += page.map { it.id }
            if (page.size < MARK_PAGE) break
            cursor = page.last().startTime
        }
        return ids.distinct().take(MARK_LIMIT)
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
                    _state.update { it.copy(loadingMore = false, error = error.friendlyMessage(repository.context)) }
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

        /** A page for the walk that collects ids, which the server takes up to 200 at a time. */
        private const val MARK_PAGE = 200

        /** What "Mark these as read" will clear in one go. More than this is not a phone's job. */
        private const val MARK_LIMIT = 2000

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReviewsViewModel(container.repository) }
        }
    }
}
