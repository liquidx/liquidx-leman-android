package net.liquidx.leman.domain.model

/** Client-owned thread lifecycle (spec 03). */
enum class ThreadState { Idle, Running, Failed }

enum class TurnKind { User, Agent, Trace }

/** Send lifecycle for user turns (spec 03). */
enum class SendState { Synced, Sending, Failed }

data class AgentProfile(
    val name: String,
    val glyph: String,
)

/**
 * A thread is a local conversation — the gateway has no thread store, so the app
 * is the system of record (spec 02/03).
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
    val sessionId: String?,
    val agentName: String?,
    val agentGlyph: String?,
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

/** A finalized thinking trace, composed client-side from run events (spec 05). */
data class Trace(
    val reasoning: String?,
    val steps: List<TraceStep>,
)

data class TraceStep(
    val tool: String,
    val preview: String?,
    val durationSeconds: Double,
    val error: Boolean,
)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)
