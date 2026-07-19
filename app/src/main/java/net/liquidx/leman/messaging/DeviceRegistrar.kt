package net.liquidx.leman.messaging

import kotlinx.coroutines.flow.first
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.PushPrefsStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult

/**
 * Outbound half of the push client: registers this device's FCM token with the
 * gateway. Resilient — a missing endpoint or absent key never crashes; it just
 * stops and is re-triggered on the next app start or token rotation. The
 * [HermesClient] transport must already be configured by the caller.
 */
class DeviceRegistrar(
    private val client: HermesClient,
    private val settingsStore: SettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val pushPrefs: PushPrefsStore,
    private val tokenProvider: suspend () -> String?,
) {
    enum class Outcome { DONE, RETRY_LATER, GAVE_UP }

    suspend fun register(): Outcome {
        if (!settingsStore.settings.first().notificationsEnabled) return Outcome.DONE
        if (apiKeyStore.get().isNullOrBlank()) return Outcome.GAVE_UP
        val token = tokenProvider() ?: return Outcome.RETRY_LATER
        return when (val r = client.registerDevice(token, pushPrefs.deviceId())) {
            is ApiResult.Ok -> Outcome.DONE
            is ApiResult.Err -> when (r.error) {
                is ApiError.Network, ApiError.Timeout, is ApiError.Server -> Outcome.RETRY_LATER
                else -> Outcome.GAVE_UP
            }
        }
    }
}
