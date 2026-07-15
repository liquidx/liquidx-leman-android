package net.liquidx.leman.ui.newthread

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
    val text: String = "",
    val agentName: String = Settings.DEFAULT_AGENT_NAME,
    val starting: Boolean = false,
) {
    val canStart: Boolean get() = text.isNotBlank() && !starting
}

sealed interface NewThreadEvent {
    data class SetText(val text: String) : NewThreadEvent
    data object Start : NewThreadEvent
}

class NewThreadViewModel(
    private val repo: ThreadRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val text = MutableStateFlow("")
    private val starting = MutableStateFlow(false)

    /** One-shot: emits the created thread id → navigate to 2b. */
    private val _created = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val created: SharedFlow<String> = _created.asSharedFlow()

    val state: StateFlow<NewThreadUiState> = combine(
        text,
        settingsStore.settings,
        starting,
    ) { t, settings, s ->
        NewThreadUiState(text = t, agentName = settings.agentName, starting = s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewThreadUiState())

    fun onEvent(event: NewThreadEvent) {
        when (event) {
            is NewThreadEvent.SetText -> text.value = event.text
            NewThreadEvent.Start -> {
                val message = text.value.trim()
                if (message.isEmpty() || starting.value) return
                starting.value = true
                viewModelScope.launch {
                    val id = repo.createThread(message)
                    _created.tryEmit(id)
                }
            }
        }
    }
}
