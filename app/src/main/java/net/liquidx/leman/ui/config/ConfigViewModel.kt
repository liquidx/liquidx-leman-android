package net.liquidx.leman.ui.config

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
    val serverUrlInput: String = "",
    val urlError: String? = null,
    val apiKeyMasked: String? = null,       // null = no key stored
    val apiKeyInput: String = "",
    val revealedKey: String? = null,
    val settings: Settings = Settings(),
    val connState: ConnState = ConnState.NotConfigured,
    val testResult: TestConnectionState = TestConnectionState.Idle,
    val confirmClearArmed: Boolean = false, // two-tap confirm; no dialogs in the design
    val loaded: Boolean = false,
)

sealed interface ConfigEvent {
    data class SetServerUrlInput(val value: String) : ConfigEvent
    data object SaveServerUrl : ConfigEvent
    data object TestConnection : ConfigEvent
    data class SetApiKeyInput(val value: String) : ConfigEvent
    data object SaveApiKey : ConfigEvent
    data object RevealKey : ConfigEvent
    data object HideKey : ConfigEvent
    data class SetBiometricUnlock(val enabled: Boolean) : ConfigEvent
    data class SetAgentName(val name: String) : ConfigEvent
    data class SelectGlyph(val glyph: String) : ConfigEvent
    data class SetExpandTraces(val enabled: Boolean) : ConfigEvent
    data class SetShowToolArgs(val enabled: Boolean) : ConfigEvent
    data object ClearAllThreads : ConfigEvent
}

class ConfigViewModel(
    private val settingsStore: SettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val connectionManager: ConnectionManager,
    private val repo: ThreadRepository,
    private val allowHttp: Boolean = false, // debug builds may target LAN gateways
) : ViewModel() {

    private val urlInput = MutableStateFlow<String?>(null) // null = mirror settings
    private val urlError = MutableStateFlow<String?>(null)
    private val apiKeyMasked = MutableStateFlow<String?>(null)
    private val apiKeyInput = MutableStateFlow("")
    private val revealedKey = MutableStateFlow<String?>(null)
    private val testResult = MutableStateFlow<TestConnectionState>(TestConnectionState.Idle)
    private val confirmClearArmed = MutableStateFlow(false)
    private var confirmJob: Job? = null

    init {
        viewModelScope.launch { refreshMask() }
    }

    val state: StateFlow<ConfigUiState> = combine(
        settingsStore.settings,
        connectionManager.state,
        urlInput,
        urlError,
        combine(apiKeyMasked, apiKeyInput, revealedKey) { m, i, r -> Triple(m, i, r) },
        combine(testResult, confirmClearArmed) { t, c -> t to c },
    ) { values ->
        val settings = values[0] as Settings
        val conn = values[1] as ConnState
        val input = values[2] as String?
        val error = values[3] as String?

        @Suppress("UNCHECKED_CAST")
        val key = values[4] as Triple<String?, String, String?>

        @Suppress("UNCHECKED_CAST")
        val misc = values[5] as Pair<TestConnectionState, Boolean>
        ConfigUiState(
            serverUrlInput = input ?: settings.serverUrl,
            urlError = error,
            apiKeyMasked = key.first,
            apiKeyInput = key.second,
            revealedKey = key.third,
            settings = settings,
            connState = conn,
            testResult = misc.first,
            confirmClearArmed = misc.second,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfigUiState())

    fun onEvent(event: ConfigEvent) {
        when (event) {
            is ConfigEvent.SetServerUrlInput -> {
                urlInput.value = event.value
                urlError.value = null
            }
            ConfigEvent.SaveServerUrl -> saveServerUrl()
            ConfigEvent.TestConnection -> testConnection()
            is ConfigEvent.SetApiKeyInput -> apiKeyInput.value = event.value
            ConfigEvent.SaveApiKey -> viewModelScope.launch {
                val value = apiKeyInput.value.trim()
                if (value.isEmpty()) return@launch
                apiKeyStore.set(value)
                apiKeyInput.value = ""
                refreshMask()
                connectionManager.reconfigure()
            }
            ConfigEvent.RevealKey -> viewModelScope.launch { revealedKey.value = apiKeyStore.get() }
            ConfigEvent.HideKey -> revealedKey.value = null
            is ConfigEvent.SetBiometricUnlock -> update { it.copy(biometricUnlock = event.enabled) }
            is ConfigEvent.SetAgentName -> update { it.copy(agentName = event.name) }
            is ConfigEvent.SelectGlyph -> {
                if (event.glyph in Settings.GLYPH_CHOICES) {
                    update { it.copy(agentGlyph = event.glyph) }
                }
            }
            is ConfigEvent.SetExpandTraces -> update { it.copy(expandTracesByDefault = event.enabled) }
            is ConfigEvent.SetShowToolArgs -> update { it.copy(showToolArgs = event.enabled) }
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
        val raw = (urlInput.value ?: return).trim()
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        val schemeOk = uri?.scheme == "https" || (allowHttp && uri?.scheme == "http")
        if (uri == null || !schemeOk || uri.host.isNullOrBlank()) {
            urlError.value = if (allowHttp) "enter an http(s):// origin" else "enter an https:// origin"
            return
        }
        urlError.value = null
        viewModelScope.launch {
            settingsStore.update { it.copy(serverUrl = raw.trimEnd('/')) }
            urlInput.value = null
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
