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
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.ReviewFlags
import nz.mckenzie.frigo.data.ReviewPage
import nz.mckenzie.frigo.data.reviewChange
import nz.mckenzie.frigo.ui.friendlyMessage

/** Which list of labels is being chosen. */
enum class ReviewLabels { Alerts, Detections }

data class ReviewSettingsUiState(
    val loading: Boolean = true,
    val page: ReviewPage? = null,
    /**
     * The camera being set, which is the first one until another is picked. There
     * is no all-cameras scope: Frigate sets one camera at a time, and a list that
     * covered every camera would offer objects some of them can't report.
     */
    val scope: String? = null,
    val error: String? = null,
    /** A change is on its way to the server. */
    val busy: Boolean = false,
)

/** What the page shows: the camera being set. */
val ReviewSettingsUiState.shown: ReviewFlags?
    get() = page?.let { page -> scope?.let { page.settings.cameras[it] } }

/**
 * The four settings behind the Review page, for all cameras or one at a time.
 *
 * Each change goes to the server on its own rather than when you leave, because
 * Frigate applies it as it saves and the next alert follows it. The page shows
 * the change at once and puts it back if the server turns it down.
 */
class ReviewSettingsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewSettingsUiState())
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.reviewPage() }
                .onSuccess { page ->
                    _state.update { current ->
                        // A camera that has gone from the server falls back to the
                        // first one, which is where a page that has just opened starts.
                        val scope = current.scope?.takeIf { name ->
                            page.cameras.any { it.name == name }
                        } ?: page.cameras.firstOrNull()?.name
                        current.copy(loading = false, page = page, scope = scope)
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.friendlyMessage(repository.context)) }
                }
        }
    }

    fun show(camera: String?) {
        if (camera == null) return
        _state.update { it.copy(scope = camera, error = null) }
    }

    fun setAlertsOn(on: Boolean) = write { it.copy(alertsOn = on) }

    fun setDetectionsOn(on: Boolean) = write { it.copy(detectionsOn = on) }

    fun setLabels(kind: ReviewLabels, labels: List<String>) = write {
        when (kind) {
            ReviewLabels.Alerts -> it.copy(alertLabels = labels)
            // Nothing ticked means every other object, which Frigate spells as no list.
            ReviewLabels.Detections -> it.copy(detectionLabels = labels.ifEmpty { null })
        }
    }

    private fun write(transform: (ReviewFlags) -> ReviewFlags) {
        val current = _state.value
        if (current.busy) return
        // A page read off the phone can't send anything: the server wasn't there.
        if (current.page?.readAt != null) return

        // One camera at a time: there is no scope that covers every camera.
        val camera = current.scope ?: return
        val before = current.shown ?: return
        val after = transform(before)
        // Nothing to say means nothing to send.
        val change = reviewChange(camera, before, after) ?: return

        _state.update {
            it.copy(page = it.page?.setting(camera, after), busy = true, error = null)
        }

        viewModelScope.launch {
            runCatching { repository.setReview(change) }
                .onSuccess {
                    _state.update { it.copy(busy = false) }
                    // The buzz follows these, so the phone's copy is brought up to
                    // date now rather than waiting for the next launch.
                    repository.refreshAlertRules()
                }
                .onFailure { failure ->
                    // Put the page back the way the server still has it.
                    _state.update {
                        it.copy(
                            page = it.page?.setting(camera, before),
                            busy = false,
                            error = failure.friendlyMessage(repository.context),
                        )
                    }
                }
        }
    }

    private fun ReviewPage.setting(camera: String, flags: ReviewFlags): ReviewPage =
        copy(settings = settings.copy(cameras = settings.cameras + (camera to flags)))

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReviewSettingsViewModel(container.repository) }
        }
    }
}
