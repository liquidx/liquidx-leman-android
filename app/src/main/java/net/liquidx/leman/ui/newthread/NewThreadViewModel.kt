package net.liquidx.leman.ui.newthread

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.Settings

data class NewThreadUiState(
    val agentName: String = Settings.DEFAULT_AGENT_NAME,
    val starting: Boolean = false,
    val startFailed: Boolean = false,
)

sealed interface NewThreadEvent {
    data object Start : NewThreadEvent
}

class NewThreadViewModel(
    private val repo: ThreadRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    /** Composer buffer — the field owns its text; 2c always opens a fresh draft. */
    val textState = TextFieldState()

    private val starting = MutableStateFlow(false)

    /** `POST /api/sessions` failed — stay on 2c and let the user retry the send (spec §4). */
    private val startFailed = MutableStateFlow(false)

    /** One-shot: emits the created thread id → navigate to 2b. */
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    val state: StateFlow<NewThreadUiState> = combine(
        settingsStore.settings,
        starting,
        startFailed,
    ) { settings, s, failed ->
        NewThreadUiState(agentName = settings.agentName, starting = s, startFailed = failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewThreadUiState())

    fun onEvent(event: NewThreadEvent) {
        when (event) {
            NewThreadEvent.Start -> {
                val message = textState.text.toString().trim()
                if (message.isEmpty() || starting.value) return
                starting.value = true
                startFailed.value = false
                viewModelScope.launch {
                    val id = repo.createThread(message)
                    starting.value = false
                    if (id != null) _created.tryEmit(id) else startFailed.value = true
                }
            }
        }
    }
}
