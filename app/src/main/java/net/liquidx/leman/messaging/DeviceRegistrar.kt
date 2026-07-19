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
    /**
     * Auto-init is off in the manifest so the device isn't push-reachable before
     * opt-in; enabling it here is the opt-in. Injected because the static
     * FirebaseMessaging call needs an initialized Firebase app (absent in tests).
     */
    private val enableAutoInit: () -> Unit = {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = true
    },
    /**
     * Opt-out half of the injected auto-init pair: turns local delivery back
     * off. Injected for the same reason as [enableAutoInit] — no initialized
     * Firebase app under Robolectric.
     */
    private val disableAutoInit: () -> Unit = {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
    },
    /** Deletes the local FCM token on opt-out. Injected to keep tests Firebase-free. */
    private val deleteToken: suspend () -> Unit = { deleteFcmToken() },
    private val tokenProvider: suspend () -> String?,
) {
    enum class Outcome { DONE, RETRY_LATER, GAVE_UP }

    suspend fun register(): Outcome {
        if (!settingsStore.settings.first().notificationsEnabled) return Outcome.DONE
        enableAutoInit()
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

    /**
     * Opt-out: tells the server to stop pushing to this device, then disables
     * local delivery regardless of the network outcome. Order matters — the
     * server call happens first so we don't drop the token before telling the
     * server about it. A `404` (endpoint not built yet) counts as [Outcome.DONE],
     * not an error — see [HermesClient.unregisterDevice].
     */
    suspend fun unregister(): Outcome {
        val deviceId = pushPrefs.deviceId()
        val outcome = when (val r = client.unregisterDevice(deviceId)) {
            is ApiResult.Ok -> Outcome.DONE
            is ApiResult.Err -> when (r.error) {
                is ApiError.Client -> if (r.error.code == 404) Outcome.DONE else Outcome.GAVE_UP
                is ApiError.Network, ApiError.Timeout, is ApiError.Server -> Outcome.RETRY_LATER
                else -> Outcome.GAVE_UP
            }
        }
        disableAutoInit()
        deleteToken()
        return outcome
    }
}
