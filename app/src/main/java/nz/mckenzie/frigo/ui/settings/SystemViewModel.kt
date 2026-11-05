package nz.mckenzie.frigo.ui.settings

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
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.SystemPage
import nz.mckenzie.frigo.ui.friendlyMessage

data class SystemUiState(
    val loading: Boolean = true,
    val page: SystemPage? = null,
    val error: String? = null,
    /** Between asking for a restart and the server answering again. */
    val restarting: Boolean = false,
    /** Whether the yes-or-no about restarting is on screen. */
    val asking: Boolean = false,
)

/**
 * The numbers the server reports about itself, read again every few seconds while
 * the page is open.
 *
 * The reading stops when you leave the page, so nothing is asked for in the
 * background. A restart is the one thing that changes anything, and it is only
 * offered to an admin.
 */
class SystemViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(SystemUiState())
    val state = _state.asStateFlow()

    private var poll: Job? = null

    /** True once the server has stopped answering, which is what a restart looks like. */
    private var wentDown = false
    private var restartAt = 0L

    fun start() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive) {
                fetch()
                delay(RefreshMillis)
            }
        }
    }

    fun stop() {
        poll?.cancel()
        poll = null
    }

    /** One read, for the Try again button. */
    fun load() {
        viewModelScope.launch { fetch() }
    }

    private suspend fun fetch() {
        runCatching { repository.systemPage() }
            .onSuccess { page ->
                val backAgain = wentDown || System.currentTimeMillis() - restartAt > GiveUpMillis
                wentDown = false
                _state.update {
                    it.copy(
                        loading = false,
                        page = page,
                        error = null,
                        restarting = it.restarting && !backAgain,
                    )
                }
            }
            .onFailure { failure ->
                // While it restarts, not answering is the expected answer.
                if (_state.value.restarting) {
                    wentDown = true
                    return@onFailure
                }
                _state.update { it.copy(loading = false, error = failure.friendlyMessage()) }
            }
    }

    fun askToRestart() {
        if (_state.value.page?.restartable != true) return
        _state.update { it.copy(asking = true) }
    }

    fun cancelRestart() {
        _state.update { it.copy(asking = false) }
    }

    fun restart() {
        _state.update { it.copy(asking = false, restarting = true, error = null) }
        wentDown = false
        restartAt = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { repository.restartServer() }
                .onFailure { failure ->
                    _state.update { it.copy(restarting = false, error = failure.friendlyMessage()) }
                }
        }
    }

    companion object {
        /** How often the page asks again while it is open. */
        private const val RefreshMillis = 5_000L

        /**
         * After this long without the server answering, the wait for the restart
         * gives up rather than spinning for ever.
         */
        private const val GiveUpMillis = 120_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { SystemViewModel(container.repository) }
        }
    }
}
