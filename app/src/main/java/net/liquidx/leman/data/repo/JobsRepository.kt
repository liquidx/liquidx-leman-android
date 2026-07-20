package net.liquidx.leman.data.repo

import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.JobCreateDto
import net.liquidx.leman.data.remote.JobDto
import net.liquidx.leman.data.remote.JobPatchDto
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.Job

/**
 * In-memory store over `/api/jobs` (jobs-tab design). Jobs are low-volume
 * admin data with no offline requirement, so unlike threads there is no Room
 * cache — screens refresh on entry and mutations fold the server's echo back
 * into [state] (the server normalizes schedules, so its echo is the truth;
 * no optimistic writes).
 */
class JobsRepository(
    private val client: HermesClient,
    private val onAuthFailure: (Int) -> Unit = {},
) {

    data class JobsState(
        val jobs: List<Job> = emptyList(),
        /** True once any refresh has completed, even a failed one. */
        val loaded: Boolean = false,
        /** Error from the most recent refresh; cleared by a successful one. */
        val refreshError: ApiError? = null,
    )

    private val _state = MutableStateFlow(JobsState())
    val state: StateFlow<JobsState> = _state

    fun job(id: String): Job? = _state.value.jobs.firstOrNull { it.id == id }

    suspend fun refresh(): ApiResult<Unit> = when (val result = client.listJobs()) {
        is ApiResult.Ok -> {
            _state.value = JobsState(jobs = result.value.map { it.toJob() }, loaded = true)
            ApiResult.Ok(Unit)
        }
        is ApiResult.Err -> {
            reportAuth(result.error)
            // Keep the last good list — a flaky refresh shouldn't blank the tab.
            _state.update { it.copy(loaded = true, refreshError = result.error) }
            result
        }
    }

    suspend fun create(name: String, prompt: String, schedule: String): ApiResult<Job> =
        client.createJob(JobCreateDto(name = name, prompt = prompt, schedule = schedule))
            .fold { created -> _state.update { it.copy(jobs = it.jobs + created) } }

    /** Full-form save from the edit screen — always patches all three fields. */
    suspend fun save(id: String, name: String, prompt: String, schedule: String): ApiResult<Job> =
        client.updateJob(id, JobPatchDto(name = name, prompt = prompt, schedule = schedule))
            .fold(::replace)

    suspend fun setEnabled(id: String, enabled: Boolean): ApiResult<Job> =
        client.updateJob(id, JobPatchDto(enabled = enabled)).fold(::replace)

    suspend fun delete(id: String): ApiResult<Unit> = when (val result = client.deleteJob(id)) {
        is ApiResult.Ok -> {
            _state.update { s -> s.copy(jobs = s.jobs.filterNot { it.id == id }) }
            result
        }
        is ApiResult.Err -> {
            reportAuth(result.error)
            result
        }
    }

    private fun replace(job: Job) {
        _state.update { s -> s.copy(jobs = s.jobs.map { if (it.id == job.id) job else it }) }
    }

    private inline fun ApiResult<JobDto>.fold(onOk: (Job) -> Unit): ApiResult<Job> = when (this) {
        is ApiResult.Ok -> {
            val job = value.toJob()
            onOk(job)
            ApiResult.Ok(job)
        }
        is ApiResult.Err -> {
            reportAuth(error)
            this
        }
    }

    private fun reportAuth(error: ApiError) {
        if (error is ApiError.Auth) onAuthFailure(error.code)
    }
}

fun JobDto.toJob(): Job = Job(
    id = id,
    name = name,
    prompt = prompt,
    scheduleDisplay = scheduleDisplay.ifBlank { schedule?.display.orEmpty() },
    enabled = enabled,
    nextRunAt = nextRunAt.toEpochMillis(),
    lastRunAt = lastRunAt.toEpochMillis(),
    lastStatus = lastStatus,
    lastError = lastError,
    runsCompleted = repeat?.completed ?: 0,
)

private fun String?.toEpochMillis(): Long? = this?.let {
    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
}
