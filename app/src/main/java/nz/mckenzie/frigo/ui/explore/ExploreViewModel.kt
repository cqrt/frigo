package nz.mckenzie.frigo.ui.explore

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
import nz.mckenzie.frigo.data.FrigateEvent
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.Period
import nz.mckenzie.frigo.ui.friendlyMessage
import nz.mckenzie.frigo.ui.range

data class ExploreUiState(
    val events: List<FrigateEvent> = emptyList(),
    val cameras: List<Camera> = emptyList(),
    val labels: List<String> = emptyList(),
    val subLabels: List<String> = emptyList(),
    val camera: String? = null,
    val chosenLabels: Set<String> = emptySet(),
    val chosenSubLabels: Set<String> = emptySet(),
    val period: Period = Period.All,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
) {
    /** Whether anything narrows the list beyond the camera chips. */
    val narrowed: Boolean
        get() = chosenLabels.isNotEmpty() ||
            chosenSubLabels.isNotEmpty() ||
            period != Period.All ||
            camera != null
}

class ExploreViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state = _state.asStateFlow()

    init {
        loadChoices()
        refresh()
    }

    fun selectCamera(camera: String?) {
        if (_state.value.camera == camera) return
        _state.update { it.copy(camera = camera) }
        refresh()
    }

    fun toggleLabel(label: String) {
        _state.update { state ->
            state.copy(
                chosenLabels = state.chosenLabels.toggle(label),
            )
        }
        refresh()
    }

    fun toggleSubLabel(name: String) {
        _state.update { state ->
            state.copy(
                chosenSubLabels = state.chosenSubLabels.toggle(name),
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
            it.copy(
                camera = null,
                chosenLabels = emptySet(),
                chosenSubLabels = emptySet(),
                period = Period.All,
            )
        }
        refresh()
    }

    fun refresh() {
        val current = _state.value
        val (after, before) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                repository.events(
                    camera = current.camera,
                    labels = current.chosenLabels.toList(),
                    subLabels = current.chosenSubLabels.toList(),
                    limit = PAGE,
                    after = after,
                    before = before,
                )
            }
                .onSuccess { events ->
                    _state.update {
                        it.copy(
                            events = events,
                            loading = false,
                            hasMore = events.size >= PAGE,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.friendlyMessage(repository.context)) }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.events.isEmpty()) return
        val (after, _) = current.period.range()
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val before = current.events.last().startTime
            runCatching {
                repository.events(
                    camera = current.camera,
                    labels = current.chosenLabels.toList(),
                    subLabels = current.chosenSubLabels.toList(),
                    limit = PAGE,
                    after = after,
                    before = before,
                )
            }
                .onSuccess { older ->
                    _state.update {
                        it.copy(
                            events = it.events + older,
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

    private fun loadChoices() {
        viewModelScope.launch {
            runCatching { repository.cameras() }
                .onSuccess { cameras ->
                    _state.update { it.copy(cameras = repository.cameraList.value.shown(cameras)) }
                }
        }
        viewModelScope.launch {
            runCatching { repository.labels() }
                .onSuccess { labels -> _state.update { it.copy(labels = labels) } }
        }
        viewModelScope.launch {
            runCatching { repository.subLabels() }
                .onSuccess { names -> _state.update { it.copy(subLabels = names) } }
        }
    }

    companion object {
        private const val PAGE = 30

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExploreViewModel(container.repository) }
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (contains(value)) this - value else this + value
