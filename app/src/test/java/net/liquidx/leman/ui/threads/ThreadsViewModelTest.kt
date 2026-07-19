package net.liquidx.leman.ui.threads

import app.cash.turbine.test
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThreadsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val dayMillis = 86_400_000L

    private fun entity(
        id: String,
        title: String,
        preview: String = "",
        pinned: Boolean = false,
        state: String = "idle",
        lastActiveAt: Long,
        source: String = "api_server",
    ) = ThreadEntity(
        id = id, title = title, preview = preview, state = state, pinned = pinned,
        unread = false, createdAt = lastActiveAt, lastActiveAt = lastActiveAt,
        source = source, agentName = null, agentGlyph = null,
    )

    private fun kotlinx.coroutines.test.TestScope.vm(harness: VmHarness) = ThreadsViewModel(
        repo = harness.repo,
        connectionManager = harness.connectionManager,
        clock = { harness.now },
        zone = ZoneOffset.UTC,
        tick = flowOf(Unit),
    )

    @Test
    fun grouping_pinnedTodayYesterdayEarlier_emptySectionsOmitted() = runTest {
        val h = VmHarness(this)
        val now = h.now
        h.db.threadDao().upsertThread(entity("p1", "book train to geneva", pinned = true, lastActiveAt = now - 3 * dayMillis))
        h.db.threadDao().upsertThread(entity("t1", "morning digest", lastActiveAt = now - 60_000))
        h.db.threadDao().upsertThread(entity("y1", "fix flaky ci pipeline", lastActiveAt = now - dayMillis))
        h.db.threadDao().upsertThread(entity("e1", "monitor hn for llm articles", lastActiveAt = now - 4 * dayMillis))

        val vm = vm(h)
        vm.state.test {
            val loaded = awaitLoaded()
            assertEquals(listOf("PINNED", "TODAY", "YESTERDAY", "EARLIER"), loaded.sections.map { it.key })
            assertEquals(listOf("p1"), loaded.sections[0].items.map { it.id })
            assertEquals(listOf("t1"), loaded.sections[1].items.map { it.id })
            assertEquals("jul 15", loaded.sections[1].dateLabel)
            assertEquals("jul 14", loaded.sections[2].dateLabel)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun filter_caseInsensitiveSubstring_onTitleAndPreview() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "Book Train to Geneva", preview = "3 options", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("b", "morning digest", preview = "GENEVA weather", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("c", "unrelated", preview = "nothing", lastActiveAt = h.now))

        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.SetFilter("geneva"))
            val filtered = awaitUntil { it.filter == "geneva" }
            assertEquals(setOf("a", "b"), filtered.sections.flatMap { it.items }.map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun readout_countsRunningThreads() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "one", state = "running", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("b", "two", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            val s = awaitLoaded()
            assertEquals(2, s.totalCount)
            assertEquals(1, s.runningCount)
            val running = s.sections.flatMap { it.items }.first { it.id == "a" }
            assertEquals("running", running.stateLabel)
            assertEquals(StateTone.Accent, running.tone)
            assertEquals("now", running.timeLabel)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun stateLabels_idleIsDone_failedIsFailed() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("i", "idle one", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("f", "failed one", state = "failed", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            val items = awaitLoaded().sections.flatMap { it.items }.associateBy { it.id }
            assertEquals("done", items["i"]?.stateLabel)
            assertEquals("failed", items["f"]?.stateLabel)
            assertEquals(StateTone.Danger, items["f"]?.tone)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun sourceLabel_nonApiServerSourceShown_apiServerSourceHidden() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("cron1", "cron thread", lastActiveAt = h.now, source = "cron"))
        h.db.threadDao().upsertThread(entity("app1", "app thread", lastActiveAt = h.now, source = "api_server"))
        val vm = vm(h)
        vm.state.test {
            val items = awaitLoaded().sections.flatMap { it.items }.associateBy { it.id }
            assertEquals("cron", items["cron1"]?.sourceLabel)
            assertEquals(null, items["app1"]?.sourceLabel)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun togglePin_delegatesToRepository() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "pin me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.TogglePin("a"))
            val pinned = awaitUntil { s -> s.sections.any { it.key == "PINNED" } }
            assertTrue(pinned.sections.first().items.single().pinned)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun revealDelete_opensRowWithoutDeleting() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            val revealed = awaitUntil { it.revealedDeleteId == "a" }
            assertEquals(1, revealed.totalCount)
            assertEquals(0, h.client.deleteCalls.size)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    /**
     * The old arm-then-timeout behaviour is deliberately gone: a revealed button
     * must not retract itself while the user is reaching for it.
     */
    @Test
    fun revealDelete_staysOpenIndefinitely() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            assertEquals("a", awaitUntil { it.revealedDeleteId == "a" }.revealedDeleteId)
            advanceTimeBy(60_000)
            advanceUntilIdle()
            expectNoEvents()
            assertEquals("a", vm.state.value.revealedDeleteId)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun revealDelete_secondRowClosesTheFirst() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "first", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("b", "second", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            awaitUntil { it.revealedDeleteId == "a" }
            vm.onEvent(ThreadsEvent.RevealDelete("b"))
            assertEquals("b", awaitUntil { it.revealedDeleteId == "b" }.revealedDeleteId)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun confirmDelete_removesThreadAndCallsServer() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        h.db.threadDao().upsertThread(entity("b", "keep me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            awaitUntil { it.revealedDeleteId == "a" }
            vm.onEvent(ThreadsEvent.ConfirmDelete("a"))
            val after = awaitUntil { s -> s.sections.flatMap { it.items }.none { it.id == "a" } }
            assertEquals(listOf("b"), after.sections.flatMap { it.items }.map { it.id })
            assertEquals(null, after.revealedDeleteId)
            assertEquals(listOf("a"), h.client.deleteCalls)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun confirmDelete_networkFailure_keepsRowAndSurfacesError() = runTest {
        val h = VmHarness(this)
        h.client.deleteSessionResult = ApiResult.Err(ApiError.Network(java.io.IOException("offline")))
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            awaitUntil { it.revealedDeleteId == "a" }
            vm.onEvent(ThreadsEvent.ConfirmDelete("a"))
            val failed = awaitUntil { it.deleteErrorId == "a" }
            assertEquals(listOf("a"), failed.sections.flatMap { it.items }.map { it.id })
            assertEquals(null, failed.revealedDeleteId)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun revealDelete_clearsAPriorDeleteError() = runTest {
        val h = VmHarness(this)
        h.client.deleteSessionResult = ApiResult.Err(ApiError.Network(java.io.IOException("offline")))
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            awaitUntil { it.revealedDeleteId == "a" }
            vm.onEvent(ThreadsEvent.ConfirmDelete("a"))
            awaitUntil { it.deleteErrorId == "a" }
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            assertEquals(null, awaitUntil { it.revealedDeleteId == "a" }.deleteErrorId)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }

    @Test
    fun hideDelete_closesRow() = runTest {
        val h = VmHarness(this)
        h.db.threadDao().upsertThread(entity("a", "delete me", lastActiveAt = h.now))
        val vm = vm(h)
        vm.state.test {
            awaitLoaded()
            vm.onEvent(ThreadsEvent.RevealDelete("a"))
            awaitUntil { it.revealedDeleteId == "a" }
            vm.onEvent(ThreadsEvent.HideDelete)
            assertEquals(null, awaitUntil { it.revealedDeleteId == null }.revealedDeleteId)
            assertEquals(0, h.client.deleteCalls.size)
            cancelAndIgnoreRemainingEvents()
        }
        h.close()
    }
}

/** Awaits the first loaded emission (initial state is a placeholder). */
suspend fun <T> app.cash.turbine.TurbineTestContext<T>.awaitLoaded(): T where T : Any {
    while (true) {
        val item = awaitItem()
        val loaded = when (item) {
            is ThreadsUiState -> item.loaded
            else -> true
        }
        if (loaded) return item
    }
}

suspend fun <T : Any> app.cash.turbine.TurbineTestContext<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
