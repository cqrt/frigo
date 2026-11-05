package nz.mckenzie.frigo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AiSetup
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.FrigoRepository
import nz.mckenzie.frigo.data.GenaiService
import nz.mckenzie.frigo.data.words
import nz.mckenzie.frigo.ui.friendlyMessage

data class AiDescriptionsUiState(
    val loading: Boolean = true,
    val setup: AiSetup? = null,
    val error: String? = null,
) {
    val on: Boolean get() = setup?.on == true
    val service: GenaiService? get() = setup?.service

    /**
     * Whether a switch may be sent. The phone's own copy of the settings can be
     * read but not changed, and only an admin may change anything.
     */
    val editable: Boolean get() = setup?.admin == true && setup?.readAt == null
}

/**
 * Whether the server describes alerts, and which model does it.
 *
 * The switch goes to the server on its own, because Frigate applies it as it
 * saves. The page shows the change at once and puts it back if the server turns
 * it down, the way every other switch here works.
 */
class AiDescriptionsViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(AiDescriptionsUiState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.aiSetup() }
                .onSuccess { setup -> _state.update { it.copy(loading = false, setup = setup) } }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.friendlyMessage(repository.context)) }
                }
        }
    }

    fun setOn(on: Boolean) {
        val setup = _state.value.setup ?: return
        if (!_state.value.editable) return
        if (setup.on == on) return

        _state.update { it.copy(setup = setup.copy(on = on), error = null) }

        viewModelScope.launch {
            runCatching { repository.setDescribeAlerts(on) }
                .onFailure { failure ->
                    _state.update {
                        it.copy(setup = setup.copy(on = setup.on), error = failure.friendlyMessage(repository.context))
                    }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AiDescriptionsViewModel(container.repository) }
        }
    }
}

data class AiModelUiState(
    val loading: Boolean = true,
    val service: GenaiService? = null,
    val chats: Boolean = false,
    val editable: Boolean = true,
    val address: String = "",
    val model: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * The address and the model a server describes with.
 *
 * A server with nothing set up gets a new service in its settings file; one
 * already set up keeps everything but the two fields typed here. The save is
 * checked by the server before it is written, so a bad address comes back as a
 * sentence rather than a silent nothing.
 */
class AiModelViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(AiModelUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { repository.aiSetup() }
                .onSuccess { setup ->
                    _state.update {
                        it.copy(
                            loading = false,
                            service = setup.service,
                            chats = setup.chats,
                            editable = setup.admin && setup.readAt == null,
                            address = setup.service?.baseUrl.orEmpty(),
                            model = setup.service?.model.orEmpty(),
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update { it.copy(loading = false, error = failure.friendlyMessage(repository.context)) }
                }
        }
    }

    fun setAddress(value: String) = _state.update { it.copy(address = value, error = null) }

    fun setModel(value: String) = _state.update { it.copy(model = value, error = null) }

    fun save() {
        val now = _state.value
        if (now.saving || !now.editable) return

        val model = now.model.trim()
        val address = now.address.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        when {
            address.length <= "http://".length -> {
                _state.update {
                    it.copy(error = repository.context.words(R.string.ai_error_address))
                }
                return
            }
            model.isEmpty() -> {
                _state.update {
                    it.copy(error = repository.context.words(R.string.ai_error_model))
                }
                return
            }
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repository.setGenaiModel(
                    name = now.service?.name,
                    baseUrl = address,
                    model = model,
                    giveChat = !now.chats,
                )
            }
                .onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { failure ->
                    _state.update { it.copy(saving = false, error = failure.friendlyMessage(repository.context)) }
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AiModelViewModel(container.repository) }
        }
    }
}
