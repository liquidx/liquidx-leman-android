package net.liquidx.leman.data.repo

import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.remote.JobDto
import net.liquidx.leman.data.remote.JobRepeatDto
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.testutil.FakeHermesClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsRepositoryTest {

    private val client = FakeHermesClient()
    private var authFailures = mutableListOf<Int>()
    private val repo = JobsRepository(client, onAuthFailure = { authFailures += it })

    private fun dto(id: String, name: String = "job $id") = JobDto(
        id = id,
        name = name,
        prompt = "do things",
        scheduleDisplay = "0 7 * * *",
        nextRunAt = "2026-07-21T07:00:00+09:00",
        repeat = JobRepeatDto(completed = 3),
    )

    @Test
    fun refresh_ok_populatesStateAndParsesTimestamps() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a"), dto("b")))
        assertTrue(repo.refresh() is ApiResult.Ok)
        val state = repo.state.value
        assertTrue(state.loaded)
        assertNull(state.refreshError)
        assertEquals(listOf("a", "b"), state.jobs.map { it.id })
        // 2026-07-21T07:00:00+09:00 == 2026-07-20T22:00:00Z
        assertEquals(1784584800000L, state.jobs[0].nextRunAt)
        assertEquals(3, state.jobs[0].runsCompleted)
    }

    @Test
    fun refresh_error_keepsLastGoodListAndSetsError() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a")))
        repo.refresh()
        client.listJobsResult = ApiResult.Err(ApiError.Timeout)
        assertTrue(repo.refresh() is ApiResult.Err)
        val state = repo.state.value
        assertEquals(listOf("a"), state.jobs.map { it.id })
        assertEquals(ApiError.Timeout, state.refreshError)
        assertTrue(state.loaded)
    }

    @Test
    fun refresh_authError_reportsToConnectionManager() = runTest {
        client.listJobsResult = ApiResult.Err(ApiError.Auth(401))
        repo.refresh()
        assertEquals(listOf(401), authFailures)
    }

    @Test
    fun create_appendsServerEcho() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a")))
        repo.refresh()
        client.createJobResult = ApiResult.Ok(dto("new", name = "created"))
        val result = repo.create("created", "p", "every 2h") as ApiResult.Ok
        assertEquals("new", result.value.id)
        assertEquals(listOf("a", "new"), repo.state.value.jobs.map { it.id })
        val sent = client.createJobCalls.single()
        assertEquals("created", sent.name)
        assertEquals("every 2h", sent.schedule)
    }

    @Test
    fun save_replacesInPlace_patchesAllFormFields() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a"), dto("b")))
        repo.refresh()
        client.updateJobResult = ApiResult.Ok(dto("a", name = "renamed"))
        repo.save("a", "renamed", "p2", "every 5m")
        assertEquals(listOf("renamed", "job b"), repo.state.value.jobs.map { it.name })
        val (id, patch) = client.updateJobCalls.single()
        assertEquals("a", id)
        assertEquals("renamed", patch.name)
        assertEquals("p2", patch.prompt)
        assertEquals("every 5m", patch.schedule)
        assertNull(patch.enabled)
    }

    @Test
    fun setEnabled_patchesOnlyEnabled() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a")))
        repo.refresh()
        client.updateJobResult = ApiResult.Ok(dto("a").copy(enabled = false))
        repo.setEnabled("a", false)
        assertFalse(repo.state.value.jobs.single().enabled)
        val (_, patch) = client.updateJobCalls.single()
        assertEquals(false, patch.enabled)
        assertNull(patch.name)
        assertNull(patch.schedule)
    }

    @Test
    fun save_error_leavesStateUntouched() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a")))
        repo.refresh()
        client.updateJobResult = ApiResult.Err(ApiError.Server(500, "Invalid schedule 'x'"))
        val result = repo.save("a", "n", "p", "x")
        assertTrue(result is ApiResult.Err)
        assertEquals("job a", repo.state.value.jobs.single().name)
    }

    @Test
    fun delete_removesFromState() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a"), dto("b")))
        repo.refresh()
        assertTrue(repo.delete("a") is ApiResult.Ok)
        assertEquals(listOf("b"), repo.state.value.jobs.map { it.id })
        assertEquals(listOf("a"), client.deleteJobCalls)
    }

    @Test
    fun delete_error_keepsJob() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(dto("a")))
        repo.refresh()
        client.deleteJobResult = ApiResult.Err(ApiError.Timeout)
        assertTrue(repo.delete("a") is ApiResult.Err)
        assertEquals(listOf("a"), repo.state.value.jobs.map { it.id })
    }

    @Test
    fun toJob_fallsBackToNestedScheduleDisplay_andSurvivesBadTimestamps() = runTest {
        client.listJobsResult = ApiResult.Ok(listOf(
            JobDto(
                id = "x",
                scheduleDisplay = "",
                schedule = net.liquidx.leman.data.remote.JobScheduleDto(kind = "interval", minutes = 120, display = "every 120m"),
                nextRunAt = "not a timestamp",
            ),
        ))
        repo.refresh()
        val job = repo.state.value.jobs.single()
        assertEquals("every 120m", job.scheduleDisplay)
        assertNull(job.nextRunAt)
    }
}
