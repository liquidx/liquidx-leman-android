package net.liquidx.leman.data.repo

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.local.encodeTrace
import net.liquidx.leman.data.local.toDomain
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.HermesStreamException
import net.liquidx.leman.data.remote.RunStatus
import net.liquidx.leman.data.remote.WireMessage
import net.liquidx.leman.domain.composeTrace
import net.liquidx.leman.domain.model.AgentProfile
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.Thread
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.Turn

/** Transient state of an in-flight run — never persisted per delta (spec 03). */
data class StreamingRun(
    val threadId: String,
    val runId: String,
    val text: String,
    val trace: Trace?,
    val interrupted: Boolean,
)

/**
 * The system of record for threads (spec 03). Runs write into Room; the UI
 * observes Room flows plus [streaming] for the active run's accumulating text.
 */
class ThreadRepository(
    private val db: LemanDatabase,
    private val client: HermesClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val backoffFactory: () -> Backoff = { Backoff() },
    private val onAuthFailure: (Int) -> Unit = {},
) {
    private val threadDao get() = db.threadDao()
    private val turnDao get() = db.turnDao()

    private val _streaming = MutableStateFlow<Map<String, StreamingRun>>(emptyMap())
    val streaming: StateFlow<Map<String, StreamingRun>> = _streaming.asStateFlow()

    private val activeRuns = mutableMapOf<String, Job>()

    @Volatile
    private var visibleThreadId: String? = null

    fun observeThreads(): Flow<List<Thread>> =
        threadDao.observeThreads().map { list -> list.map { it.toDomain() } }

    fun observeTurns(threadId: String): Flow<List<Turn>> =
        turnDao.observeTurns(threadId).map { list -> list.map { it.toDomain() } }

    /** Unread bookkeeping: a run completing on the visible thread is read (spec 03). */
    fun setVisibleThread(threadId: String?) {
        visibleThreadId = threadId
    }

    suspend fun createThread(firstMessage: String, profile: AgentProfile? = null): String {
        val id = newId()
        val now = clock()
        threadDao.upsertThread(
            ThreadEntity(
                id = id,
                title = firstMessage.snippet(80),
                preview = firstMessage.snippet(120),
                state = "idle",
                pinned = false,
                unread = false,
                createdAt = now,
                lastActiveAt = now,
                source = "api_server",
                agentName = profile?.name,
                agentGlyph = profile?.glyph,
            ),
        )
        sendMessage(id, firstMessage)
        return id
    }

    suspend fun sendMessage(threadId: String, text: String, viaButton: Boolean = false) {
        val thread = threadDao.getThread(threadId) ?: return
        val now = clock()
        val turnId = newId()
        turnDao.upsertTurn(
            TurnEntity(
                id = turnId,
                threadId = threadId,
                seq = (turnDao.maxSeq(threadId) ?: 0) + 1,
                kind = "user",
                createdAt = now,
                markdown = text,
                blocksJson = null,
                traceJson = null,
                runId = null,
                sendState = "sending",
                viaButton = viaButton,
            ),
        )
        threadDao.upsertThread(
            thread.copy(state = "running", lastActiveAt = now, preview = text.snippet(120)),
        )
        launchRun(threadId, turnId)
    }

    /** Retry never happens automatically — only on this explicit call (spec 04). */
    suspend fun retryTurn(turnId: String) {
        val turn = turnDao.getTurn(turnId) ?: return
        if (turn.kind != "user") return
        turnDao.upsertTurn(turn.copy(sendState = "sending", runId = null))
        threadDao.getThread(turn.threadId)?.let {
            threadDao.upsertThread(it.copy(state = "running", lastActiveAt = clock()))
        }
        launchRun(turn.threadId, turnId)
    }

    suspend fun discardTurn(turnId: String) {
        val turn = turnDao.getTurn(turnId) ?: return
        turnDao.deleteTurn(turnId)
        threadDao.getThread(turn.threadId)?.let { thread ->
            val lastTurn = turnDao.getTurns(turn.threadId).lastOrNull()
            threadDao.upsertThread(
                thread.copy(
                    state = "idle",
                    preview = lastTurn?.markdown?.snippet(120) ?: "",
                ),
            )
        }
    }

    suspend fun setPinned(threadId: String, pinned: Boolean) {
        threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(pinned = pinned)) }
    }

    suspend fun markRead(threadId: String) {
        threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(unread = false)) }
    }

    suspend fun renameThread(threadId: String, title: String) {
        threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(title = title)) }
    }

    suspend fun deleteThread(threadId: String) {
        activeRuns.remove(threadId)?.cancel()
        clearStreaming(threadId)
        threadDao.deleteThread(threadId)
    }

    suspend fun clearAll() {
        activeRuns.values.forEach(Job::cancel)
        activeRuns.clear()
        _streaming.value = emptyMap()
        threadDao.clearAllThreads()
    }

    /** Local store → share-sheet JSON; the app is the only place this data exists (spec 03). */
    suspend fun exportJson(): String {
        val threadRows = buildJsonArray {
            for (t in allThreads()) {
                add(
                    buildJsonObject {
                        put("id", t.id); put("title", t.title); put("preview", t.preview)
                        put("state", t.state); put("pinned", t.pinned); put("unread", t.unread)
                        put("createdAt", t.createdAt); put("lastActiveAt", t.lastActiveAt)
                        put("source", t.source)
                        t.agentName?.let { put("agentName", it) }
                        t.agentGlyph?.let { put("agentGlyph", it) }
                        put(
                            "turns",
                            buildJsonArray {
                                for (turn in turnDao.getTurns(t.id)) {
                                    add(
                                        buildJsonObject {
                                            put("id", turn.id); put("seq", turn.seq); put("kind", turn.kind)
                                            put("createdAt", turn.createdAt)
                                            turn.markdown?.let { put("markdown", it) }
                                            turn.traceJson?.let { put("trace", Json.parseToJsonElement(it)) }
                                            turn.runId?.let { put("runId", it) }
                                            put("sendState", turn.sendState)
                                            put("viaButton", turn.viaButton)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject {
            put("exported_at", clock())
            put("threads", threadRows)
        }.toString()
    }

    private suspend fun allThreads(): List<ThreadEntity> = threadDao.observeThreads().first()

    /**
     * On thread open / process restart: a thread stuck `running` with a synced
     * user turn carrying a runId is recovered via poll + stream replay (spec 02).
     */
    suspend fun recoverIfRunning(threadId: String) {
        if (activeRuns[threadId]?.isActive == true) return
        val thread = threadDao.getThread(threadId) ?: return
        if (thread.state != "running") return
        val userTurn = turnDao.getTurns(threadId).lastOrNull { it.kind == "user" }
        if (userTurn == null) {
            threadDao.upsertThread(thread.copy(state = "idle"))
            return
        }
        val runId = userTurn.runId
        if (runId == null) {
            failTurn(threadId, userTurn.id)
            return
        }
        activeRuns[threadId] = scope.launch {
            streamLoop(threadId, runId, userTurn.id, openStreamFirst = false)
        }
    }

    // ---- run lifecycle -----------------------------------------------------

    private suspend fun launchRun(threadId: String, userTurnId: String) {
        activeRuns[threadId]?.cancel()
        activeRuns[threadId] = scope.launch { runTurn(threadId, userTurnId) }
    }

    private suspend fun runTurn(threadId: String, userTurnId: String) {
        threadDao.getThread(threadId) ?: return
        val input = buildInput(threadId)
        when (val result = client.startRun(input, threadId)) {
            is ApiResult.Err -> {
                if (result.error is ApiError.Auth) onAuthFailure((result.error as ApiError.Auth).code)
                failTurn(threadId, userTurnId)
            }
            is ApiResult.Ok -> {
                val runId = result.value.runId
                turnDao.getTurn(userTurnId)?.let {
                    turnDao.upsertTurn(it.copy(sendState = "synced", runId = runId))
                }
                streamLoop(threadId, runId, userTurnId, openStreamFirst = true)
            }
        }
    }

    /**
     * Fold the event stream; on drop, poll `GET /v1/runs/{id}` as backstop and —
     * if still running — re-open the stream, RESETTING accumulation first
     * because the replay starts from the beginning (spec 02).
     */
    private suspend fun streamLoop(
        threadId: String,
        runId: String,
        userTurnId: String,
        openStreamFirst: Boolean,
    ) {
        val backoff = backoffFactory()
        var openStream = openStreamFirst
        try {
            while (true) {
                if (openStream) {
                    val events = mutableListOf<RunEvent>()
                    setStreaming(StreamingRun(threadId, runId, "", null, interrupted = false))
                    var completed: RunEvent.RunCompleted? = null
                    var streamError: ApiError? = null
                    try {
                        client.runEvents(runId).collect { event ->
                            events += event
                            if (event is RunEvent.RunCompleted) completed = event
                            setStreaming(streamingFrom(threadId, runId, events))
                        }
                    } catch (e: HermesStreamException) {
                        streamError = e.apiError
                    }
                    completed?.let {
                        finalize(threadId, runId, it.output, events)
                        return
                    }
                    if (streamError is ApiError.Auth) {
                        onAuthFailure((streamError as ApiError.Auth).code)
                        failTurn(threadId, userTurnId)
                        return
                    }
                    // dropped or ended without completion → poll backstop
                    markInterrupted(threadId)
                }
                openStream = true

                var pollFailures = 0
                var outcome: RunStatus? = null
                var polledOutput: String? = null
                while (outcome == null) {
                    when (val poll = client.getRun(runId)) {
                        is ApiResult.Ok -> {
                            outcome = poll.value.runStatus
                            polledOutput = poll.value.output
                        }
                        is ApiResult.Err -> {
                            val error = poll.error
                            if (error is ApiError.Auth) {
                                onAuthFailure(error.code)
                                failTurn(threadId, userTurnId)
                                return
                            }
                            if (++pollFailures >= MAX_POLL_FAILURES) {
                                failTurn(threadId, userTurnId)
                                return
                            }
                            delay(backoff.nextDelayMillis())
                        }
                    }
                }
                when (outcome) {
                    RunStatus.Completed -> {
                        finalize(threadId, runId, polledOutput.orEmpty(), emptyList())
                        return
                    }
                    RunStatus.Failed -> {
                        failTurn(threadId, userTurnId)
                        return
                    }
                    else -> {
                        // still running: back off, then re-open the stream (replays)
                        delay(backoff.nextDelayMillis())
                    }
                }
            }
        } finally {
            clearStreaming(threadId)
        }
    }

    private suspend fun finalize(
        threadId: String,
        runId: String,
        output: String,
        events: List<RunEvent>,
    ) {
        val now = clock()
        var seq = turnDao.maxSeq(threadId) ?: 0
        composeTrace(events)?.let { trace ->
            turnDao.upsertTurn(
                TurnEntity(
                    id = newId(), threadId = threadId, seq = ++seq, kind = "trace",
                    createdAt = now, markdown = null, blocksJson = null,
                    traceJson = encodeTrace(trace), runId = runId,
                    sendState = "synced", viaButton = false,
                ),
            )
        }
        turnDao.upsertTurn(
            TurnEntity(
                id = newId(), threadId = threadId, seq = ++seq, kind = "agent",
                createdAt = now, markdown = output, blocksJson = null,
                traceJson = null, runId = runId, sendState = "synced", viaButton = false,
            ),
        )
        threadDao.getThread(threadId)?.let {
            threadDao.upsertThread(
                it.copy(
                    state = "idle",
                    preview = output.snippet(120),
                    lastActiveAt = now,
                    unread = visibleThreadId != threadId,
                ),
            )
        }
        clearStreaming(threadId)
    }

    private suspend fun failTurn(threadId: String, userTurnId: String) {
        turnDao.getTurn(userTurnId)?.let { turnDao.upsertTurn(it.copy(sendState = "failed")) }
        threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(state = "failed")) }
        clearStreaming(threadId)
    }

    /** Turns → `input` messages: traces excluded, failed sends excluded (spec 03). */
    private suspend fun buildInput(threadId: String): List<WireMessage> =
        turnDao.getTurns(threadId).mapNotNull { turn ->
            val content = turn.markdown ?: return@mapNotNull null
            when {
                turn.kind == "user" && turn.sendState != "failed" -> WireMessage("user", content)
                turn.kind == "agent" -> WireMessage("assistant", content)
                else -> null
            }
        }

    private fun streamingFrom(threadId: String, runId: String, events: List<RunEvent>): StreamingRun =
        StreamingRun(
            threadId = threadId,
            runId = runId,
            text = buildString {
                for (event in events) if (event is RunEvent.MessageDelta) append(event.delta)
            },
            trace = composeTrace(events),
            interrupted = false,
        )

    private fun setStreaming(run: StreamingRun) {
        _streaming.update { it + (run.threadId to run) }
    }

    private fun markInterrupted(threadId: String) {
        _streaming.update { map ->
            map[threadId]?.let { map + (threadId to it.copy(interrupted = true)) } ?: map
        }
    }

    private fun clearStreaming(threadId: String) {
        _streaming.update { it - threadId }
    }

    private fun String.snippet(max: Int): String {
        val firstLine = lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.length <= max) firstLine else firstLine.take(max - 1) + "…"
    }

    private companion object {
        const val MAX_POLL_FAILURES = 5
    }
}
