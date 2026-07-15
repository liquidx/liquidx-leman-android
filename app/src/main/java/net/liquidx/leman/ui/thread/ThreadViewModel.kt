package net.liquidx.leman.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.StreamingRun
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ActionButton
import net.liquidx.leman.domain.model.AgentProfile
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Settings
import net.liquidx.leman.domain.model.Thread
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.domain.model.Turn
import net.liquidx.leman.ui.format.TimeFormat

data class ThreadUiState(
    val thread: Thread? = null,
    val turns: List<Turn> = emptyList(),
    val streaming: StreamingRun? = null,
    val agentProfile: AgentProfile = AgentProfile(Settings.DEFAULT_AGENT_NAME, Settings.DEFAULT_AGENT_GLYPH),
    val expandedTraces: Set<String> = emptySet(),
    val showToolArgs: Boolean = true,
    val connState: ConnState = ConnState.NotConfigured,
    val loaded: Boolean = false,
) {
    val composerEnabled: Boolean get() = connState !is ConnState.NotConfigured

    /** Header status line: `▪ DONE · juno` (+ profile origin, spec design). */
    val statusLine: String
        get() {
            val stateWord = when (thread?.state) {
                ThreadState.Running -> "RUNNING"
                ThreadState.Failed -> "FAILED"
                else -> "DONE"
            }
            val profile = if (thread?.agentName != null) "thread profile" else "default profile"
            return "▪ $stateWord · ${agentProfile.name} · $profile"
        }
}

sealed interface ThreadEvent {
    data class Send(val text: String) : ThreadEvent
    data class Retry(val turnId: String) : ThreadEvent
    data class Discard(val turnId: String) : ThreadEvent
    data class ToggleTrace(val turnId: String) : ThreadEvent
    data class ActionTapped(val button: ActionButton) : ThreadEvent
    data object TogglePin : ThreadEvent
}

class ThreadViewModel(
    private val repo: ThreadRepository,
    connectionManager: ConnectionManager,
    settingsStore: SettingsStore,
    private val savedState: SavedStateHandle,
    val threadId: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    /** Per-trace override beats the pref default, survives rotation (spec 05). */
    private val traceOverrides = MutableStateFlow(
        savedState.get<HashMap<String, Boolean>>(KEY_TRACE_OVERRIDES) ?: HashMap(),
    )

    init {
        repo.setVisibleThread(threadId)
        viewModelScope.launch {
            repo.markRead(threadId)
            repo.recoverIfRunning(threadId)
        }
    }

    val state: StateFlow<ThreadUiState> = combine(
        repo.observeThreads().map { list -> list.firstOrNull { it.id == threadId } },
        repo.observeTurns(threadId),
        repo.streaming.map { it[threadId] },
        settingsStore.settings,
        connectionManager.state,
        traceOverrides,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val thread = values[0] as Thread?

        @Suppress("UNCHECKED_CAST")
        val turns = values[1] as List<Turn>
        val streaming = values[2] as StreamingRun?
        val settings = values[3] as Settings
        val conn = values[4] as ConnState

        @Suppress("UNCHECKED_CAST")
        val overrides = values[5] as Map<String, Boolean>

        val expanded = turns.asSequence()
            .filter { it.trace != null }
            .map { it.id }
            .filter { overrides[it] ?: settings.expandTracesByDefault }
            .toSet()
        ThreadUiState(
            thread = thread,
            turns = turns,
            streaming = streaming,
            agentProfile = thread?.profileOr(settings.agentProfile) ?: settings.agentProfile,
            expandedTraces = expanded,
            showToolArgs = settings.showToolArgs,
            connState = conn,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    fun onEvent(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.Send -> viewModelScope.launch {
                if (event.text.isNotBlank()) repo.sendMessage(threadId, event.text.trim())
            }
            is ThreadEvent.Retry -> viewModelScope.launch { repo.retryTurn(event.turnId) }
            is ThreadEvent.Discard -> viewModelScope.launch { repo.discardTurn(event.turnId) }
            is ThreadEvent.ToggleTrace -> {
                val current = state.value.expandedTraces.contains(event.turnId)
                val next = HashMap(traceOverrides.value)
                next[event.turnId] = !current
                traceOverrides.value = next
                savedState[KEY_TRACE_OVERRIDES] = next
            }
            is ThreadEvent.ActionTapped -> viewModelScope.launch {
                repo.sendMessage(threadId, event.button.payload, viaButton = true)
            }
            ThreadEvent.TogglePin -> viewModelScope.launch {
                state.value.thread?.let { repo.setPinned(threadId, !it.pinned) }
            }
        }
    }

    fun gutterTimestamp(turn: Turn): String = TimeFormat.clock(turn.createdAt, zone)

    override fun onCleared() {
        repo.setVisibleThread(null)
    }

    private companion object {
        const val KEY_TRACE_OVERRIDES = "traceOverrides"
    }
}
