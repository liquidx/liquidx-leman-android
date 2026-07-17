package net.liquidx.leman

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.debug.FakeHermesServer
import net.liquidx.leman.debug.SampleCorpus
import net.liquidx.leman.di.AppContainer
import net.liquidx.leman.ui.nav.LemanNavHost
import net.liquidx.leman.ui.theme.LemanTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class InMemoryKeyStore(private var key: String? = null) : ApiKeyStore {
    override suspend fun get(): String? = key
    override suspend fun set(value: String) {
        key = value
    }
    override suspend fun clear() {
        key = null
    }
}

/**
 * The merge gate for streaming changes (spec 07): app wired to the in-process
 * fake gateway; exercises client, repository, store, and UI together.
 */
class LemanE2eTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var fake: FakeHermesServer

    // Owns the injected SettingsStore's DataStore; cancelled in tearDown so the
    // file registration is released before the next test builds its own store.
    private val testSettingsScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun launch(
        withApiKey: Boolean = true,
        seedExtra: suspend (LemanDatabase) -> Unit = {},
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fake = FakeHermesServer()
        val db = Room.inMemoryDatabaseBuilder(context, LemanDatabase::class.java).build()
        container = AppContainer(
            context,
            AppContainer.Overrides(
                hermesClient = fake,
                db = db,
                apiKeyStore = InMemoryKeyStore(if (withApiKey) "test-key" else null),
                // Isolate settings per test: this runs in the live app process next to
                // LemanApp's own container, and DataStore forbids two instances active on
                // one file. A unique temp file per test keeps them from colliding.
                settings = SettingsStore(
                    scope = testSettingsScope,
                    produceFile = {
                        File(context.cacheDir, "e2e-settings-${UUID.randomUUID()}.preferences_pb")
                    },
                ),
            ),
        )
        runBlocking {
            SampleCorpus.seed(db)
            seedExtra(db)
        }
        compose.setContent {
            LemanTheme { LemanNavHost(container) }
        }
        container.connectionManager.reconfigure()
    }

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        // Release this test's DataStore + coroutines so the next test in the same
        // process starts clean (DataStore is a process-wide singleton per file).
        if (::container.isInitialized) container.appScope.cancel()
        testSettingsScope.cancel()
    }

    @Test
    fun coldStart_rendersSeededListFromLocalStore() {
        launch()
        compose.onNodeWithText("fix flaky ci pipeline").assertExists()
        compose.onNodeWithText("book train to geneva").assertExists()
    }

    @Test
    fun tapThreadRow_opensThreadWithItsLog() {
        launch()
        compose.onNodeWithText("fix flaky ci pipeline").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("message juno").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("the ci pipeline keeps flaking on main — roughly every third run fails in the test stage. find it and fix it.")
            .assertExists()
    }

    @Test
    fun pinGlyph_togglesWithoutOpeningThread() {
        launch()
        compose.onAllNodesWithContentDescription("pin")[0].performClick()
        // still on the list (filter row present), and something is now unpinnable
        compose.onNodeWithText("filter threads").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("unpin").fetchSemanticsNodes().size >= 3
        }
    }

    @Test
    fun traceLine_expandsToTable() {
        launch()
        compose.onNodeWithText("fix flaky ci pipeline").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("trace · 9 steps", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("trace · 9 steps", substring = true)[0].performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("reasoning").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun composer_disabledWhenUnconfigured() {
        launch(withApiKey = false)
        compose.onNodeWithText("fix flaky ci pipeline").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("message juno").fetchSemanticsNodes().isNotEmpty()
        }
        // a disabled BasicTextField exposes no SetText action
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    /**
     * Seeds a 40-turn thread (alternating user/agent, one short line each)
     * directly into the local store — long enough that "anchored at the first
     * unread turn", "at the top", and "at the bottom" are visibly distinct
     * viewport states. `lastReadAt` = createdAt of [readUpToTurn] (1-based),
     * so turn `readUpToTurn + 1` is the first unread.
     */
    private suspend fun seedLongThread(db: LemanDatabase, unread: Boolean, readUpToTurn: Int) {
        val now = System.currentTimeMillis()
        val base = now - 2 * 3_600_000L
        fun turnAt(i: Int) = base + i * 60_000L
        db.threadDao().upsertThread(
            ThreadEntity(
                id = "th-long", title = "long scroll thread", preview = "turn message 40",
                state = "idle", pinned = false, unread = unread,
                createdAt = base, lastActiveAt = now, source = "api_server",
                agentName = null, agentGlyph = null,
                lastReadAt = turnAt(readUpToTurn),
            ),
        )
        db.turnDao().upsertTurns(
            (1..40).map { i ->
                TurnEntity(
                    id = "th-long-turn-$i", threadId = "th-long", seq = i.toLong(),
                    kind = if (i % 2 == 1) "user" else "agent",
                    createdAt = turnAt(i),
                    markdown = "turn message %02d".format(i),
                    blocksJson = null, traceJson = null, runId = null,
                    sendState = "synced", viaButton = false,
                )
            },
        )
    }

    /**
     * Seeds a 2-turn thread whose last (agent) turn is far taller than the
     * viewport — the synced-cron-digest shape: few LazyColumn items, one huge
     * message. Index-based bottom detection considers the last item "visible"
     * from its very first line, so this shape is exactly where pixel-aware
     * detection matters (ux-fixes follow-up).
     */
    private suspend fun seedTallThread(db: LemanDatabase) {
        val now = System.currentTimeMillis()
        val base = now - 3_600_000L
        db.threadDao().upsertThread(
            ThreadEntity(
                id = "th-tall", title = "tall digest thread", preview = "digest",
                state = "idle", pinned = false, unread = false,
                createdAt = base, lastActiveAt = now, source = "cron",
                agentName = null, agentGlyph = null,
                lastReadAt = base + 2 * 60_000L,
            ),
        )
        db.turnDao().upsertTurns(
            listOf(
                TurnEntity(
                    id = "th-tall-turn-1", threadId = "th-tall", seq = 1, kind = "user",
                    createdAt = base + 60_000L, markdown = "run the digest",
                    blocksJson = null, traceJson = null, runId = null,
                    sendState = "synced", viaButton = false,
                ),
                TurnEntity(
                    id = "th-tall-turn-2", threadId = "th-tall", seq = 2, kind = "agent",
                    createdAt = base + 2 * 60_000L,
                    markdown = (1..60).joinToString("\n\n") { "digest item line %02d".format(it) },
                    blocksJson = null, traceJson = null, runId = null,
                    sendState = "synced", viaButton = false,
                ),
            ),
        )
    }

    @Test
    fun tallLastTurn_scrolledToTop_showsJumpToLatest_tapLandsAtTrueBottom() {
        launch(seedExtra = { db -> seedTallThread(db) })
        compose.onNodeWithText("tall digest thread").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("digest item line 01").fetchSemanticsNodes().isNotEmpty()
        }
        // Scroll to the very top: the tall last item is still index-"visible",
        // but its bottom edge is far below the fold — the control must show.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("run the digest").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("↓ latest").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap: lands at the true bottom (the digest's last lines on screen),
        // and the control retires — which, with pixel-aware detection, is
        // itself the assertion that the item's bottom edge is in the viewport.
        compose.onNodeWithText("↓ latest").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("digest item line 60").fetchSemanticsNodes().any {
                it.boundsInRoot.height > 0
            }
        }
        compose.onNodeWithText("digest item line 60").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("↓ latest").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun unreadThread_opensAnchoredAtFirstUnreadTurn() {
        launch(seedExtra = { db -> seedLongThread(db, unread = true, readUpToTurn = 15) })
        compose.onNodeWithText("long scroll thread").performClick()
        // The anchor scroll lands the first unread turn (16) at the top of the log.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("turn message 16").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("turn message 16").assertIsDisplayed()
        // Not at the top: already-read turns sit above the fold (uncomposed).
        assertTrue(compose.onAllNodesWithText("turn message 01").fetchSemanticsNodes().isEmpty())
        // Not at the bottom either: the newest turn is far below the fold.
        assertTrue(compose.onAllNodesWithText("turn message 40").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun scrolledUp_jumpToLatestAppears_tapReturnsToBottom() {
        launch(seedExtra = { db -> seedLongThread(db, unread = false, readUpToTurn = 40) })
        compose.onNodeWithText("long scroll thread").performClick()
        // A read thread opens at the bottom; no jump control while there.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("turn message 40").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("↓ latest").fetchSemanticsNodes().isEmpty())

        // Scroll away from the bottom: the control appears.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("↓ latest").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap it: the newest turn is back on screen and the control retires.
        compose.onNodeWithText("↓ latest").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("turn message 40").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("turn message 40").assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("↓ latest").fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * Sends through the sessions send path (`createSession` → `chat/stream`):
     * SampleCorpus-seeded threads aren't known sessions on the fake, so the
     * first message must go through "new thread", which mints a real fake
     * session id before sending (spec 03).
     */
    @Test
    fun e2e_sendMessage_streamsAndPersistsAgentTurnAndTrace() {
        launch()
        compose.onNodeWithText("new thread").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("book something nice")
        compose.onNodeWithText("start thread ⏎").performClick()

        // fake demo scenario: reasoning + tool events + delta + run.completed
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("anything else?", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("trace · ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
