package net.liquidx.leman.ui.jobs

import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.remote.JobDto
import net.liquidx.leman.data.repo.JobsRepository
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.testutil.FakeHermesClient
import net.liquidx.leman.testutil.MainDispatcherRule
import net.liquidx.leman.testutil.type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class JobEditViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val client = FakeHermesClient()
    private val repo = JobsRepository(client)

    private val storedJob = JobDto(
        id = "aaa",
        name = "daily digest",
        prompt = "summarize things",
        scheduleDisplay = "0 7 * * *",
    )

    @Test
    fun newJob_saveBlank_doesNothing() = runTest {
        val vm = JobEditViewModel(repo, jobId = null)
        vm.onEvent(JobEditEvent.Save)
        advanceUntilIdle()
        assertTrue(client.createJobCalls.isEmpty())
    }

    @Test
    fun newJob_save_createsAndEmitsDone() = runTest {
        client.createJobResult = ApiResult.Ok(storedJob)
        val vm = JobEditViewModel(repo, jobId = null)
        vm.done.test {
            vm.nameState.type("  daily digest ")
            vm.scheduleState.type(" 0 7 * * * ")
            vm.promptState.type(" summarize things ")
            vm.onEvent(JobEditEvent.Save)
            awaitItem()
        }
        val sent = client.createJobCalls.single()
        assertEquals("daily digest", sent.name)
        assertEquals("0 7 * * *", sent.schedule)
        assertEquals("summarize things", sent.prompt)
        assertEquals("aaa", repo.state.value.jobs.single().id)
    }

    @Test
    fun newJob_serverRejectsSchedule_showsMessageInline() = runTest {
        client.createJobResult = ApiResult.Err(ApiError.Server(500, "Invalid schedule 'x'. Use cron"))
        val vm = JobEditViewModel(repo, jobId = null)
        vm.nameState.type("n")
        vm.scheduleState.type("x")
        vm.promptState.type("p")
        vm.onEvent(JobEditEvent.Save)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Invalid schedule 'x'. Use cron", state.error)
        assertFalse(state.busy) // retry possible
    }

    @Test
    fun edit_prefillsFromStore_refreshingIfNeeded() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(storedJob))
        val vm = JobEditViewModel(repo, jobId = "aaa")
        advanceUntilIdle()
        assertEquals("daily digest", vm.nameState.text.toString())
        assertEquals("0 7 * * *", vm.scheduleState.text.toString())
        assertEquals("summarize things", vm.promptState.text.toString())
        assertFalse(vm.state.value.isNew)
        assertFalse(vm.state.value.missing)
        assertEquals(1, client.listJobsCalls)
    }

    @Test
    fun edit_jobGone_flagsMissing() = runTest {
        client.listJobsResult = ApiResult.Ok(emptyList())
        val vm = JobEditViewModel(repo, jobId = "zzz")
        advanceUntilIdle()
        assertTrue(vm.state.value.missing)
    }

    @Test
    fun edit_save_patchesAllFields_emitsDone() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(storedJob))
        client.updateJobResult = ApiResult.Ok(storedJob.copy(name = "renamed"))
        val vm = JobEditViewModel(repo, jobId = "aaa")
        advanceUntilIdle()
        vm.done.test {
            vm.nameState.type("renamed")
            vm.onEvent(JobEditEvent.Save)
            awaitItem()
        }
        val (id, patch) = client.updateJobCalls.single()
        assertEquals("aaa", id)
        assertEquals("renamed", patch.name)
        assertEquals("0 7 * * *", patch.schedule)
        assertNull(patch.enabled)
    }

    @Test
    fun edit_toggleEnabled_revertsOnFailure() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(storedJob))
        val vm = JobEditViewModel(repo, jobId = "aaa")
        advanceUntilIdle()
        client.updateJobResult = ApiResult.Err(ApiError.Timeout)
        vm.onEvent(JobEditEvent.SetEnabled(false))
        advanceUntilIdle()
        assertTrue(vm.state.value.enabled) // reverted
        assertEquals("timed out · try again", vm.state.value.error)
    }

    @Test
    fun edit_delete_isTwoTap_thenEmitsDone() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(storedJob))
        val vm = JobEditViewModel(repo, jobId = "aaa")
        advanceUntilIdle()
        vm.onEvent(JobEditEvent.RequestDelete)
        assertTrue(vm.state.value.confirmingDelete)
        assertTrue(client.deleteJobCalls.isEmpty())
        vm.done.test {
            vm.onEvent(JobEditEvent.ConfirmDelete)
            awaitItem()
        }
        assertEquals(listOf("aaa"), client.deleteJobCalls)
        assertTrue(repo.state.value.jobs.isEmpty())
    }

    @Test
    fun edit_deleteFailure_staysWithError() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(storedJob))
        client.deleteJobResult = ApiResult.Err(ApiError.Server(500, null))
        val vm = JobEditViewModel(repo, jobId = "aaa")
        advanceUntilIdle()
        vm.onEvent(JobEditEvent.RequestDelete)
        vm.onEvent(JobEditEvent.ConfirmDelete)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("server error · try again", state.error)
        assertFalse(state.busy)
        assertFalse(state.confirmingDelete)
    }
}
