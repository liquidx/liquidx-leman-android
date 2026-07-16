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
import net.liquidx.leman.data.remote.HermesStreamException
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.testutil.FakeHermesClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        ).setQueryCoroutineContext(dispatcher).build()
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

    private fun completedScript(output: String) = listOf<Any>(
        RunEvent.MessageDelta(output.take(2), 1.0),
        RunEvent.MessageDelta(output.drop(2), 1.1),
        RunEvent.RunCompleted(output, null, 2.0),
    )

    @Test
    fun createThread_derivesTitle_insertsThread_andStartsRun() = runTest {
        client.eventScripts.add(completedScript("hello there"))
        val repo = repo()
        val threadId = repo.createThread("fix the flaky ci pipeline please")
        advanceUntilIdle()
        val thread = repo.observeThreads().first().single()
        assertEquals(threadId, thread.id)
        assertEquals("fix the flaky ci pipeline please", thread.title)
        assertEquals(ThreadState.Idle, thread.state)
        assertEquals(1, client.startRunCalls.size)
    }

    @Test
    fun sendMessage_run202_userTurnSyncedWithRunId() = runTest {
        client.eventScripts.add(completedScript("ok"))
        val repo = repo()
        val threadId = repo.createThread("first message")
        advanceUntilIdle()
        val userTurn = repo.observeTurns(threadId).first().first { it.kind == TurnKind.User }
        assertEquals(SendState.Synced, userTurn.sendState)
        assertEquals("run_1", userTurn.runId)
    }

    @Test
    fun sendMessage_buildsInputFromHistory_excludingTraces_newMessageLast() = runTest {
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.Reasoning("thinking", 0.5),
                RunEvent.ToolStarted("ci.logs", "q", 1.0),
                RunEvent.ToolCompleted("ci.logs", 2.0, false, 3.0),
                RunEvent.RunCompleted("first answer", null, 4.0),
            ),
        )
        val repo = repo()
        val threadId = repo.createThread("first message")
        advanceUntilIdle()
        // history now: user + trace + agent
        client.eventScripts.add(completedScript("second answer"))
        repo.sendMessage(threadId, "follow up")
        advanceUntilIdle()

        val input = client.startRunCalls[1].first
        assertEquals(listOf("user", "assistant", "user"), input.map { it.role })
        assertEquals("first message", input[0].content)
        assertEquals("first answer", input[1].content)
        assertEquals("follow up", input[2].content)
    }

    @Test
    fun runCompleted_persistsTraceThenAgentTurn_updatesThread() = runTest {
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.Reasoning("diagnosing", 0.5),
                RunEvent.ToolStarted("ci.logs", "fetch runs", 1.0),
                RunEvent.ToolCompleted("ci.logs", 5.9, false, 2.0),
                RunEvent.MessageDelta("the fix", 3.0),
                RunEvent.RunCompleted("the fix is in", null, 4.0),
            ),
        )
        val repo = repo()
        val threadId = repo.createThread("fix ci")
        advanceUntilIdle()

        val turns = repo.observeTurns(threadId).first()
        assertEquals(listOf(TurnKind.User, TurnKind.Trace, TurnKind.Agent), turns.map { it.kind })
        val trace = turns[1].trace
        assertNotNull(trace)
        assertEquals(2, trace!!.steps.size)
        assertEquals("the fix is in", turns[2].markdown)
        assertEquals("run_1", turns[2].runId)

        val thread = repo.observeThreads().first().single()
        assertEquals(ThreadState.Idle, thread.state)
        assertEquals("the fix is in", thread.preview)
    }

    @Test
    fun runCompleted_offScreen_setsUnread_visibleDoesNot() = runTest {
        client.eventScripts.add(completedScript("answer"))
        val repo = repo()
        val threadId = repo.createThread("q")
        advanceUntilIdle()
        assertTrue(repo.observeThreads().first().single().unread)

        client.eventScripts.add(completedScript("answer2"))
        repo.setVisibleThread(threadId)
        repo.sendMessage(threadId, "again")
        advanceUntilIdle()
        assertEquals(false, repo.observeThreads().first().single().unread)
    }

    @Test
    fun postFailure_userTurnFailed_threadFailed_noAutoRetry() = runTest {
        client.startRunResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val repo = repo()
        val threadId = repo.createThread("will fail")
        advanceUntilIdle()

        val turn = repo.observeTurns(threadId).first().single()
        assertEquals(SendState.Failed, turn.sendState)
        assertEquals(ThreadState.Failed, repo.observeThreads().first().single().state)
        assertEquals(1, client.startRunCalls.size) // user actions never auto-retry
    }

    @Test
    fun streamDies_deltasNeverTouchedDb_pollShowsFailed_turnFails() = runTest {
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.MessageDelta("partial ", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        client.getRunResults.add(ApiResult.Ok(client.runDto("failed")))
        val repo = repo()
        val threadId = repo.createThread("q")
        advanceUntilIdle()

        val turns = repo.observeTurns(threadId).first()
        assertEquals(1, turns.size) // no agent/trace rows: deltas never hit the DB
        assertEquals(SendState.Failed, turns.single().sendState)
        assertEquals(ThreadState.Failed, repo.observeThreads().first().single().state)
    }

    @Test
    fun streamDies_pollShowsCompleted_outputPersistedFromPoll() = runTest {
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.MessageDelta("part", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        client.getRunResults.add(ApiResult.Ok(client.runDto("completed", output = "full answer")))
        val repo = repo()
        val threadId = repo.createThread("q")
        advanceUntilIdle()

        val agent = repo.observeTurns(threadId).first().single { it.kind == TurnKind.Agent }
        assertEquals("full answer", agent.markdown)
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }

    @Test
    fun streamDies_stillRunning_reopensAndResetsAccumulation_noDoubleText() = runTest {
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.MessageDelta("AB", 1.0),
                HermesStreamException(ApiError.Network(IOException("drop"))),
            ),
        )
        // replay from the beginning on re-open (verified gateway behavior)
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.MessageDelta("AB", 1.0),
                RunEvent.MessageDelta("C", 1.5),
                RunEvent.RunCompleted("ABC", null, 2.0),
            ),
        )
        client.getRunResults.add(ApiResult.Ok(client.runDto("running")))
        val repo = repo()
        val threadId = repo.createThread("q")
        advanceUntilIdle()

        assertEquals(2, client.streamOpens)
        val agent = repo.observeTurns(threadId).first().single { it.kind == TurnKind.Agent }
        assertEquals("ABC", agent.markdown)
    }

    @Test
    fun retryTurn_reSendsSameMessage_asNewRun() = runTest {
        client.startRunResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val repo = repo()
        val threadId = repo.createThread("retry me")
        advanceUntilIdle()

        client.startRunResult = ApiResult.Ok(net.liquidx.leman.data.remote.RunAcceptedDto("run_2", "started"))
        client.eventScripts.add(completedScript("recovered"))
        val failedTurn = repo.observeTurns(threadId).first().single()
        repo.retryTurn(failedTurn.id)
        advanceUntilIdle()

        assertEquals(2, client.startRunCalls.size)
        assertEquals("retry me", client.startRunCalls[1].first.last().content)
        val turns = repo.observeTurns(threadId).first()
        assertEquals(SendState.Synced, turns.first { it.kind == TurnKind.User }.sendState)
        assertEquals("recovered", turns.single { it.kind == TurnKind.Agent }.markdown)
    }

    @Test
    fun discardTurn_removesFailedTurn_threadBackToIdle() = runTest {
        client.startRunResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val repo = repo()
        val threadId = repo.createThread("discard me")
        advanceUntilIdle()

        val failedTurn = repo.observeTurns(threadId).first().single()
        repo.discardTurn(failedTurn.id)
        advanceUntilIdle()
        assertEquals(0, repo.observeTurns(threadId).first().size)
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }

    @Test
    fun authFailureOnStart_notifiesCallback() = runTest {
        client.startRunResult = ApiResult.Err(ApiError.Auth(401))
        val repo = repo()
        repo.createThread("q")
        advanceUntilIdle()
        assertEquals(1, authFailures)
    }

    @Test
    fun streaming_stateExposesAccumulatingTextAndLiveTrace() = runTest {
        // Stream that never completes: text stays visible in streaming state.
        client.eventScripts.add(
            listOf<Any>(
                RunEvent.MessageDelta("live ", 1.0),
                RunEvent.ToolStarted("web_search", "q", 1.5),
                RunEvent.MessageDelta("text", 2.0),
            ),
        )
        client.getRunResults.add(ApiResult.Ok(client.runDto("failed"))) // terminal for the test
        val repo = repo()
        val threadId = repo.createThread("q")

        var sawStreaming = false
        val collectScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        val job = collectScope.launch {
            repo.streaming.collect { map ->
                map[threadId]?.let {
                    if (it.text == "live text" && it.trace?.steps?.size == 1) sawStreaming = true
                }
            }
        }
        advanceUntilIdle()
        job.cancel()
        assertTrue(sawStreaming)
    }

    @Test
    fun pinsReadsDeletes_areLocalOnly() = runTest {
        client.eventScripts.add(completedScript("a"))
        val repo = repo()
        val threadId = repo.createThread("local ops")
        advanceUntilIdle()

        repo.setPinned(threadId, true)
        assertTrue(repo.observeThreads().first().single().pinned)
        repo.markRead(threadId)
        assertEquals(false, repo.observeThreads().first().single().unread)
        repo.deleteThread(threadId)
        assertEquals(0, repo.observeThreads().first().size)
    }

    @Test
    fun clearAll_wipesEverything() = runTest {
        client.eventScripts.add(completedScript("a"))
        val repo = repo()
        repo.createThread("one")
        advanceUntilIdle()
        repo.clearAll()
        assertEquals(0, repo.observeThreads().first().size)
    }

    @Test
    fun exportJson_containsThreadsAndTurns() = runTest {
        client.eventScripts.add(completedScript("answer text"))
        val repo = repo()
        repo.createThread("export me")
        advanceUntilIdle()
        val json = repo.exportJson()
        assertTrue(json.contains("export me"))
        assertTrue(json.contains("answer text"))
    }

    @Test
    fun recoverIfRunning_completedWhileGone_persistsFromPoll() = runTest {
        // Simulate a thread stuck in running with a synced user turn carrying a runId.
        client.startRunResult = ApiResult.Ok(net.liquidx.leman.data.remote.RunAcceptedDto("run_9", "started"))
        client.eventScripts.add(
            listOf<Any>(HermesStreamException(ApiError.Network(IOException("app killed")))),
        )
        client.getRunResults.add(ApiResult.Ok(client.runDto("failed"))) // first life: fails
        val repo = repo()
        val threadId = repo.createThread("interrupted")
        advanceUntilIdle()

        // Second life: the run actually finished server-side.
        client.getRunResults.clear()
        client.getRunResults.add(ApiResult.Ok(client.runDto("completed", output = "done while away")))
        // Reset thread to running as if we died mid-run.
        val failed = repo.observeTurns(threadId).first().single()
        db.turnDao().upsertTurn(
            db.turnDao().getTurn(failed.id)!!.copy(sendState = "synced", runId = "run_9"),
        )
        db.threadDao().upsertThread(db.threadDao().getThread(threadId)!!.copy(state = "running"))

        repo.recoverIfRunning(threadId)
        advanceUntilIdle()
        val agent = repo.observeTurns(threadId).first().single { it.kind == TurnKind.Agent }
        assertEquals("done while away", agent.markdown)
        assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
    }

    @Test
    fun startRun_alwaysSendsThreadIdAsSessionId() = runTest {
        client.eventScripts.add(completedScript("a"))
        val repo = repo()
        val threadId = repo.createThread("s")
        advanceUntilIdle()
        assertEquals(threadId, client.startRunCalls[0].second) // thread id IS the session id (spec 03)

        client.eventScripts.add(completedScript("b"))
        repo.sendMessage(threadId, "next")
        advanceUntilIdle()
        assertEquals(threadId, client.startRunCalls[1].second)
    }
}
