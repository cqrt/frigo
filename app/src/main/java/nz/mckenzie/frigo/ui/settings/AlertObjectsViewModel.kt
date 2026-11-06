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
    /** The cameras to set, named and ordered the way this phone shows them. */
    val cameras: List<Camera> = emptyList(),
    /** What each camera can make an alert from, by the server's name for it. */
    val choices: AlertObjectChoices = AlertObjectChoices(),
    /** The camera being set, which is the first one until another is picked. */
    val scope: String? = null,
    /** The list as it stands, every camera's own included. */
    val objects: AlertObjects = AlertObjects(),
    /** What is ticked for this camera now. Every object it alerts on is everything. */
    val chosen: Set<String> = emptySet(),
    /**
     * True when neither the server nor the phone's copy of its settings said
     * anything, so only what is already ticked is listed.
     */
    val noAnswer: Boolean = false,
) {
    /**
     * The objects this camera offers, and anything already ticked for it so it can
     * be taken off. Another camera's list is not this camera's business.
     */
    val offered: List<String>
        get() = (choices.forCamera(scope) + objects.letsThrough(scope)).distinct().sorted()

    /** Everything this camera alerts on, which is what ticking all of them means. */
    val all: Set<String>
        get() = choices.forCamera(scope).toSet()
}

/** What one scope lets through, as a list, for the page to widen its offered labels by. */
private fun AlertObjects.letsThrough(scope: String?): List<String> =
    if (scope == null) labels.toList() else labelsFor(scope).toList()

/**
 * What is ticked for a camera: the list it has, or every object it alerts on when it
 * has none. No list is the same as everything, and a camera starts that way.
 */
private fun ticked(objects: AlertObjects, choices: AlertObjectChoices, camera: String): Set<String> {
    val saved = objects.labelsFor(camera)
    return saved.ifEmpty { choices.forCamera(camera).toSet() }
}

/**
 * The objects this phone lets buzz, camera by camera.
 *
 * The list is the phone's own, so nothing here is sent anywhere: the page reads
 * what the server's cameras can alert on from the copy of its settings the phone
 * already keeps, and a change is written to the phone. Frigate turns a non-admin's
 * change to its own lists down, which is why this list exists at all.
 *
 * One camera is set at a time, and leaving a camera writes what was ticked for it.
 * A camera with every object it alerts on ticked is stored as having no list, which
 * is how "everything" is kept: a label the server adds later is included.
 */
class AlertObjectsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        AlertObjectsUiState(objects = repository.alertObjects.value),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val choices = runCatching { repository.alertObjectChoices() }.getOrNull()
            // A camera turned off on this phone is not one to set, the same as on
            // Review and Explore.
            val cameras = choices
                ?.let { repository.cameraList.value.shown(it.cameras) }
                .orEmpty()
            _state.update { current ->
                val scope = current.scope ?: cameras.firstOrNull()?.name
                val next = choices ?: AlertObjectChoices()
                current.copy(
                    loading = false,
                    noAnswer = choices == null,
                    cameras = cameras,
                    choices = next,
                    scope = scope,
                    chosen = scope?.let { ticked(current.objects, next, it) }.orEmpty(),
                )
            }
        }
    }

    /** Moves to another camera, keeping what was ticked for the one being left. */
    fun show(camera: String) {
        val current = _state.value
        if (current.scope == camera) return

        // What was ticked on the camera being left is kept, the same as Done would.
        val kept = current.scope
            ?.let { current.objects.withChosen(it, current.chosen, current.all) }
            ?: current.objects
        commit(kept)
        _state.update {
            it.copy(
                scope = camera,
                objects = kept,
                chosen = ticked(kept, it.choices, camera),
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

    /** Keeps the list here on the phone, with all of them ticked stored as everything. */
    fun save(labels: Set<String>) {
        val current = _state.value
        val camera = current.scope ?: return
        val next = current.objects.withChosen(camera, labels, current.all)
        commit(next)
        _state.update { it.copy(objects = next, chosen = ticked(next, it.choices, camera)) }
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
