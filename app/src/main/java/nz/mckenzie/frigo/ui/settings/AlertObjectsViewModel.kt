package nz.mckenzie.frigo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AlertObjectChoices
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository

data class AlertObjectsUiState(
    val loading: Boolean = true,
    /** The cameras to narrow by, named and ordered the way this phone shows them. */
    val cameras: List<Camera> = emptyList(),
    /** What each camera can make an alert from, by the server's name for it. */
    val choices: AlertObjectChoices = AlertObjectChoices(),
    /** The camera the list is narrowed to, or null for every camera. */
    val scope: String? = null,
    /** The objects ticked when the page opened, which is the list as it stands. */
    val chosen: Set<String> = emptySet(),
    /**
     * True when neither the server nor the phone's copy of its settings said
     * anything, so only what is already ticked is listed.
     */
    val noAnswer: Boolean = false,
) {
    /**
     * The objects this camera offers, and anything already ticked so it can be
     * taken off. One list for every camera: this narrows what is shown, not what
     * a tick means.
     */
    val offered: List<String>
        get() = (choices.forCamera(scope) + chosen).distinct().sorted()
}

/**
 * The objects this phone lets buzz.
 *
 * The list is the phone's own, so nothing here is sent anywhere: the page reads
 * what the server's cameras can alert on from the copy of its settings the phone
 * already keeps, and a change is written to the phone. Frigate turns a non-admin's
 * change to its own lists down, which is why this list exists at all.
 */
class AlertObjectsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        AlertObjectsUiState(chosen = repository.alertObjects.value.labels),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val choices = runCatching { repository.alertObjectChoices() }.getOrNull()
            // A camera turned off on this phone is not one to narrow by, the same
            // as on Review and Explore.
            val cameras = choices
                ?.let { repository.cameraList.value.shown(it.cameras) }
                .orEmpty()
            _state.update { current ->
                current.copy(
                    loading = false,
                    noAnswer = choices == null,
                    cameras = cameras,
                    choices = choices ?: AlertObjectChoices(),
                )
            }
        }
    }

    /** Narrows the list to one camera, or back to all of them. */
    fun show(camera: String?) {
        _state.update { it.copy(scope = camera) }
    }

    /** One object on or off the list. */
    fun toggle(label: String) {
        _state.update { current ->
            current.copy(
                chosen = if (label in current.chosen) {
                    current.chosen - label
                } else {
                    current.chosen + label
                },
            )
        }
    }

    /** Keeps the list on the phone. Empty means everything the server sends. */
    fun save(labels: Set<String>) {
        _state.update { it.copy(chosen = labels) }
        viewModelScope.launch { repository.setAlertObjects(labels) }
    }

    companion object {
        fun factory(repository: FrigoRepository) = viewModelFactory {
            initializer { AlertObjectsViewModel(repository) }
        }
    }
}
