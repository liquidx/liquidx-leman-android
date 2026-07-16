package net.liquidx.leman.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.remote.HermesStreamException
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.data.remote.SessionListDto
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.testutil.FakeHermesClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadRepositoryTest {

    private lateinit var db: LemanDatabase
    private lateinit var client: FakeHermesClient
    private var authFailures = 0
    private var now = 1_000_000L

    @Before
    fun setUp() {
        client = FakeHermesClient()
        authFailures = 0
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * The repo scope and Room's query context both run on the test scheduler so
     * that advanceUntilIdle() drives the whole run lifecycle in virtual time
     * (backgroundScope tasks are not driven by advanceUntilIdle; Room's default
     * executors are real threads).
     */
    private fun kotlinx.coroutines.test.TestScope.repo(): ThreadRepository {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LemanDatabase::class.java,
        )
            // withTransaction's blocking beginTransaction() asserts off-main-thread;
            // the test scheduler runs on the main thread, so relax that here.
            .allowMainThreadQueries()
            .setQueryCoroutineContext(dispatcher).build()
        var id = 0
        return ThreadRepository(
            db = db,
            client = client,
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            clock = { now },
            newId = { "id-${id++}" },
            backoffFactory = { Backoff(random = Random(1)) },
            onAuthFailure = { authFailures++ },
        )
    }

    private suspend fun seedThread(id: String, state: String, preview: String = "p") {
        db.threadDao().upsertThread(
            ThreadEntity(
                id = id, title = "t", preview = preview, state = state, pinned = false,
                unread = false, createdAt = now, lastActiveAt = now, source = "api_server",
                agentName = null, agentGlyph = null,
            ),
        )
    }

    private suspend fun seedUserTurn(
        turnId: String,
        threadId: String,
        markdown: String,
        sendState: String,
        runId: String? = null,
        seq: Long = 1,
    ) {
        db.turnDao().upsertTurn(
            TurnEntity(
                id = turnId, threadId = threadId, seq = seq, kind = "user", createdAt = now,
                markdown = markdown, blocksJson = null, traceJson = null, runId = runId,
                sendState = sendState, viaButton = false,
            ),
        )
    }

    @Test
    fun createThread_usesServerSessionId_andStreamsChat() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "api_9_z"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                RunEvent.MessageDelta("hello there", 2.0),
                RunEvent.RunCompleted("hello there", null, 3.0),
            ),
        )
        val repo = repo()
        val id = repo.createThread("fix the flaky ci pipeline please")
        advanceUntilIdle()
        assertEquals("api_9_z", id)
        assertEquals("api_9_z" to "fix the flaky ci pipeline please", client.chatCalls.single())
        val turns = repo.observeTurns("api_9_z").first()
        assertEquals(SendState.Synced, turns.first { it.kind == TurnKind.User }.sendState)
        assertEquals("r1", turns.first { it.kind == TurnKind.User }.runId)
        assertEquals("hello there", turns.first { it.kind == TurnKind.Agent }.markdown)
    }

    @Test
    fun createThread_derivesTitleAndPreview() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("hello there", null, 2.0)),
        )
        val repo = repo()
        val id = repo.createThread("fix the flaky ci pipeline please")
        advanceUntilIdle()
        val thread = repo.observeThreads().first().single()
        assertEquals(id, thread.id)
        assertEquals("fix the flaky ci pipeline please", thread.title)
        assertEquals(ThreadState.Idle, thread.state)
    }

    @Test
    fun createThread_sessionCreateFails_returnsNull_noThreadRow() = runTest {
        client.createSessionResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val repo = repo()
        assertNull(repo.createThread("hi"))
        assertTrue(repo.observeThreads().first().isEmpty())
    }

    @Test
    fun createThread_authFailureOnSessionCreate_notifiesCallback() = runTest {
        client.createSessionResult = ApiResult.Err(ApiError.Auth(401))
        val repo = repo()
        assertNull(repo.createThread("hi"))
        assertEquals(1, authFailures)
    }

    @Test
    fun sendMessage_secondTurn_syncsWithNewRunId() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        // Both scripts queued up front: FakeHermesClient only pops the head once a second
        // script is queued behind it, so interleaving an add between opens would replay
        // the first script twice instead of advancing to the second.
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("first answer", null, 2.0)),
        )
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r2", 1.0), RunEvent.RunCompleted("second answer", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("first message")
        advanceUntilIdle()

        repo.sendMessage("s1", "follow up")
        advanceUntilIdle()

        val userTurns = repo.observeTurns("s1").first().filter { it.kind == TurnKind.User }
        assertEquals(2, userTurns.size)
        assertEquals("r2", userTurns.last().runId)
        assertEquals(SendState.Synced, userTurns.last().sendState)
        assertEquals("s1" to "follow up", client.chatCalls[1])
    }

    @Test
    fun runCompleted_persistsTraceThenAgentTurn_updatesThread() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                RunEvent.Reasoning("diagnosing", 0.5),
                RunEvent.ToolStarted("ci.logs", "fetch runs", 1.0),
                RunEvent.ToolCompleted("ci.logs", 5.9, false, 2.0),
                RunEvent.MessageDelta("the fix", 3.0),
                RunEvent.RunCompleted("the fix is in", null, 4.0),
            ),
        )
        val repo = repo()
        repo.createThread("fix ci")
        advanceUntilIdle()

        val turns = repo.observeTurns("s1").first()
        assertEquals(listOf(TurnKind.User, TurnKind.Trace, TurnKind.Agent), turns.map { it.kind })
        val trace = turns[1].trace
        assertNotNull(trace)
        assertEquals(2, trace!!.steps.size)
        assertEquals("the fix is in", turns[2].markdown)
        assertEquals("r1", turns[2].runId)

        val thread = repo.observeThreads().first().single()
        assertEquals(ThreadState.Idle, thread.state)
        assertEquals("the fix is in", thread.preview)
    }

    @Test
    fun runCompleted_offScreen_setsUnread_visibleDoesNot() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("answer", null, 2.0)),
        )
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r2", 1.0), RunEvent.RunCompleted("answer2", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("q")
        advanceUntilIdle()
        assertTrue(repo.observeThreads().first().single().unread)

        repo.setVisibleThread("s1")
        repo.sendMessage("s1", "again")
        advanceUntilIdle()
        assertEquals(false, repo.observeThreads().first().single().unread)
    }

    @Test
    fun droppedStream_afterRunStarted_recoversByPollingMessages() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                RunEvent.MessageDelta("par", 2.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "hi", timestamp = 1.0),
                SessionMessageDto(2, "assistant", "partial answer done", timestamp = 5.0, finishReason = "stop"),
            ),
        )
        val repo = repo()
        repo.createThread("hi")
        advanceUntilIdle()
        val thread = repo.observeThreads().first().single()
        assertEquals(ThreadState.Idle, thread.state)
        assertEquals(
            "partial answer done",
            repo.observeTurns("s1").first().last { it.kind == TurnKind.Agent }.markdown,
        )
    }

    @Test
    fun droppedStream_beforeRunStarted_failsTurn() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(listOf(HermesStreamException(ApiError.Network(IOException("refused")))))
        client.messagesBySession["s1"] = ApiResult.Ok(emptyList())
        val repo = repo()
        repo.createThread("hi")
        advanceUntilIdle()
        assertEquals(ThreadState.Failed, repo.observeThreads().first().single().state)
        val turns = repo.observeTurns("s1").first()
        assertEquals(1, turns.size) // no agent/trace rows: deltas never hit the DB
        assertEquals(SendState.Failed, turns.single { it.kind == TurnKind.User }.sendState)
    }

    @Test
    fun recoverByPolling_exhaustsPolls_failsTurn() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(SessionMessageDto(1, "user", "hi", timestamp = 1.0)), // no assistant reply ever arrives
        )
        val repo = repo()
        repo.createThread("hi")
        advanceUntilIdle()

        assertEquals(ThreadState.Failed, repo.observeThreads().first().single().state)
        assertEquals(
            SendState.Failed,
            repo.observeTurns("s1").first().single { it.kind == TurnKind.User }.sendState,
        )
    }

    @Test
    fun recoverByPolling_sessionDeletedRemotely_removesLocalThread() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        // messagesBySession left unset -> sessionMessages() returns 404 (session gone)
        val repo = repo()
        repo.createThread("hi")
        advanceUntilIdle()

        assertTrue(repo.observeThreads().first().isEmpty())
    }

    @Test
    fun recoverByPolling_authFailure_notifiesAndFailsTurn() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        client.messagesBySession["s1"] = ApiResult.Err(ApiError.Auth(401))
        val repo = repo()
        repo.createThread("hi")
        advanceUntilIdle()

        assertEquals(1, authFailures)
        assertEquals(
            SendState.Failed,
            repo.observeTurns("s1").first().single { it.kind == TurnKind.User }.sendState,
        )
    }

    @Test
    fun retryTurn_hasRunId_pollsInsteadOfResending() = runTest {
        // runId is only ever persisted once the server ack'd this exact turn via
        // run.started (see chatTurn's RunStarted branch), so a failed turn that
        // already carries one must recover by polling rather than resend.
        val repo = repo()
        seedThread("s1", state = "failed")
        seedUserTurn("t1", "s1", "already sent", sendState = "failed", runId = "r1")
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "already sent", timestamp = 1.0),
                SessionMessageDto(2, "assistant", "already answered", timestamp = 2.0, finishReason = "stop"),
            ),
        )

        repo.retryTurn("t1")
        advanceUntilIdle()

        assertEquals(0, client.chatCalls.size) // no duplicate send
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
        // recoverByPolling rebuilds local turns from the polled session messages
        assertEquals(
            "already answered",
            repo.observeTurns("s1").first().last { it.kind == TurnKind.Agent }.markdown,
        )
    }

    @Test
    fun retryTurn_noRunId_resendsEvenWithDuplicateContentOnServer() = runTest {
        // Regression: an earlier identical "ok" was already sent and answered, but the
        // *new* "ok" failed before the server ever ack'd it (runId == null). Content-based
        // dedup would wrongly match the OLD server message and silently drop the retry;
        // the runId-based check must resend it instead.
        val repo = repo()
        seedThread("s1", state = "failed")
        seedUserTurn("t0", "s1", "ok", sendState = "synced", runId = "r0", seq = 1)
        seedUserTurn("t1", "s1", "ok", sendState = "failed", runId = null, seq = 2)
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "ok", timestamp = 1.0),
                SessionMessageDto(2, "assistant", "already answered", timestamp = 2.0, finishReason = "stop"),
            ),
        )
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r2", 1.0), RunEvent.RunCompleted("resent ok", null, 2.0)),
        )

        repo.retryTurn("t1")
        advanceUntilIdle()

        assertEquals(1, client.chatCalls.size)
        assertEquals(
            SendState.Synced,
            repo.observeTurns("s1").first().first { it.id == "t1" }.sendState,
        )
    }

    @Test
    fun retryTurn_notOnServer_resends() = runTest {
        val repo = repo()
        seedThread("s1", state = "failed")
        seedUserTurn("t1", "s1", "not yet sent", sendState = "failed")
        client.messagesBySession["s1"] = ApiResult.Ok(emptyList())
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r2", 1.0), RunEvent.RunCompleted("resent ok", null, 2.0)),
        )

        repo.retryTurn("t1")
        advanceUntilIdle()

        assertEquals(1, client.chatCalls.size)
        assertEquals(
            SendState.Synced,
            repo.observeTurns("s1").first().first { it.kind == TurnKind.User }.sendState,
        )
    }

    @Test
    fun discardTurn_removesFailedTurn_threadBackToIdle() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(listOf(HermesStreamException(ApiError.Network(IOException("down")))))
        client.messagesBySession["s1"] = ApiResult.Ok(emptyList())
        val repo = repo()
        repo.createThread("discard me")
        advanceUntilIdle()

        val failedTurn = repo.observeTurns("s1").first().single()
        repo.discardTurn(failedTurn.id)
        advanceUntilIdle()
        assertEquals(0, repo.observeTurns("s1").first().size)
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }

    @Test
    fun renameThread_patchesServer_localOnlyOnOk() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("ok", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("original title")
        advanceUntilIdle()

        val renamed = repo.renameThread("s1", "new title")
        assertTrue(renamed)
        assertEquals("s1" to "new title", client.renameCalls.single())
        assertEquals("new title", repo.observeThreads().first().single().title)

        client.renameSessionResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val failed = repo.renameThread("s1", "rejected title")
        assertFalse(failed)
        assertEquals("new title", repo.observeThreads().first().single().title) // untouched
    }

    @Test
    fun renameThread_authFailure_notifiesCallback() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("ok", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("original title")
        advanceUntilIdle()

        client.renameSessionResult = ApiResult.Err(ApiError.Auth(401))
        assertFalse(repo.renameThread("s1", "new title"))
        assertEquals(1, authFailures)
    }

    @Test
    fun deleteThread_deletesServerThenLocal_404TreatedAsGone() = runTest {
        val repo = repo()
        seedThread("s1", state = "idle")
        seedThread("s2", state = "idle")
        seedThread("s3", state = "idle")

        client.deleteSessionResult = ApiResult.Ok(Unit)
        assertTrue(repo.deleteThread("s1"))
        assertNull(repo.observeThreads().first().find { it.id == "s1" })

        client.deleteSessionResult = ApiResult.Err(ApiError.Client(404, "not found"))
        assertTrue(repo.deleteThread("s2"))
        assertNull(repo.observeThreads().first().find { it.id == "s2" })

        client.deleteSessionResult = ApiResult.Err(ApiError.Network(IOException("down")))
        assertFalse(repo.deleteThread("s3"))
        assertNotNull(repo.observeThreads().first().find { it.id == "s3" })
    }

    @Test
    fun authFailure_onChatStream_poisonsConnState_andFailsTurn() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(listOf(HermesStreamException(ApiError.Auth(401))))
        val repo = repo()
        repo.createThread("q")
        advanceUntilIdle()
        assertEquals(1, authFailures)
        assertEquals(
            SendState.Failed,
            repo.observeTurns("s1").first().single { it.kind == TurnKind.User }.sendState,
        )
    }

    @Test
    fun runCompletion_triggersSyncNow() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("done", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("q")
        advanceUntilIdle()
        assertTrue(client.listSessionsCalls >= 1)
    }

    @Test
    fun runCompletion_reconcilesOwnThreadToMessageIdRows_staysReadWhileVisible() = runTest {
        // The post-run syncNow() must reconcile *this* thread immediately (chatTurn
        // drops it from activeRuns first), swapping the run's temp-id turns for the
        // server's deterministic msg-id rows — and, because the thread is visible, it
        // must not be flipped to unread.
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("the answer", null, 2.0)),
        )
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "my question", timestamp = 10.0),
                SessionMessageDto(2, "assistant", "the answer", timestamp = 11.0, finishReason = "stop"),
            ),
        )
        client.listSessionsResults.add(
            ApiResult.Ok(
                SessionListDto(
                    data = listOf(SessionDto(id = "s1", startedAt = 9.0, lastActive = 11.0)),
                    hasMore = false,
                ),
            ),
        )
        val repo = repo()
        repo.setVisibleThread("s1")
        repo.createThread("my question")
        advanceUntilIdle()

        val turns = repo.observeTurns("s1").first()
        assertTrue(turns.isNotEmpty())
        // Reconciled to server message ids, not the run's local temp ids.
        assertTrue(turns.all { it.id.startsWith("msg-s1-") || it.id.startsWith("trace-s1-") })
        assertEquals("the answer", turns.last { it.kind == TurnKind.Agent }.markdown)
        assertFalse(repo.observeThreads().first().single().unread)
    }

    @Test
    fun streaming_stateExposesAccumulatingTextAndLiveTrace() = runTest {
        // Stream that never completes: text stays visible in streaming state.
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(
                RunEvent.RunStarted("r1", 0.5),
                RunEvent.MessageDelta("live ", 1.0),
                RunEvent.ToolStarted("web_search", "q", 1.5),
                RunEvent.MessageDelta("text", 2.0),
            ),
        )
        val repo = repo()

        var sawStreaming = false
        val collectScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val job = collectScope.launch {
            repo.streaming.collect { map ->
                map["s1"]?.let {
                    if (it.text == "live text" && it.trace?.steps?.size == 1) sawStreaming = true
                }
            }
        }
        repo.createThread("q")
        advanceUntilIdle()
        job.cancel()
        assertTrue(sawStreaming)
    }

    @Test
    fun pinAndMarkRead_areLocalOnly() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("a", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("local ops")
        advanceUntilIdle()

        repo.setPinned("s1", true)
        assertTrue(repo.observeThreads().first().single().pinned)
        repo.markRead("s1")
        assertEquals(false, repo.observeThreads().first().single().unread)
        assertEquals(0, client.renameCalls.size)
        assertEquals(0, client.deleteCalls.size)
    }

    @Test
    fun clearAll_wipesEverything() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("a", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("one")
        advanceUntilIdle()
        repo.clearAll()
        assertEquals(0, repo.observeThreads().first().size)
    }

    @Test
    fun exportJson_containsThreadsAndTurns() = runTest {
        client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
        client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("answer text", null, 2.0)),
        )
        val repo = repo()
        repo.createThread("export me")
        advanceUntilIdle()
        val json = repo.exportJson()
        assertTrue(json.contains("export me"))
        assertTrue(json.contains("answer text"))
    }

    @Test
    fun recoverIfRunning_completedWhileGone_persistsFromPoll() = runTest {
        val repo = repo()
        // Seed a thread stuck "running" as if the process died mid-run.
        seedThread("s1", state = "running", preview = "interrupted")
        seedUserTurn("t1", "s1", "interrupted", sendState = "synced", runId = "r1")
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "interrupted", timestamp = 1.0),
                SessionMessageDto(2, "assistant", "done while away", timestamp = 2.0, finishReason = "stop"),
            ),
        )

        repo.recoverIfRunning("s1")
        advanceUntilIdle()
        val agent = repo.observeTurns("s1").first().single { it.kind == TurnKind.Agent }
        assertEquals("done while away", agent.markdown)
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }

    @Test
    fun recoverIfRunning_noUserTurn_goesIdle() = runTest {
        val repo = repo()
        seedThread("s1", state = "running")
        repo.recoverIfRunning("s1")
        advanceUntilIdle()
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }
}
