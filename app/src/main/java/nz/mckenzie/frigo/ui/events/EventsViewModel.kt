package nz.mckenzie.frigo.ui.events

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
import nz.mckenzie.frigo.ui.friendlyMessage

data class EventsUiState(
    val events: List<FrigateEvent> = emptyList(),
    val cameras: List<Camera> = emptyList(),
    val selectedCamera: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
)

class EventsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state = _state.asStateFlow()

    init {
        loadCameras()
        refresh()
    }

    fun selectCamera(camera: String?) {
        if (_state.value.selectedCamera == camera) return
        _state.update { it.copy(selectedCamera = camera) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val camera = _state.value.selectedCamera
            runCatching { repository.events(camera = camera, limit = PAGE) }
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
                    _state.update { it.copy(loading = false, error = error.friendlyMessage()) }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || !current.hasMore || current.events.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            val before = current.events.last().startTime
            runCatching { repository.events(camera = current.selectedCamera, limit = PAGE, before = before) }
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
                    _state.update { it.copy(loadingMore = false, error = error.friendlyMessage()) }
                }
        }
    }

    private fun loadCameras() {
        viewModelScope.launch {
            runCatching { repository.cameras() }
                .onSuccess { cameras -> _state.update { it.copy(cameras = cameras) } }
        }
    }

    companion object {
        private const val PAGE = 30

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { EventsViewModel(container.repository) }
        }
    }
}
