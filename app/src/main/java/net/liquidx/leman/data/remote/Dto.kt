package net.liquidx.leman.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Wire JSON config: the gateway evolves independently, so never crash on new fields. */
val HermesJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class HealthDto(
    val status: String,
    val platform: String,
    val version: String,
)

@Serializable
data class UsageDto(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

@Serializable
data class ErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val source: String = "api_server",
    val model: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double = 0.0,
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** POST /api/sessions nests the created session under `session`. */
@Serializable
data class SessionEnvelopeDto(val session: SessionDto)

@Serializable
data class ToolCallFunctionDto(val name: String? = null, val arguments: String? = null)

@Serializable
data class ToolCallDto(val function: ToolCallFunctionDto? = null)

@Serializable
data class SessionMessageDto(
    val id: Long,
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val reasoning: String? = null,
    val timestamp: Double = 0.0,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class SessionMessagesDto(
    @SerialName("session_id") val sessionId: String = "",
    val data: List<SessionMessageDto> = emptyList(),
)

@Serializable
data class ChatRequestDto(val message: String)

@Serializable
data class SessionPatchDto(val title: String)

/** POST /api/devices — registers this device's FCM token for push (FCM push client). */
@Serializable
data class DeviceRegistrationDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("fcm_token") val fcmToken: String,
)

/** GET /v1/capabilities — gate the whole Sessions feature on these flags (spec §5). */
@Serializable
data class CapabilitiesDto(
    val version: String? = null,
    val features: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
) {
    private fun flag(name: String): Boolean {
        val primitive = features[name] as? JsonPrimitive
        return primitive?.content?.toBooleanStrictOrNull() == true
    }
    val supportsSessions: Boolean
        get() = flag("session_resources") && flag("session_chat_streaming")
}
