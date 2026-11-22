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
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.ui.friendlyMessage

data class HomeAssistantUiState(
    val loading: Boolean = true,
    val address: String = "",
    val token: String = "",
    /** Whether the phone already has a box, which is what Forget takes away. */
    val set: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * The two things Home Assistant needs to be asked anything.
 *
 * The save is checked against the box before it is kept, so a wrong address or a
 * token Home Assistant has forgotten comes back as a sentence here rather than the
 * first time a light is tapped.
 */
class HomeAssistantViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeAssistantUiState())
    val state = _state.asStateFlow()

    init {
        val known = repository.homeAssistant.value
        _state.update {
            it.copy(
                loading = false,
                address = known?.address.orEmpty(),
                token = known?.token.orEmpty(),
                set = known != null,
            )
        }
    }

    fun setAddress(value: String) = _state.update { it.copy(address = value, error = null) }

    fun setToken(value: String) = _state.update { it.copy(token = value, error = null) }

    fun save() {
        val now = _state.value
        if (now.saving) return

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.setHomeAssistant(now.address, now.token) }
                .onSuccess {
                    _state.update { it.copy(saving = false, saved = true, set = true) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            saving = false,
                            error = failure.friendlyMessage(repository.context),
                        )
                    }
                }
        }
    }

    fun forget() {
        viewModelScope.launch {
            repository.forgetHomeAssistant()
            _state.update {
                it.copy(address = "", token = "", set = false, error = null)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HomeAssistantViewModel(container.repository) }
        }
    }
}
