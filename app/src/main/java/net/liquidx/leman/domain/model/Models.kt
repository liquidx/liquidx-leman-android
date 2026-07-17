package net.liquidx.leman.domain.model

/** Client-owned thread lifecycle (spec 03). */
enum class ThreadState { Idle, Running, Failed }

enum class TurnKind { User, Agent, Trace, System }

/** Send lifecycle for user turns (spec 03). */
enum class SendState { Synced, Sending, Failed }

data class AgentProfile(
    val name: String,
    val glyph: String,
)

/**
 * A thread is a cache of a gateway session — the server is the system of
 * record; `id` IS the server session id (spec 02/03).
 */
data class Thread(
    val id: String,
    val title: String,
    val preview: String,
    val state: ThreadState,
    val pinned: Boolean,
    val unread: Boolean,
    val createdAt: Long,
    val lastActiveAt: Long,
    val source: String,
    val agentName: String?,
    val agentGlyph: String?,
    /** High-water mark of read turns (ux-fixes spec): drives open-at-first-unread. */
    val lastReadAt: Long = 0,
) {
    /** Per-thread identity override beats the global default (spec 03). */
    fun profileOr(default: AgentProfile): AgentProfile = AgentProfile(
        name = agentName ?: default.name,
        glyph = agentGlyph ?: default.glyph,
    )
}

data class Turn(
    val id: String,
    val threadId: String,
    val seq: Long,
    val kind: TurnKind,
    val createdAt: Long,
    val markdown: String?,
    val trace: Trace?,
    val runId: String?,
    val sendState: SendState,
    val viaButton: Boolean,
)

/**
 * A finalized thinking trace, composed client-side from run events (spec 05).
 * Ordered steps of tool calls and reasoning rows — the design's trace table.
 * Serializable because it persists as one JSON column on the turn (spec 03).
 */
@kotlinx.serialization.Serializable
data class Trace(
    val steps: List<TraceStep>,
)

@kotlinx.serialization.Serializable
enum class TraceStepKind { Tool, Reasoning }

@kotlinx.serialization.Serializable
data class TraceStep(
    val kind: TraceStepKind,
    /** Tool name for [TraceStepKind.Tool] steps; null for reasoning rows. */
    val tool: String? = null,
    /** Tool args/query preview, or the reasoning text. */
    val summary: String? = null,
    val durationSeconds: Double = 0.0,
    val error: Boolean = false,
)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)
