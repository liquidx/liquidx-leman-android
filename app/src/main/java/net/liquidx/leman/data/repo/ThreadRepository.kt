package net.liquidx.leman.data.repo

import androidx.room.withTransaction
import java.util.UUID
import kotlin.coroutines.coroutineContext
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

    // Bridges the gap between createSession() returning and launchChat() registering
    // an activeRuns entry: a concurrent sync tick's stale listSessions snapshot can
    // lack the brand-new session and would otherwise delete the just-upserted local
    // row out from under sendMessage (spec §2 review, fix 2).
    private val protectedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var visibleThreadId: String? = null

    val syncer = SessionSyncer(
        db = db,
        client = client,
        isRunActive = { activeRuns[it]?.isActive == true || it in protectedThreads },
        visibleThreadId = { visibleThreadId },
    )

    suspend fun syncNow(): ApiResult<Unit> = syncer.syncOnce()

    fun observeThreads(): Flow<List<Thread>> =
        threadDao.observeThreads().map { list -> list.map { it.toDomain() } }

    fun observeTurns(threadId: String): Flow<List<Turn>> =
        turnDao.observeTurns(threadId).map { list -> list.map { it.toDomain() } }

    /** Point-in-time snapshot, e.g. to capture pre-markRead state on thread open. */
    suspend fun getThread(threadId: String): Thread? = threadDao.getThread(threadId)?.toDomain()

    /** Unread bookkeeping: a run completing on the visible thread is read (spec 03). */
    fun setVisibleThread(threadId: String?) {
        visibleThreadId = threadId
    }

    suspend fun createThread(firstMessage: String, profile: AgentProfile? = null): String? {
        val session = when (val result = client.createSession()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> {
                if (result.error is ApiError.Auth) onAuthFailure((result.error as ApiError.Auth).code)
                return null
            }
        }
        protectedThreads.add(session.id)
        try {
            val now = clock()
            threadDao.upsertThread(
                ThreadEntity(
                    id = session.id,
                    title = firstMessage.snippet(80),
                    preview = firstMessage.snippet(120),
                    state = "idle",
                    pinned = false,
                    unread = false,
                    createdAt = now,
                    lastActiveAt = now,
                    source = session.source,
                    agentName = profile?.name,
                    agentGlyph = profile?.glyph,
                ),
            )
            sendMessage(session.id, firstMessage)
            return session.id
        } finally {
            // By now sendMessage's launchChat has registered activeRuns[session.id],
            // so the syncer's isRunActive guard covers the thread from here on.
            protectedThreads.remove(session.id)
        }
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
        launchChat(threadId, turnId, text)
    }

    /**
     * dedup key: [TurnEntity.runId], not message content (spec §3).
     *
     * `runId` is only ever set from the `run.started` event in [chatTurn], which
     * only fires once Hermes has accepted *this exact* turn. So `runId != null`
     * means the server unambiguously has this message; `runId == null` means it
     * never got there and must be resent.
     *
     * Content equality against `sessionMessages(...)` was the old dedup key, but
     * it produces false positives whenever the user's new message text matches
     * an earlier message they sent (e.g. resending "ok" after a prior, already
     * answered "ok"): the retry would match the *old* server message, skip the
     * resend, and the new message would be silently dropped.
     */
    suspend fun retryTurn(turnId: String) {
        val turn = turnDao.getTurn(turnId) ?: return
        if (turn.kind != "user") return
        threadDao.getThread(turn.threadId)?.let {
            threadDao.upsertThread(it.copy(state = "running", lastActiveAt = clock()))
        }
        if (turn.runId != null) {
            turnDao.upsertTurn(turn.copy(sendState = "synced"))
            activeRuns[turn.threadId]?.cancel()
            activeRuns[turn.threadId] = scope.launch { recoverByPolling(turn.threadId, turnId) }
        } else {
            turnDao.upsertTurn(turn.copy(sendState = "sending"))
            launchChat(turn.threadId, turnId, turn.markdown.orEmpty())
        }
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

    /** Opening a thread reads it: unread clears and the read high-water mark advances
     * to the latest turn (or now, if the thread has no turns yet) — spec ux-fixes. */
    suspend fun markRead(threadId: String) {
        threadDao.getThread(threadId)?.let { thread ->
            val latestTurnAt = turnDao.getTurns(threadId).maxOfOrNull { it.createdAt } ?: clock()
            threadDao.upsertThread(thread.copy(unread = false, lastReadAt = latestTurnAt))
        }
    }

    /** Propagates to the server first; local state only changes on success (spec §4). */
    suspend fun renameThread(threadId: String, title: String): Boolean =
        when (val result = client.renameSession(threadId, title)) {
            is ApiResult.Ok -> {
                threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(title = title)) }
                true
            }
            is ApiResult.Err -> {
                if (result.error is ApiError.Auth) onAuthFailure((result.error as ApiError.Auth).code)
                false
            }
        }

    suspend fun deleteThread(threadId: String): Boolean {
        val result = client.deleteSession(threadId)
        val gone = result is ApiResult.Ok ||
            (result as? ApiResult.Err)?.error.let { it is ApiError.Client && it.code == 404 }
        if (gone) {
            activeRuns.remove(threadId)?.cancel()
            clearStreaming(threadId)
            threadDao.deleteThread(threadId)
        } else if ((result as ApiResult.Err).error is ApiError.Auth) {
            onAuthFailure((result.error as ApiError.Auth).code)
        }
        return gone
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

    /** On thread open / process restart: a thread stuck `running` recovers via message polling. */
    suspend fun recoverIfRunning(threadId: String) {
        if (activeRuns[threadId]?.isActive == true) return
        val thread = threadDao.getThread(threadId) ?: return
        if (thread.state != "running") return
        val userTurn = turnDao.getTurns(threadId).lastOrNull { it.kind == "user" }
        if (userTurn == null) {
            threadDao.upsertThread(thread.copy(state = "idle"))
            return
        }
        activeRuns[threadId] = scope.launch { recoverByPolling(threadId, userTurn.id) }
    }

    // ---- chat lifecycle ------------------------------------------------------

    private suspend fun launchChat(threadId: String, userTurnId: String, text: String) {
        activeRuns[threadId]?.cancel()
        activeRuns[threadId] = scope.launch { chatTurn(threadId, userTurnId, text) }
    }

    private suspend fun chatTurn(threadId: String, userTurnId: String, text: String) {
        val events = mutableListOf<RunEvent>()
        var runId = ""
        var completed: RunEvent.RunCompleted? = null
        var streamError: ApiError? = null
        setStreaming(StreamingRun(threadId, runId, "", null, interrupted = false))
        try {
            client.chatStream(threadId, text).collect { event ->
                when (event) {
                    is RunEvent.RunStarted -> {
                        runId = event.runId
                        turnDao.getTurn(userTurnId)?.let {
                            turnDao.upsertTurn(it.copy(sendState = "synced", runId = event.runId))
                        }
                    }
                    is RunEvent.RunCompleted -> completed = event
                    else -> Unit
                }
                events += event
                setStreaming(streamingFrom(threadId, runId, events))
            }
        } catch (e: HermesStreamException) {
            streamError = e.apiError
        }
        try {
            completed?.let {
                finalize(threadId, runId, it.output, events)
                // Retire our own run from the active set BEFORE syncing so the syncer
                // reconciles *this* thread now (temp-id turns → msg-id turns) while it's
                // likely still visible — otherwise isRunActive would skip it and the swap
                // would defer to an arbitrary later tick. Guard against clobbering a newer
                // job (e.g. a retry) launched onto this thread meanwhile.
                if (activeRuns[threadId] === coroutineContext[Job]) activeRuns.remove(threadId)
                syncNow() // reconcile ids + siblings right after our own run (spec §2)
                return
            }
            if (streamError is ApiError.Auth) {
                onAuthFailure((streamError as ApiError.Auth).code)
                failTurn(threadId, userTurnId)
                return
            }
            val started = turnDao.getTurn(userTurnId)?.sendState == "synced"
            if (started) recoverByPolling(threadId, userTurnId) else failTurn(threadId, userTurnId)
        } finally {
            clearStreaming(threadId)
        }
    }

    /**
     * A POST stream can't be re-attached, so a dropped chat recovers by polling
     * the session's message history until the assistant reply lands (spec §3).
     */
    private suspend fun recoverByPolling(threadId: String, userTurnId: String) {
        markInterrupted(threadId)
        val backoff = backoffFactory()
        var polls = 0
        while (polls < MAX_RECOVERY_POLLS) {
            polls++
            when (val result = client.sessionMessages(threadId)) {
                is ApiResult.Err -> {
                    val error = result.error
                    if (error is ApiError.Auth) {
                        onAuthFailure(error.code)
                        failTurn(threadId, userTurnId)
                        return
                    }
                    if (error is ApiError.Client && error.code == 404) {
                        // session deleted remotely — the thread is gone
                        threadDao.deleteThread(threadId)
                        clearStreaming(threadId)
                        return
                    }
                }
                is ApiResult.Ok -> {
                    val messages = result.value
                    val userTurn = turnDao.getTurn(userTurnId)
                    val userIndex = messages.indexOfLast {
                        it.role == "user" && it.content == userTurn?.markdown
                    }
                    val replied = userIndex >= 0 && messages.drop(userIndex + 1).any {
                        it.role == "assistant" && !it.content.isNullOrEmpty()
                    }
                    if (replied) {
                        val now = clock()
                        val rebuilt = sessionTurns(threadId, messages)
                        val newPreview = (
                            messages.lastOrNull { m -> !m.content.isNullOrEmpty() }?.content ?: ""
                            ).snippet(120)
                        // One transaction: observers never see the mid-rebuild empty state.
                        // Unsynced local turns (a still-failed/sending user message) survive
                        // the rebuild, appended past the rebuilt max seq with sendState intact.
                        // (Narrow window: if the server acked but this run's runId never
                        // persisted locally before the crash/drop, the preserved local copy
                        // and the now-rebuilt server copy can both surface as a visible
                        // duplicate — accepted trade-off.)
                        db.withTransaction {
                            val preserved =
                                turnDao.getTurns(threadId).filter { it.sendState != "synced" }
                            turnDao.deleteTurnsFor(threadId)
                            turnDao.upsertTurns(rebuilt)
                            if (preserved.isNotEmpty()) {
                                var seq = rebuilt.maxOfOrNull { it.seq } ?: 0L
                                turnDao.upsertTurns(preserved.map { it.copy(seq = ++seq) })
                            }
                            threadDao.getThread(threadId)?.let {
                                threadDao.upsertThread(
                                    it.copy(
                                        state = "idle",
                                        preview = newPreview,
                                        lastActiveAt = now,
                                        unread = visibleThreadId != threadId,
                                    ),
                                )
                            }
                        }
                        // Mirror chatTurn's post-finalize sync: freshen serverLastActive now
                        // (guarded so a newer job, e.g. a retry, isn't clobbered) so the next
                        // 30s tick doesn't see a stale change-key and re-mark this
                        // content-identical thread unread.
                        if (activeRuns[threadId] === coroutineContext[Job]) activeRuns.remove(threadId)
                        syncNow()
                        clearStreaming(threadId)
                        return
                    }
                }
            }
            delay(backoff.nextDelayMillis())
        }
        failTurn(threadId, userTurnId)
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

    private companion object {
        const val MAX_RECOVERY_POLLS = 20
    }
}
