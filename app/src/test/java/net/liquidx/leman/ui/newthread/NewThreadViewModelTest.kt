package net.liquidx.leman.ui.newthread

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import net.liquidx.leman.testutil.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewThreadViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    @Test
    fun start_ignoresBlankText() = runTest {
        val h = VmHarness(this)
        val vm = NewThreadViewModel(h.repo, h.settingsStore)
        vm.textState.type("   ")
        vm.onEvent(NewThreadEvent.Start)
        advanceUntilIdle()
        assertTrue(h.repo.observeThreads().first().isEmpty())
        h.close()
    }

    @Test
    fun start_createsThread_emitsNavigationId() = runTest {
        val h = VmHarness(this)
        h.client.eventScripts.add(
            listOf(RunEvent.MessageDelta("on it", 1.0), RunEvent.RunCompleted("on it", null, 2.0)),
        )
        val vm = NewThreadViewModel(h.repo, h.settingsStore)
        vm.created.test {
            vm.textState.type("plan lyon trip")
            vm.onEvent(NewThreadEvent.Start)
            val id = awaitItem()
            advanceUntilIdle()
            val thread = h.repo.observeThreads().first().single()
            assertEquals(id, thread.id)
            assertEquals("plan lyon trip", thread.title)
        }
        h.close()
    }

    @Test
    fun start_isIdempotentWhileStarting() = runTest {
        val h = VmHarness(this)
        h.client.eventScripts.add(
            listOf(RunEvent.MessageDelta("x", 1.0), RunEvent.RunCompleted("x", null, 2.0)),
        )
        val vm = NewThreadViewModel(h.repo, h.settingsStore)
        vm.textState.type("only once")
        vm.onEvent(NewThreadEvent.Start)
        vm.onEvent(NewThreadEvent.Start)
        advanceUntilIdle()
        assertEquals(1, h.repo.observeThreads().first().size)
        h.close()
    }
}
