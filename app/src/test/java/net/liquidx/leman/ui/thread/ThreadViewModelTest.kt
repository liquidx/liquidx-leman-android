package net.liquidx.leman.ui.thread

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import net.liquidx.leman.ui.threads.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private fun completedScript(output: String, withTrace: Boolean = false) = buildList<Any> {
        add(RunEvent.RunStarted("r1", 0.1))
        if (withTrace) {
            add(RunEvent.Reasoning("thinking", 0.5))
            add(RunEvent.ToolStarted("web_search", "query", 1.0))
            add(RunEvent.ToolCompleted("web_search", 2.0, false, 2.0))
        }
        add(RunEvent.MessageDelta(output, 3.0))
        add(RunEvent.RunCompleted(output, null, 4.0))
    }

    private fun kotlinx.coroutines.test.TestScope.vm(
        harness: VmHarness,
        threadId: String,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = ThreadViewModel(
        repo = harness.repo,
        connectionManager = harness.connectionManager,
        settingsStore = harness.settingsStore,
        savedState = savedState,
        threadId = threadId,
        zone = ZoneOffset.UTC,
    )

    @Test
    fun traceExpansion_defaultsFromPref_overrideWins() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(completedScript("answer", withTrace = true))
        val threadId = h.repo.createThread("with trace")!!
        advanceUntilIdle()

        val vm = vm(h, threadId)
        vm.state.test {
            val loaded = awaitUntil { it.loaded && it.turns.isNotEmpty() }
            val traceTurn = loaded.turns.single { it.kind == TurnKind.Trace }
            assertFalse(loaded.expandedTraces.contains(traceTurn.id)) // pref default: collapsed

            vm.onEvent(ThreadEvent.ToggleTrace(traceTurn.id))
            assertTrue(awaitUntil { it.expandedTraces.isNotEmpty() }.expandedTraces.contains(traceTurn.id))

            vm.onEvent(ThreadEvent.ToggleTrace(traceTurn.id))
            assertFalse(awaitUntil { it.expandedTraces.isEmpty() }.expandedTraces.contains(traceTurn.id))
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun traceExpansion_expandByDefaultPref_expandsWithoutOverride() = runTest {
        val h = VmHarness(this)
        h.settingsStore.update { it.copy(expandTracesByDefault = true) }
        h.client.chatScripts.add(completedScript("answer", withTrace = true))
        val threadId = h.repo.createThread("with trace")!!
        advanceUntilIdle()

        val vm = vm(h, threadId)
        vm.state.test {
            val loaded = awaitUntil { it.loaded && it.turns.isNotEmpty() }
            val traceTurn = loaded.turns.single { it.kind == TurnKind.Trace }
            assertTrue(loaded.expandedTraces.contains(traceTurn.id))
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun send_appendsUserTurn_streamingStateExposed() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(completedScript("first"))
        val threadId = h.repo.createThread("start")!!
        advanceUntilIdle()

        val vm = vm(h, threadId)
        vm.state.test {
            awaitUntil { it.loaded && it.turns.size == 2 }
            h.client.chatScripts.add(completedScript("second answer"))
            vm.onEvent(ThreadEvent.Send("follow up"))
            val after = awaitUntil { s -> s.turns.count { it.kind == TurnKind.User } == 2 }
            assertEquals("follow up", after.turns.last { it.kind == TurnKind.User }.markdown)
            awaitUntil { s -> s.turns.count { it.kind == TurnKind.Agent } == 2 }
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun actionTapped_sendsPayloadViaButton() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(completedScript("ok"))
        val threadId = h.repo.createThread("actions")!!
        advanceUntilIdle()

        val vm = vm(h, threadId)
        vm.state.test {
            awaitUntil { it.loaded && it.turns.isNotEmpty() }
            h.client.chatScripts.add(completedScript("confirmed"))
            vm.onEvent(
                ThreadEvent.ActionTapped(
                    net.liquidx.leman.domain.model.ActionButton(
                        "confirm booking", "confirm booking", net.liquidx.leman.domain.model.ActionKind.Primary,
                    ),
                ),
            )
            val after = awaitUntil { s -> s.turns.count { it.kind == TurnKind.User } == 2 }
            val viaButton = after.turns.last { it.kind == TurnKind.User }
            assertTrue(viaButton.viaButton)
            assertEquals("confirm booking", viaButton.markdown)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun composer_disabledWhenNotConfigured_enabledWhenOnline() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(completedScript("a"))
        val threadId = h.repo.createThread("conn")!!
        advanceUntilIdle()

        val vm = vm(h, threadId)
        vm.state.test {
            val loaded = awaitUntil { it.loaded }
            assertFalse(loaded.composerEnabled) // no key yet → NotConfigured

            h.apiKeyStore.set("key")
            h.connectionManager.reconfigure()
            assertTrue(awaitUntil { it.connState is ConnState.Online }.composerEnabled)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun openingThread_marksItRead() = runTest {
        val h = VmHarness(this)
        h.client.chatScripts.add(completedScript("a"))
        val threadId = h.repo.createThread("unread")!!
        advanceUntilIdle()
        assertTrue(h.repo.observeThreads().first().single().unread)

        val vm = vm(h, threadId)
        vm.state.test {
            awaitUntil { it.loaded && it.thread?.unread == false }
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }
}
