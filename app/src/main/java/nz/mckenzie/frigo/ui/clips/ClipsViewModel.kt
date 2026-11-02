package nz.mckenzie.frigo.ui.clips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.Export
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.friendlyMessage

data class ClipsUiState(
    val exports: List<Export> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** What a rename or a delete went wrong with, said over the list. */
    val problem: String? = null,
)

/**
 * The clips the server is holding, newest first.
 *
 * A stretch is cut on the server, so a clip just asked for is on the list before it
 * is a file. While one is still being cut the list asks again every few seconds
 * rather than making you pull it down to find out.
 */
class ClipsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(ClipsUiState())
    val state = _state.asStateFlow()

    private var watching: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        load(spinner = true)
    }

    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }

    fun rename(export: Export, name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean == export.name) return
        viewModelScope.launch {
            runCatching { repository.renameExport(export.id, clean) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(
                            exports = state.exports.map {
                                if (it.id == export.id) it.copy(name = clean) else it
                            },
                            problem = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(problem = failure.friendlyMessage()) }
                }
        }
    }

    /**
     * Takes a clip off the server. It goes from the list at once and comes back if
     * the server turns the delete down, so a wrong tap is visible rather than lost.
     */
    fun delete(export: Export) {
        val before = _state.value.exports
        _state.update {
            it.copy(exports = it.exports.filterNot { kept -> kept.id == export.id }, problem = null)
        }
        viewModelScope.launch {
            runCatching { repository.deleteExports(listOf(export.id)) }
                .onFailure { failure ->
                    _state.update { it.copy(exports = before, problem = failure.friendlyMessage()) }
                }
        }
    }

    private fun load(spinner: Boolean) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = spinner && it.exports.isEmpty(),
                    error = null,
                )
            }
            runCatching { repository.exports() }
                .onSuccess { exports ->
                    _state.update { it.copy(exports = exports, loading = false) }
                    watchCutting()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.exports.isEmpty()) failure.friendlyMessage() else null,
                            problem = if (it.exports.isEmpty()) null else failure.friendlyMessage(),
                        )
                    }
                }
        }
    }

    /** Asks again every few seconds while Frigate is still cutting one out. */
    private fun watchCutting() {
        if (watching?.isActive == true) return
        if (_state.value.exports.none { it.inProgress }) return
        watching = viewModelScope.launch {
            while (isActive && _state.value.exports.any { it.inProgress }) {
                delay(3_000)
                runCatching { repository.exports() }
                    .onSuccess { exports -> _state.update { it.copy(exports = exports) } }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ClipsViewModel(container.repository) }
        }
    }
}
