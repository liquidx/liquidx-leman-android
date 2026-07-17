package net.liquidx.leman.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.ZoneOffset
import net.liquidx.leman.data.remote.Fixtures
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.components.ConfigTab
import net.liquidx.leman.ui.components.ThreadsTab
import net.liquidx.leman.ui.config.ConfigScreen
import net.liquidx.leman.ui.config.ConfigUiState
import net.liquidx.leman.ui.config.TestConnectionState
import net.liquidx.leman.ui.format.TimeFormat
import net.liquidx.leman.ui.markdown.segmentBlocks
import net.liquidx.leman.ui.newthread.NewThreadScreen
import net.liquidx.leman.ui.newthread.NewThreadUiState
import net.liquidx.leman.ui.theme.LemanTheme
import net.liquidx.leman.ui.theme.lemanScreenBackground
import net.liquidx.leman.ui.thread.ThreadScreen
import net.liquidx.leman.ui.threads.ThreadsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel-fidelity regression net (spec 07): full screens at 380×788 with the
 * handoff sample data. Record: `./gradlew testDebugUnitTest -Proborazzi.test.record=true`
 * Verify: `-Proborazzi.test.verify=true` against `app/src/test/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w380dp-h788dp-320dpi")
class ScreenshotTests {

    @get:Rule
    val compose = createComposeRule()

    private val tabs = listOf(ThreadsTab, ConfigTab)

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LemanTheme {
                Box(Modifier.size(380.dp, 788.dp).lemanScreenBackground()) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // ---- 2a ---------------------------------------------------------------

    @Test
    fun threads_default() = snap("2a-threads-default") {
        ThreadsScreen(
            state = ScreenshotFixtures.threadsState(),
            clock = "09:41", tabs = tabs, onEvent = {},
            onOpenThread = {}, onNewThread = {}, onOpenConfig = {},
        )
    }

    @Test
    fun threads_filtered() = snap("2a-threads-filtered") {
        val full = ScreenshotFixtures.threadsState()
        ThreadsScreen(
            state = full.copy(
                filter = "ci",
                sections = full.sections.mapNotNull { section ->
                    val items = section.items.filter { it.title.contains("ci") }
                    if (items.isEmpty()) null else section.copy(items = items, count = items.size)
                },
            ),
            clock = "09:41", tabs = tabs, onEvent = {},
            onOpenThread = {}, onNewThread = {}, onOpenConfig = {},
        )
    }

    @Test
    fun threads_filterEmpty() = snap("2a-threads-empty") {
        ThreadsScreen(
            state = ScreenshotFixtures.threadsState().copy(filter = "quokka", sections = emptyList()),
            clock = "09:41", tabs = tabs, onEvent = {},
            onOpenThread = {}, onNewThread = {}, onOpenConfig = {},
        )
    }

    @Test
    fun threads_offline() = snap("2a-threads-offline") {
        ThreadsScreen(
            state = ScreenshotFixtures.threadsState(
                connState = ConnState.Offline(net.liquidx.leman.domain.model.ApiError.Timeout),
            ),
            clock = "09:41", tabs = tabs, onEvent = {},
            onOpenThread = {}, onNewThread = {}, onOpenConfig = {},
        )
    }

    // ---- 2b ---------------------------------------------------------------

    private val timestampOf = { turn: net.liquidx.leman.domain.model.Turn ->
        TimeFormat.clock(turn.createdAt, ZoneOffset.UTC)
    }

    @Test
    fun thread_tracesCollapsed() = snap("2b-thread-collapsed") {
        ThreadScreen(
            state = ScreenshotFixtures.threadState(),
            clock = "09:41", timestampOf = timestampOf, onEvent = {}, onBack = {},
        )
    }

    @Test
    fun thread_tracesExpanded() = snap("2b-thread-expanded") {
        val turns = ScreenshotFixtures.ciTurns()
        val traceIds = turns.filter { it.trace != null }.map { it.id }.toSet()
        ThreadScreen(
            state = ScreenshotFixtures.threadState(expanded = traceIds),
            clock = "09:41", timestampOf = timestampOf, onEvent = {}, onBack = {},
        )
    }

    @Test
    fun thread_streaming() = snap("2b-thread-streaming") {
        val turns = ScreenshotFixtures.ciTurns().take(1)
        ThreadScreen(
            state = ScreenshotFixtures.threadState(
                turns = turns,
                streaming = net.liquidx.leman.data.repo.StreamingRun(
                    threadId = "th-ci",
                    runId = "run-ci-live",
                    text = "found it. the flake is in **test_retry_backoff** — it asserts on a real",
                    trace = net.liquidx.leman.debug.SampleCorpus.ciTrace(),
                    interrupted = false,
                ),
            ),
            clock = "09:41", timestampOf = timestampOf, onEvent = {}, onBack = {},
        )
    }

    @Test
    fun thread_systemCollapsed() = snap("2b-thread-system") {
        ThreadScreen(
            state = ScreenshotFixtures.threadState(turns = ScreenshotFixtures.systemTurns()),
            clock = "09:41", timestampOf = timestampOf, onEvent = {}, onBack = {},
        )
    }

    @Test
    fun thread_failedSend() = snap("2b-thread-failed") {
        ThreadScreen(
            state = ScreenshotFixtures.threadState(turns = ScreenshotFixtures.failedSendTurns()),
            clock = "09:41", timestampOf = timestampOf, onEvent = {}, onBack = {},
        )
    }

    // ---- 2c / 2d -----------------------------------------------------------

    @Test
    fun newThread_default() = snap("2c-new-thread") {
        NewThreadScreen(
            state = NewThreadUiState(agentName = "juno"),
            clock = "09:41",
            connState = ConnState.Online("0.18.0"),
            onEvent = {}, onCancel = {},
        )
    }

    @Test
    fun config_default() = snap("2d-config") {
        ConfigScreen(
            state = ConfigUiState(
                apiKeyMasked = "hm_••••••••••••3kf2",
                connState = ConnState.Online("0.18.0"),
                testResult = TestConnectionState.Ok("hermes-agent", "0.18.0"),
                loaded = true,
            ),
            clock = "09:41", tabs = tabs, appVersion = "0.1",
            onEvent = {}, onRevealRequested = {}, onExport = {},
            onOpenThreads = {},
            serverUrlState = rememberTextFieldState("https://api.gent.ino.ink"),
            // production seeds this from settings; default agent name is "juno"
            agentNameState = rememberTextFieldState("juno"),
        )
    }

    // ---- markdown torture ----------------------------------------------------

    @Test
    fun markdown_torture() = snap("md-torture") {
        val blocks = segmentBlocks(Fixtures.load("markdown/torture.md"))
        Column(
            Modifier
                .size(380.dp, 788.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            net.liquidx.leman.ui.components.AgentTurn("juno", blocks)
        }
    }
}
