package net.liquidx.leman.ui.newthread

import app.cash.turbine.test
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import net.liquidx.leman.testutil.type
import net.liquidx.leman.ui.threads.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        h.client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("on it", null, 2.0)),
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
        h.client.chatScripts.add(
            listOf(RunEvent.RunStarted("r1", 1.0), RunEvent.RunCompleted("x", null, 2.0)),
        )
        val vm = NewThreadViewModel(h.repo, h.settingsStore)
        vm.textState.type("only once")
        vm.onEvent(NewThreadEvent.Start)
        vm.onEvent(NewThreadEvent.Start)
        advanceUntilIdle()
        assertEquals(1, h.repo.observeThreads().first().size)
        h.close()
    }

    @Test
    fun start_sessionCreateFails_surfacesFailure_staysOnScreen() = runTest {
        val h = VmHarness(this)
        h.client.createSessionResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val vm = NewThreadViewModel(h.repo, h.settingsStore)
        vm.state.test {
            awaitItem() // initial
            vm.textState.type("plan lyon trip")
            vm.onEvent(NewThreadEvent.Start)
            val failed = awaitUntil { it.startFailed }
            assertFalse(failed.starting) // retry is possible: starting flag was reset
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(h.repo.observeThreads().first().isEmpty())
        // text is preserved so the user can retry without retyping.
        assertEquals("plan lyon trip", vm.textState.text.toString())
        h.close()
    }
}
