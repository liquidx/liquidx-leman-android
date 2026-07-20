package net.liquidx.leman.ui.jobs

import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.remote.JobDto
import net.liquidx.leman.data.repo.JobsRepository
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.VmHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JobsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    // 2026-07-21T07:00:00+09:00 == 2026-07-20T22:00:00Z; "now" is an hour before.
    private val nextRunEpoch = 1784584800000L
    private val now = nextRunEpoch - 3_600_000L

    private fun jobs() = listOf(
        JobDto(
            id = "aaa",
            name = "daily digest",
            scheduleDisplay = "0 7 * * *",
            nextRunAt = "2026-07-21T07:00:00+09:00",
            lastStatus = "ok",
        ),
        JobDto(id = "bbb", name = "paused one", scheduleDisplay = "every 60m", enabled = false, state = "paused"),
        JobDto(id = "ccc", name = "broken one", scheduleDisplay = "every 5m", lastStatus = "error", lastError = "boom"),
    )

    private fun kotlinx.coroutines.test.TestScope.vm(h: VmHarness, repo: JobsRepository) =
        JobsViewModel(repo, h.connectionManager, clock = { now }, zone = ZoneId.of("UTC"))

    @Test
    fun init_refreshes_andBuildsItems() = runTest {
        val h = VmHarness(this)
        h.client.listJobsResult = ApiResult.Ok(jobs())
        val vm = vm(h, JobsRepository(h.client))
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.loaded)
        assertEquals(3, state.totalCount)
        assertEquals(1, state.pausedCount)

        val (digest, paused, broken) = state.items
        assertEquals("scheduled", digest.stateLabel)
        assertEquals(JobTone.Accent, digest.tone)
        // Instant is 22:00Z; the VM renders in UTC where that's still "today" → bare time.
        assertEquals("22:00", digest.nextRunLabel)
        assertEquals("paused", paused.stateLabel)
        assertEquals(JobTone.Faint, paused.tone)
        assertNull(paused.nextRunLabel)
        assertEquals("failed", broken.stateLabel)
        assertEquals(JobTone.Danger, broken.tone)
        h.close()
    }

    @Test
    fun refreshFailure_setsFlag_keepsList() = runTest {
        val h = VmHarness(this)
        h.client.listJobsResult = ApiResult.Ok(jobs())
        val repo = JobsRepository(h.client)
        val vm = vm(h, repo)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        h.client.listJobsResult = ApiResult.Err(ApiError.Timeout)
        vm.onEvent(JobsEvent.Refresh)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.refreshFailed)
        assertEquals(3, state.items.size)
        h.close()
    }
}
