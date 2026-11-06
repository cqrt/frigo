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
import nz.mckenzie.frigo.data.AlertObjects
import nz.mckenzie.frigo.data.Camera
import nz.mckenzie.frigo.data.FrigoRepository

data class AlertObjectsUiState(
    val loading: Boolean = true,
    /** The cameras to narrow by, named and ordered the way this phone shows them. */
    val cameras: List<Camera> = emptyList(),
    /** What each camera can make an alert from, by the server's name for it. */
    val choices: AlertObjectChoices = AlertObjectChoices(),
    /** The camera being set, or null for the list they all follow. */
    val scope: String? = null,
    /** The list as it stands, every camera's own included. */
    val objects: AlertObjects = AlertObjects(),
    /** What is ticked here now. */
    val chosen: Set<String> = emptySet(),
    /**
     * True when neither the server nor the phone's copy of its settings said
     * anything, so only what is already ticked is listed.
     */
    val noAnswer: Boolean = false,
) {
    /**
     * The objects this scope offers, and anything already ticked for it so it can
     * be taken off. A label another camera has is not this camera's business.
     */
    val offered: List<String>
        get() = ((choices.forCamera(scope) + objects.letsThrough(scope))).distinct().sorted()
}

/**
 * What one scope lets through, as a list, for the page to widen its offered
 * labels by.
 */
private fun AlertObjects.letsThrough(scope: String?): List<String> =
    if (scope == null) labels.toList() else labelsFor(scope).toList()

/**
 * The objects this phone lets buzz, camera by camera.
 *
 * The list is the phone's own, so nothing here is sent anywhere: the page reads
 * what the server's cameras can alert on from the copy of its settings the phone
 * already keeps, and a change is written to the phone. Frigate turns a non-admin's
 * change to its own lists down, which is why this list exists at all.
 *
 * Leaving a camera writes what was ticked for it, so the choice survives looking
 * at another camera. All cameras is the list every camera without one of its own
 * follows.
 */
class AlertObjectsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(startingState())
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

    private fun startingState(): AlertObjectsUiState {
        val objects = repository.alertObjects.value
        return AlertObjectsUiState(objects = objects, chosen = objects.labels)
    }

    /** Sets one camera's list, or the one every camera follows, without leaving. */
    fun show(camera: String?) {
        val current = _state.value
        if (current.scope == camera) return

        // What was ticked on the camera being left is kept, the same as Done would.
        val kept = current.objects.withLabels(current.scope, current.chosen)
        commit(kept)
        _state.update {
            it.copy(
                scope = camera,
                objects = kept,
                chosen = kept.letsThrough(camera).toSet(),
            )
        }
    }

    /** One object on or off the list here. */
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

    /** Keeps the list here on the phone. Empty means everything for this scope. */
    fun save(labels: Set<String>) {
        val current = _state.value
        val next = current.objects.withLabels(current.scope, labels)
        commit(next)
        _state.update { it.copy(objects = next, chosen = labels) }
    }

    /** Everything for the scope being set, which is the list with nobody on it. */
    fun everything() {
        val current = _state.value
        val next = current.objects.everythingFor(current.scope)
        commit(next)
        _state.update { it.copy(objects = next, chosen = emptySet()) }
    }

    private fun commit(next: AlertObjects) {
        viewModelScope.launch { repository.setAlertObjects(next) }
    }

    companion object {
        fun factory(repository: FrigoRepository) = viewModelFactory {
            initializer { AlertObjectsViewModel(repository) }
        }
    }
}
