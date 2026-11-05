package nz.mckenzie.frigo.ui.cameras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.friendlyMessage

/**
 * How the cameras are laid out on the home page.
 *
 * Each look keeps the number it was saved under. A list of one camera to a line was
 * the middle look for a while, so the wide one is still 2 and a phone that was on it
 * stays on it.
 */
enum class CameraLayout(val saved: Int) {
    /** Two to a row, the way it has always been. */
    Grid(0),

    /** One picture to a row, filling the width, with the name on it. */
    Wide(2),
}

class CamerasViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras = _cameras.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    /** True while a refresh you asked for is running, so the button can say so. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _layout = MutableStateFlow(CameraLayout.Grid)
    val layout = _layout.asStateFlow()

    /** Whether each tile plays a live stream instead of taking a picture a second. */
    private val _liveTiles = MutableStateFlow(false)
    val liveTiles = _liveTiles.asStateFlow()

    /** Bumped by a refresh: every tile takes a new picture when it changes. */
    private val _refreshKey = MutableStateFlow(0)
    val refreshKey = _refreshKey.asStateFlow()

    init {
        viewModelScope.launch {
            _layout.value = CameraLayout.entries.firstOrNull {
                it.saved == repository.readCameraLayout()
            } ?: CameraLayout.Grid
        }
        // Collected rather than read once, so flipping it in Settings changes the
        // tiles you come back to.
        viewModelScope.launch { repository.liveTiles.collect { _liveTiles.value = it } }
        refresh()
    }

    /** Grid to wide, wide back to grid: one button, two looks. */
    fun cycleLayout() {
        val next = when (_layout.value) {
            CameraLayout.Grid -> CameraLayout.Wide
            CameraLayout.Wide -> CameraLayout.Grid
        }
        _layout.value = next
        viewModelScope.launch { repository.setCameraLayout(next.saved) }
    }

    fun refresh() {
        // The list of cameras rarely changes; the pictures are the thing worth
        // asking for, so every tile is told to fetch one now rather than wait for
        // the next second to come round.
        _refreshKey.value++
        viewModelScope.launch {
            _loading.value = _cameras.value.isEmpty()
            _refreshing.value = _cameras.value.isNotEmpty()
            _error.value = null
            runCatching { repository.cameras() }
                .onSuccess { _cameras.value = it }
                .onFailure { _error.value = it.friendlyMessage(repository.context) }
            _loading.value = false
            _refreshing.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CamerasViewModel(container.repository) }
        }
    }
}
