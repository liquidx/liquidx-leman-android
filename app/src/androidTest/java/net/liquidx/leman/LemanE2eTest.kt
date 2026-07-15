package net.liquidx.leman

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.debug.FakeHermesServer
import net.liquidx.leman.debug.SampleCorpus
import net.liquidx.leman.di.AppContainer
import net.liquidx.leman.ui.nav.LemanNavHost
import net.liquidx.leman.ui.theme.LemanTheme
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

    private fun launch(withApiKey: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fake = FakeHermesServer()
        val db = Room.inMemoryDatabaseBuilder(context, LemanDatabase::class.java).build()
        container = AppContainer(
            context,
            AppContainer.Overrides(
                hermesClient = fake,
                db = db,
                apiKeyStore = InMemoryKeyStore(if (withApiKey) "test-key" else null),
            ),
        )
        runBlocking { SampleCorpus.seed(db) }
        compose.setContent {
            LemanTheme { LemanNavHost(container) }
        }
        container.connectionManager.reconfigure()
    }

    @Before
    fun setUp() = Unit

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

    @Test
    fun e2e_sendMessage_streamsAndPersistsAgentTurnAndTrace() {
        launch()
        compose.onNodeWithText("plan lyon trip").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("message juno").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("book something nice")
        compose.onAllNodes(hasSetTextAction())[0].performImeAction()

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
