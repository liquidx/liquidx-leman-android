package net.liquidx.leman.data.repo

import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.local.encodeTrace
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind

/**
 * Rebuilds a synced thread's turns from the server's message history (spec 03).
 * Deterministic ids (`msg-`/`trace-` + server message id) make the rebuild
 * idempotent. Tool results (`role: tool`) carry no timing, so trace steps map
 * tool_calls + reasoning only; durations stay 0 for synced history.
 */
fun sessionTurns(sessionId: String, messages: List<SessionMessageDto>): List<TurnEntity> {
    val turns = mutableListOf<TurnEntity>()
    val pendingSteps = mutableListOf<TraceStep>()
    var seq = 0L

    fun turn(id: String, kind: String, createdAt: Long, markdown: String?, traceJson: String? = null) =
        TurnEntity(
            id = id, threadId = sessionId, seq = ++seq, kind = kind, createdAt = createdAt,
            markdown = markdown, blocksJson = null, traceJson = traceJson, runId = null,
            sendState = "synced", viaButton = false,
        )

    fun flushTrace(anchor: String, createdAt: Long) {
        if (pendingSteps.isEmpty()) return
        turns += turn(
            id = "trace-$sessionId-$anchor", kind = "trace", createdAt = createdAt,
            markdown = null, traceJson = encodeTrace(Trace(pendingSteps.toList())),
        )
        pendingSteps.clear()
    }

    for (m in messages) {
        val createdAt = (m.timestamp * 1000).toLong()
        when (m.role) {
            "user" -> {
                if (m.content.isNullOrEmpty()) continue
                turns += turn("msg-$sessionId-${m.id}", "user", createdAt, m.content)
            }
            "assistant" -> {
                m.reasoning?.takeIf { it.isNotBlank() }?.let {
                    pendingSteps += TraceStep(TraceStepKind.Reasoning, summary = it)
                }
                m.toolCalls.orEmpty().forEach { call ->
                    val name = call.function?.name ?: return@forEach
                    pendingSteps += TraceStep(
                        TraceStepKind.Tool, tool = name,
                        summary = call.function.arguments?.take(120),
                    )
                }
                if (!m.content.isNullOrEmpty()) {
                    flushTrace(anchor = "${m.id}", createdAt = createdAt)
                    turns += turn("msg-$sessionId-${m.id}", "agent", createdAt, m.content)
                }
            }
            else -> Unit // tool results: no user-visible turn, no timing data
        }
    }
    val tailAt = messages.lastOrNull()?.let { (it.timestamp * 1000).toLong() } ?: 0L
    flushTrace(anchor = "tail", createdAt = tailAt)
    return turns
}
