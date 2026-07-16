package net.liquidx.leman.data.local

import kotlinx.serialization.json.Json
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.Thread
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.Turn
import net.liquidx.leman.domain.model.TurnKind

private val LocalJson = Json { ignoreUnknownKeys = true }

fun encodeTrace(trace: Trace): String = LocalJson.encodeToString(Trace.serializer(), trace)

/** Defensive: a malformed persisted trace renders as absent, never a crash (spec 04). */
fun decodeTrace(json: String): Trace? =
    runCatching { LocalJson.decodeFromString(Trace.serializer(), json) }.getOrNull()

fun ThreadEntity.toDomain(): Thread = Thread(
    id = id,
    title = title,
    preview = preview,
    state = when (state) {
        "running" -> ThreadState.Running
        "failed" -> ThreadState.Failed
        else -> ThreadState.Idle
    },
    pinned = pinned,
    unread = unread,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    source = source,
    agentName = agentName,
    agentGlyph = agentGlyph,
)

fun Thread.toEntity(): ThreadEntity = ThreadEntity(
    id = id,
    title = title,
    preview = preview,
    state = when (state) {
        ThreadState.Idle -> "idle"
        ThreadState.Running -> "running"
        ThreadState.Failed -> "failed"
    },
    pinned = pinned,
    unread = unread,
    createdAt = createdAt,
    lastActiveAt = lastActiveAt,
    source = source,
    agentName = agentName,
    agentGlyph = agentGlyph,
)

fun TurnEntity.toDomain(): Turn = Turn(
    id = id,
    threadId = threadId,
    seq = seq,
    kind = when (kind) {
        "agent" -> TurnKind.Agent
        "trace" -> TurnKind.Trace
        else -> TurnKind.User
    },
    createdAt = createdAt,
    markdown = markdown,
    trace = traceJson?.let(::decodeTrace),
    runId = runId,
    sendState = when (sendState) {
        "sending" -> SendState.Sending
        "failed" -> SendState.Failed
        else -> SendState.Synced
    },
    viaButton = viaButton,
)

fun Turn.toEntity(): TurnEntity = TurnEntity(
    id = id,
    threadId = threadId,
    seq = seq,
    kind = when (kind) {
        TurnKind.User -> "user"
        TurnKind.Agent -> "agent"
        TurnKind.Trace -> "trace"
    },
    createdAt = createdAt,
    markdown = markdown,
    blocksJson = null,
    traceJson = trace?.let(::encodeTrace),
    runId = runId,
    sendState = when (sendState) {
        SendState.Synced -> "synced"
        SendState.Sending -> "sending"
        SendState.Failed -> "failed"
    },
    viaButton = viaButton,
)
