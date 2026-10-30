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

class CamerasViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras = _cameras.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = _cameras.value.isEmpty()
            _error.value = null
            runCatching { repository.cameras() }
                .onSuccess { _cameras.value = it }
                .onFailure { _error.value = it.friendlyMessage() }
            _loading.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CamerasViewModel(container.repository) }
        }
    }
}
