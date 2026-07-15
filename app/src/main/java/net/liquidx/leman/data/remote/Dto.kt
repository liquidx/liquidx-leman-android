package net.liquidx.leman.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire JSON config: the gateway evolves independently, never crash on new fields. */
val HermesJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class HealthDto(
    val status: String,
    val platform: String,
    val version: String,
)

@Serializable
data class RunAcceptedDto(
    @SerialName("run_id") val runId: String,
    val status: String,
)

enum class RunStatus { Started, Running, Completed, Failed, Unknown }

@Serializable
data class RunDto(
    @SerialName("object") val objectType: String = "hermes.run",
    @SerialName("run_id") val runId: String,
    val status: String,
    @SerialName("session_id") val sessionId: String,
    val model: String,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("updated_at") val updatedAt: Double,
    @SerialName("last_event") val lastEvent: String? = null,
    val output: String? = null,
    val usage: UsageDto? = null,
) {
    /** Unknown status strings map to an explicit value rather than crashing (spec 02). */
    val runStatus: RunStatus
        get() = when (status) {
            "started" -> RunStatus.Started
            "running" -> RunStatus.Running
            "completed" -> RunStatus.Completed
            "failed" -> RunStatus.Failed
            else -> RunStatus.Unknown
        }
}

@Serializable
data class UsageDto(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class WireMessage(val role: String, val content: String)

@Serializable
data class RunRequestDto(
    val model: String,
    val input: List<WireMessage>,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

@Serializable
data class ErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
