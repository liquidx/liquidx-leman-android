package net.liquidx.leman.ui.config

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Settings

/** 2d "test connection" caption states (spec 04 §5). */
sealed interface TestConnectionState {
    data object Idle : TestConnectionState
    data object Testing : TestConnectionState
    data class Ok(val platform: String, val version: String) : TestConnectionState
    data class Unreachable(val detail: String) : TestConnectionState
    data object AuthFailed : TestConnectionState
}

data class ConfigUiState(
    val urlError: String? = null,
    val apiKeyMasked: String? = null,       // null = no key stored
    val revealedKey: String? = null,
    val settings: Settings = Settings(),
    val connState: ConnState = ConnState.NotConfigured,
    val testResult: TestConnectionState = TestConnectionState.Idle,
    val confirmClearArmed: Boolean = false, // two-tap confirm; no dialogs in the design
    val loaded: Boolean = false,
)

sealed interface ConfigEvent {
    data object SaveServerUrl : ConfigEvent
    data object TestConnection : ConfigEvent
    data object SaveApiKey : ConfigEvent
    data object RevealKey : ConfigEvent
    data object HideKey : ConfigEvent
    data class SetBiometricUnlock(val enabled: Boolean) : ConfigEvent
    data class SelectGlyph(val glyph: String) : ConfigEvent
    data class SetExpandTraces(val enabled: Boolean) : ConfigEvent
    data class SetShowToolArgs(val enabled: Boolean) : ConfigEvent
    data class SetNotificationsEnabled(val enabled: Boolean) : ConfigEvent
    data object ClearAllThreads : ConfigEvent
}

