package net.liquidx.leman.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.liquidx.leman.data.remote.HealthDto
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Settings

/**
 * Owns the gateway health state (spec 02). Probes on start/reconfigure; retries
 * with backoff on network/server failures (invisible except the status dot);
 * stops dead on auth failures — the user must fix the key in 2d (spec 04).
 */
class ConnectionManager(
    private val client: HermesClient,
    private val settings: Flow<Settings>,
    private val apiKey: suspend () -> String?,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.NotConfigured)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var retryJob: Job? = null

    /** Re-read settings + key, rebuild the transport, and probe. */
    fun reconfigure() {
        retryJob?.cancel()
        scope.launch {
            val key = apiKey()
            val url = settings.first().serverUrl
            if (key.isNullOrBlank()) {
                client.reconfigure(null, null)
                _state.value = ConnState.NotConfigured
                return@launch
            }
            client.reconfigure(url, key)
            backoff.reset()
            _state.value = ConnState.Checking
            probeInternal()
        }
    }

    /** Manual probe (2d "test connection"). Also refreshes [state]. */
    suspend fun probe(): ApiResult<HealthDto> {
        val result = client.health()
        applyResult(result, scheduleRetry = false)
        return result
    }

    /** A 401 anywhere (e.g. mid-run) poisons the whole app state (spec 04). */
    fun onAuthFailure(code: Int) {
        retryJob?.cancel()
        _state.value = ConnState.Unauthorized(ApiError.Auth(code))
    }

    private suspend fun probeInternal() {
        applyResult(client.health(), scheduleRetry = true)
    }

    private suspend fun applyResult(result: ApiResult<HealthDto>, scheduleRetry: Boolean) {
        when (result) {
            is ApiResult.Ok -> {
                val version = result.value.version
                when (val caps = client.capabilities()) {
                    // Capabilities reachable → a definitive answer either way.
                    is ApiResult.Ok -> {
                        backoff.reset()
                        _state.value = if (caps.value.supportsSessions) {
                            ConnState.Online(version)
                        } else {
                            ConnState.Unsupported(version)
                        }
                    }
                    is ApiResult.Err -> when (val error = caps.error) {
                        is ApiError.Auth -> {
                            backoff.reset()
                            _state.value = ConnState.Unauthorized(error)
                        }
                        ApiError.NotConfigured -> {
                            backoff.reset()
                            _state.value = ConnState.NotConfigured
                        }
                        // A client-class miss (e.g. 404) means an old gateway with no
                        // capabilities endpoint — genuinely, definitively unsupported.
                        is ApiError.Client -> {
                            backoff.reset()
                            _state.value = ConnState.Unsupported(version)
                        }
                        // Network/timeout/server-class: transient. Don't dead-end the
                        // whole feature on a blip — go Offline and retry like a health
                        // failure, letting the backoff grow across attempts.
                        else -> goOffline(error, scheduleRetry)
                    }
                }
            }
            is ApiResult.Err -> when (val error = result.error) {
                is ApiError.Auth -> _state.value = ConnState.Unauthorized(error)
                ApiError.NotConfigured -> _state.value = ConnState.NotConfigured
                else -> goOffline(error, scheduleRetry)
            }
        }
    }

    private fun goOffline(error: ApiError, scheduleRetry: Boolean) {
        _state.value = ConnState.Offline(error)
        if (scheduleRetry) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(backoff.nextDelayMillis())
                probeInternal()
            }
        }
    }
}
