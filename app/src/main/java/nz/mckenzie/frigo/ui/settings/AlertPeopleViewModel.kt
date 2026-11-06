package nz.mckenzie.frigo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nz.mckenzie.frigo.data.FrigoRepository

data class AlertPeopleUiState(
    val loading: Boolean = true,
    /** Every name Frigate has learned to recognise. */
    val offered: List<String> = emptyList(),
    /** The people ticked when the page opened, which is the list as it stands. */
    val chosen: Set<String> = emptySet(),
    /**
     * True when the server couldn't be asked for the names, so only the people
     * already ticked are listed.
     */
    val noAnswer: Boolean = false,
)

/**
 * The people this phone stays quiet about.
 *
 * The list is the phone's own, so nothing here is sent anywhere: the names come
 * from the ones Frigate has learned to recognise, and a change is written to the
 * phone. Frigate has no way to leave a name out of an alert, which is why this
 * exists.
 */
class AlertPeopleViewModel(private val repository: FrigoRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        AlertPeopleUiState(chosen = repository.alertPeople.value.leftOut),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val names = runCatching { repository.knownPeople() }.getOrNull()
            _state.update { current ->
                current.copy(
                    loading = false,
                    noAnswer = names == null,
                    // A name Frigate no longer recognises stays listed, so the one
                    // left out can be put back.
                    offered = ((names ?: emptyList()) + current.chosen).distinct().sorted(),
                )
            }
        }
    }

    /** One person on or off the list. */
    fun toggle(name: String) {
        _state.update { current ->
            current.copy(
                chosen = if (name in current.chosen) {
                    current.chosen - name
                } else {
                    current.chosen + name
                },
            )
        }
    }

    /** Keeps the list on the phone. Empty means everyone may reach you. */
    fun save(names: Set<String>) {
        _state.update { it.copy(chosen = names) }
        viewModelScope.launch { repository.setAlertPeople(names) }
    }

    companion object {
        fun factory(repository: FrigoRepository) = viewModelFactory {
            initializer { AlertPeopleViewModel(repository) }
        }
    }
}
