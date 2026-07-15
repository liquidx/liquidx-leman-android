package net.liquidx.leman.domain.model

/**
 * Domain form of a gateway run event (spec 02). The wire framing is `data:`-only
 * SSE with the type inside the JSON payload; unknown events map to [Unknown]
 * rather than crashing — the gateway evolves independently.
 */
sealed interface RunEvent {
    val timestamp: Double

    data class MessageDelta(val delta: String, override val timestamp: Double) : RunEvent

    data class Reasoning(val text: String, override val timestamp: Double) : RunEvent

    data class ToolStarted(
        val tool: String,
        val preview: String?,
        override val timestamp: Double,
    ) : RunEvent

    data class ToolCompleted(
        val tool: String,
        val duration: Double,
        val error: Boolean,
        override val timestamp: Double,
    ) : RunEvent

    data class RunCompleted(
        val output: String,
        val usage: TokenUsage?,
        override val timestamp: Double,
    ) : RunEvent

    data class Unknown(val raw: String, override val timestamp: Double = 0.0) : RunEvent
}
