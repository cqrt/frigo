package nz.mckenzie.frigo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.CameraSwitch
import nz.mckenzie.frigo.data.CameraSwitchPage
import nz.mckenzie.frigo.data.CameraSwitches
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.prettyCameraName
import nz.mckenzie.frigo.ui.friendlyMessage

data class CameraSwitchesUiState(
    val loading: Boolean = true,
    val page: CameraSwitchPage? = null,
    val camera: String = "",
    val error: String? = null,
) {
    /** The camera's name, or the closest thing to one when the server hasn't said. */
    val title: String
        get() = page?.cameras?.firstOrNull { it.name == camera }?.title
            ?: prettyCameraName(camera)

    val switches: CameraSwitches?
        get() = page?.switches?.get(camera)
}

/**
 * The eight switches on one camera.
 *
 * Each change goes to the server on its own rather than when you leave, because
 * Frigate applies it as it saves. The page shows the change at once and puts it
 * back if the server turns it down.
 */
class CameraSwitchesViewModel(
    private val repository: FrigoRepository,
    camera: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CameraSwitchesUiState(camera = camera))
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.cameraSwitchPage() }
                .onSuccess { page ->
                    _state.update { it.copy(loading = false, page = page) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.friendlyMessage(repository.context)) }
                }
        }
    }

    fun set(switch: CameraSwitch, on: Boolean) {
        val current = _state.value
        val camera = current.camera
        // Nothing goes to a server this phone couldn't reach: the page is the last
        // answer it read, and a change would be sent when the screen says otherwise.
        if (current.page?.readAt != null) return
        val before = current.page?.switches?.get(camera) ?: return
        if (before[switch] == on) return

        _state.update {
            it.copy(page = it.page?.setting(camera, before.with(switch, on)), error = null)
        }

        viewModelScope.launch {
            runCatching { repository.setCameraSwitch(camera, switch, on) }
                .onFailure { failure ->
                    // Put that one switch back the way the server still has it. The
                    // rest of the page is left alone, in case another tap is in flight.
                    _state.update { ui ->
                        val now = ui.page?.switches?.get(camera) ?: before
                        ui.copy(
                            page = ui.page?.setting(camera, now.with(switch, before[switch])),
                            error = failure.friendlyMessage(repository.context),
                        )
                    }
                }
        }
    }

    private fun CameraSwitchPage.setting(camera: String, switches: CameraSwitches) =
        copy(switches = this.switches + (camera to switches))

    companion object {
        fun factory(container: AppContainer, camera: String) = viewModelFactory {
            initializer { CameraSwitchesViewModel(container.repository, camera) }
        }
    }
}
