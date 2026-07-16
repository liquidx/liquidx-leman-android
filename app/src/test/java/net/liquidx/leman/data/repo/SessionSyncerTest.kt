package net.liquidx.leman.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.data.remote.SessionListDto
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
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
class SessionSyncerTest {

    private lateinit var db: LemanDatabase
    private lateinit var client: FakeHermesClient

    @Before
    fun setUp() {
        client = FakeHermesClient()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * Room's query context runs on the test scheduler (same harness shape as
     * ThreadRepositoryTest.repo()) so suspend DAO calls resolve in virtual time.
     */
    private fun TestScope.database(): LemanDatabase {
        if (!::db.isInitialized) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                LemanDatabase::class.java,
            )
                // withTransaction's blocking beginTransaction() asserts off-main-thread;
                // the test scheduler runs on the main thread, so relax that here.
                .allowMainThreadQueries()
                .setQueryCoroutineContext(dispatcher).build()
        }
        return db
    }

    private fun session(
        id: String,
        lastActive: Double,
        source: String = "api_server",
        title: String? = null,
        preview: String? = null,
        count: Int = 2,
    ) = SessionDto(
        id = id, source = source, title = title, preview = preview,
        startedAt = lastActive - 10, lastActive = lastActive, messageCount = count,
    )

    private fun TestScope.syncer(active: Set<String> = emptySet(), visible: String? = null) =
        SessionSyncer(database(), client, isRunActive = { it in active }, visibleThreadId = { visible })

    private suspend fun TestScope.seedThread(
        id: String,
        lastActiveAt: Long,
        pinned: Boolean = false,
        agentName: String? = null,
        agentGlyph: String? = null,
        serverLastActive: Long = 0,
        unread: Boolean = false,
    ) = database().threadDao().upsertThread(
        ThreadEntity(
            id = id, title = "local title", preview = "local preview", state = "idle",
            pinned = pinned, unread = unread, createdAt = lastActiveAt - 1_000,
            lastActiveAt = lastActiveAt, source = "api_server",
            agentName = agentName, agentGlyph = agentGlyph, serverLastActive = serverLastActive,
        ),
    )

    private suspend fun TestScope.seedTurn(threadId: String, id: String = "stale-1") =
        database().turnDao().upsertTurn(
            TurnEntity(
                id = id, threadId = threadId, seq = 1, kind = "user", createdAt = 50_000,
                markdown = "stale", blocksJson = null, traceJson = null, runId = null,
                sendState = "synced", viaButton = false,
            ),
        )

    @Test
    fun newForeignSession_createsThreadWithTurns_notUnread() = runTest {
        client.listSessionsResults.add(
            ApiResult.Ok(
                SessionListDto(
                    data = listOf(session("run_x", 200.0, source = "cron", title = "Digest")),
                    hasMore = false,
                ),
            ),
        )
        client.messagesBySession["run_x"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "prompt", timestamp = 190.0),
                SessionMessageDto(2, "assistant", "answer", timestamp = 195.0, finishReason = "stop"),
            ),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        val thread = db.threadDao().getThread("run_x")!!
        assertEquals("Digest", thread.title)
        assertEquals("cron", thread.source)
        assertEquals(200_000L, thread.lastActiveAt)
        assertFalse(thread.unread)
        assertEquals(listOf("user", "agent"), db.turnDao().getTurns("run_x").map { it.kind })
    }

    @Test
    fun changedSession_rebuildsTurns_marksUnreadUnlessVisible() = runTest {
        seedThread("run_x", lastActiveAt = 100_000)
        seedTurn("run_x")
        // Two list pages scripted up front: consume-first-repeat-last on the deque.
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 300.0)), hasMore = false)),
        )
        client.messagesBySession["run_x"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "question", timestamp = 190.0),
                SessionMessageDto(2, "assistant", "answer", timestamp = 195.0),
                SessionMessageDto(3, "user", "follow up", timestamp = 198.0),
            ),
        )

        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        var turns = db.turnDao().getTurns("run_x")
        assertEquals(3, turns.size) // rebuilt: the stale seeded turn is gone
        assertTrue(turns.none { it.id == "stale-1" })
        assertTrue(db.threadDao().getThread("run_x")!!.unread)

        // Session advances again while its thread is on screen: stays read.
        assertTrue(syncer(visible = "run_x").syncOnce() is ApiResult.Ok)
        val thread = db.threadDao().getThread("run_x")!!
        assertEquals(300_000L, thread.lastActiveAt)
        assertFalse(thread.unread)
        turns = db.turnDao().getTurns("run_x")
        assertEquals(3, turns.size)
    }

    @Test
    fun unchangedSession_skipsMessagesFetch() = runTest {
        // serverLastActive snapshot equals the server's current last_active → skip.
        seedThread("run_x", lastActiveAt = 200_000, serverLastActive = 200_000)
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        assertEquals(0, client.sessionMessagesCalls)
        assertNotNull(db.threadDao().getThread("run_x"))
    }

    @Test
    fun serverLastActiveMatches_skipsDespiteLocalClockSkew() = runTest {
        // The app bumped lastActiveAt off its own clock (way past the server value),
        // but the server's last_active is unchanged since the last sync. Keying on
        // serverLastActive (not lastActiveAt) means no rebuild and no messages fetch.
        seedThread("run_x", lastActiveAt = 999_999_999, serverLastActive = 200_000)
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        assertEquals(0, client.sessionMessagesCalls)
        val thread = db.threadDao().getThread("run_x")!!
        assertEquals(999_999_999L, thread.lastActiveAt) // untouched by the skipped tick
    }

    @Test
    fun appFinalizedThread_unchangedServer_notReMarkedUnread() = runTest {
        // A thread the app just finalized: read, lastActiveAt on the local clock, and
        // serverLastActive snapshotting the server's last_active. A tick with the same
        // server state must not rebuild it and must not spuriously flip it to unread.
        seedThread(
            "run_x", lastActiveAt = 500_000, serverLastActive = 200_000, unread = false,
        )
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        assertTrue(syncer(visible = null).syncOnce() is ApiResult.Ok)
        assertEquals(0, client.sessionMessagesCalls)
        assertFalse(db.threadDao().getThread("run_x")!!.unread)
    }

    @Test
    fun unsyncedTurn_survivesRebuild_withSendStateIntact() = runTest {
        seedThread("run_x", lastActiveAt = 100_000)
        // A user message that never reached the server (failed) sits alongside stale
        // synced content that the rebuild will replace.
        seedTurn("run_x", id = "stale-synced")
        database().turnDao().upsertTurn(
            TurnEntity(
                id = "failed-1", threadId = "run_x", seq = 9, kind = "user", createdAt = 60_000,
                markdown = "did not send", blocksJson = null, traceJson = null, runId = null,
                sendState = "failed", viaButton = false,
            ),
        )
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        client.messagesBySession["run_x"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "question", timestamp = 190.0),
                SessionMessageDto(2, "assistant", "answer", timestamp = 195.0),
            ),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        val turns = db.turnDao().getTurns("run_x")
        assertTrue(turns.none { it.id == "stale-synced" }) // synced stale row gone
        val failed = turns.last()
        assertEquals("failed-1", failed.id) // preserved, appended last
        assertEquals("did not send", failed.markdown)
        assertEquals("failed", failed.sendState)
        // seq continues past the rebuilt server turns.
        assertTrue(failed.seq > turns.filter { it.id != "failed-1" }.maxOf { it.seq })
    }

    @Test
    fun localSidecar_pinAndProfile_surviveRebuild() = runTest {
        seedThread("run_x", lastActiveAt = 100_000, pinned = true, agentName = "lem", agentGlyph = "◆")
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("run_x", 200.0)), hasMore = false)),
        )
        client.messagesBySession["run_x"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", "hi", timestamp = 190.0),
                SessionMessageDto(2, "assistant", "hello", timestamp = 195.0),
            ),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        val thread = db.threadDao().getThread("run_x")!!
        assertEquals(200_000L, thread.lastActiveAt) // the rebuild really happened
        assertTrue(thread.pinned)
        assertEquals("lem", thread.agentName)
        assertEquals("◆", thread.agentGlyph)
    }

    @Test
    fun sessionGoneFromServer_deletesLocalThread_unlessRunActive() = runTest {
        seedThread("kept_server", lastActiveAt = 200_000)
        seedThread("active_local", lastActiveAt = 100_000)
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("kept_server", 200.0)), hasMore = false)),
        )

        assertTrue(syncer(active = setOf("active_local")).syncOnce() is ApiResult.Ok)
        assertNotNull(db.threadDao().getThread("kept_server"))
        assertNotNull(db.threadDao().getThread("active_local")) // in-flight run: spared

        // Same server state, run finished: now it reconciles away.
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        assertNull(db.threadDao().getThread("active_local"))
        assertNotNull(db.threadDao().getThread("kept_server"))
    }

    @Test
    fun pagination_followsHasMore() = runTest {
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("s1", 100.0)), hasMore = true)),
        )
        client.listSessionsResults.add(
            ApiResult.Ok(SessionListDto(data = listOf(session("s2", 200.0)), hasMore = false)),
        )
        client.messagesBySession["s1"] = ApiResult.Ok(
            listOf(SessionMessageDto(1, "user", "one", timestamp = 90.0)),
        )
        client.messagesBySession["s2"] = ApiResult.Ok(
            listOf(SessionMessageDto(1, "user", "two", timestamp = 190.0)),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        assertEquals(2, client.listSessionsCalls)
        assertNotNull(db.threadDao().getThread("s1"))
        assertNotNull(db.threadDao().getThread("s2"))
    }

    @Test
    fun titleFallback_serverNull_usesFirstUserMessage_thenPreview() = runTest {
        val longAsk =
            "fix the pipeline because the deploy job keeps flaking on arm64 runners and blocking every release"
        client.listSessionsResults.add(
            ApiResult.Ok(
                SessionListDto(
                    data = listOf(
                        session("s_user", 100.0, title = null),
                        session("s_preview", 200.0, title = null, preview = "cron digest preview"),
                    ),
                    hasMore = false,
                ),
            ),
        )
        client.messagesBySession["s_user"] = ApiResult.Ok(
            listOf(
                SessionMessageDto(1, "user", longAsk, timestamp = 90.0),
                SessionMessageDto(2, "assistant", "done", timestamp = 95.0),
            ),
        )
        client.messagesBySession["s_preview"] = ApiResult.Ok(
            listOf(SessionMessageDto(1, "assistant", "unprompted output", timestamp = 190.0)),
        )
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        assertEquals(longAsk.snippet(80), db.threadDao().getThread("s_user")!!.title)
        assertTrue(db.threadDao().getThread("s_user")!!.title.endsWith("…"))
        // No user message at all: server preview is the last resort.
        assertEquals("cron digest preview", db.threadDao().getThread("s_preview")!!.title)
    }

    @Test
    fun listFailure_returnsErr_touchesNothing() = runTest {
        seedThread("run_x", lastActiveAt = 100_000)
        client.listSessionsResults.add(ApiResult.Err(ApiError.Network(IOException("down"))))
        assertTrue(syncer().syncOnce() is ApiResult.Err)
        assertNotNull(db.threadDao().getThread("run_x"))
        assertEquals(0, client.sessionMessagesCalls)
    }
}