class ConfigViewModel(
    private val settingsStore: SettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val connectionManager: ConnectionManager,
    private val repo: ThreadRepository,
    private val allowHttp: Boolean = false, // debug builds may target LAN gateways
) : ViewModel() {

    /**
     * Field buffers, owned here so they survive navigation. The fields are the
     * source of truth while editing — nothing writes text back into them per
     * keystroke (an async round-trip resets the cursor); we only seed them once
     * from DataStore and mirror agent-name edits out via [snapshotFlow].
     */
    val serverUrlState = TextFieldState()
    val apiKeyState = TextFieldState()
    val agentNameState = TextFieldState()

    private val urlError = MutableStateFlow<String?>(null)
    private var erroredUrlText: String? = null // field text that produced urlError
    private val apiKeyMasked = MutableStateFlow<String?>(null)
    private val revealedKey = MutableStateFlow<String?>(null)
    private val testResult = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)
    private val confirmClearArmed = MutableStateFlow(false)
    private var confirmJob: Job? = null

    init {
        viewModelScope.launch { refreshMask() }
        viewModelScope.launch {
            val stored = settingsStore.settings.first()
            if (serverUrlState.text.isEmpty()) serverUrlState.setTextAndPlaceCursorAtEnd(stored.serverUrl)
            if (agentNameState.text.isEmpty()) agentNameState.setTextAndPlaceCursorAtEnd(stored.agentName)
            snapshotFlow { agentNameState.text.toString() }.collect { name ->
                settingsStore.update { if (it.agentName == name) it else it.copy(agentName = name) }
            }
        }
        viewModelScope.launch {
            // Editing the url clears its validation error. Compared against the
            // text that failed so a queued emission can't wipe a fresh error.
            snapshotFlow { serverUrlState.text.toString() }.collect { text ->
                if (erroredUrlText != null && text != erroredUrlText) {
                    erroredUrlText = null
                    urlError.value = null
                }
            }
        }
    }

    val state: StateFlow<ConfigUiState> = combine(
        settingsStore.settings,
        connectionManager.state,
        urlError,
        combine(apiKeyMasked, revealedKey) { m, r -> m to r },
        combine(testResult, confirmClearArmed) { t, c -> t to c },
    ) { settings, conn, error, key, misc ->
        ConfigUiState(
            urlError = error,
            apiKeyMasked = key.first,
            revealedKey = key.second,
            settings = settings,
            connState = conn,
            testResult = misc.first,
            confirmClearArmed = misc.second,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfigUiState())

    fun onEvent(event: ConfigEvent) {
        when (event) {
            ConfigEvent.SaveServerUrl -> saveServerUrl()
            ConfigEvent.TestConnection -> testConnection()
            ConfigEvent.SaveApiKey -> viewModelScope.launch {
                val value = apiKeyState.text.toString().trim()
                if (value.isEmpty()) return@launch
                apiKeyStore.set(value)
                apiKeyState.clearText()
                refreshMask()
                connectionManager.reconfigure()
            }
            ConfigEvent.RevealKey -> viewModelScope.launch { revealedKey.value = apiKeyStore.get() }
            ConfigEvent.HideKey -> revealedKey.value = null
            is ConfigEvent.SetBiometricUnlock -> update { it.copy(biometricUnlock = event.enabled) }
            is ConfigEvent.SelectGlyph -> {
                if (event.glyph in Settings.GLYPH_CHOICES) {
                    update { it.copy(agentGlyph = event.glyph) }
                }
            }
            is ConfigEvent.SetExpandTraces -> update { it.copy(expandTracesByDefault = event.enabled) }
            is ConfigEvent.SetShowToolArgs -> update { it.copy(showToolArgs = event.enabled) }
            is ConfigEvent.SetNotificationsEnabled -> update { it.copy(notificationsEnabled = event.enabled) }
            ConfigEvent.ClearAllThreads -> {
                if (confirmClearArmed.value) {
                    confirmJob?.cancel()
                    confirmClearArmed.value = false
                    viewModelScope.launch { repo.clearAll() }
                } else {
                    confirmClearArmed.value = true
                    confirmJob?.cancel()
                    confirmJob = viewModelScope.launch {
                        delay(3_000)
                        confirmClearArmed.value = false
                    }
                }
            }
        }
    }

    suspend fun exportJson(): String = repo.exportJson()

    /** Local validation before save (spec 04 §5): scheme + host. */
    private fun saveServerUrl() {
        val fieldText = serverUrlState.text.toString()
        val raw = fieldText.trim()
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        val schemeOk = uri?.scheme == "https" || (allowHttp && uri?.scheme == "http")
        if (uri == null || !schemeOk || uri.host.isNullOrBlank()) {
            erroredUrlText = fieldText
            urlError.value = if (allowHttp) "enter an http(s):// origin" else "enter an https:// origin"
            return
        }
        erroredUrlText = null
        urlError.value = null
        viewModelScope.launch {
            val normalized = raw.trimEnd('/')
            settingsStore.update { it.copy(serverUrl = normalized) }
            serverUrlState.setTextAndPlaceCursorAtEnd(normalized)
            connectionManager.reconfigure()
        }
    }

    private fun testConnection() {
        testResult.value = TestConnectionState.Testing
        viewModelScope.launch {
            testResult.value = when (val result = connectionManager.probe()) {
                is ApiResult.Ok -> TestConnectionState.Ok(result.value.platform, result.value.version)
                is ApiResult.Err -> when (val e = result.error) {
                    is ApiError.Auth -> TestConnectionState.AuthFailed
                    ApiError.Timeout -> TestConnectionState.Unreachable("timed out after 10s")
                    ApiError.NotConfigured -> TestConnectionState.Unreachable("set url + api key first")
                    is ApiError.Network -> TestConnectionState.Unreachable("network error")
                    is ApiError.Server -> TestConnectionState.Unreachable("server error ${e.code}")
                    is ApiError.Client -> TestConnectionState.Unreachable("http ${e.code}")
                    is ApiError.Protocol -> TestConnectionState.Unreachable("bad response")
                }
            }
        }
    }

    /**
     * Persists the notifications toggle and only returns once the write is durable.
     * Callers that enqueue device registration must use this rather than the
     * fire-and-forget event: DeviceRegistrar re-reads this setting and reports DONE
     * (never retried) if it still sees the stale value.
     */
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        settingsStore.update { it.copy(notificationsEnabled = enabled) }
    }

    private suspend fun refreshMask() {
        apiKeyMasked.value = apiKeyStore.get()?.let(::maskKey)
    }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    companion object {
        /** `hm_••••••••••••3kf2` style (design 2d). */
        fun maskKey(key: String): String = when {
            key.length <= 7 -> "••••"
            else -> key.take(3) + "••••••••••••" + key.takeLast(4)
        }
    }
}
