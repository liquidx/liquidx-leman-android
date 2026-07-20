package net.liquidx.leman.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.JobsRepository
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Job
import net.liquidx.leman.ui.format.TimeFormat

enum class JobTone { Accent, Danger, Faint }

data class JobListItem(
    val id: String,
    val name: String,
    val scheduleDisplay: String,
    val stateLabel: String,
    val tone: JobTone,
    /** Next fire time for enabled jobs; null when paused or unscheduled. */
    val nextRunLabel: String?,
)

data class JobsUiState(
    val items: List<JobListItem> = emptyList(),
    val totalCount: Int = 0,
    val pausedCount: Int = 0,
    val connState: ConnState = ConnState.NotConfigured,
    val loaded: Boolean = false,
    /** The latest refresh failed — shown inline; any cached list stays visible. */
    val refreshFailed: Boolean = false,
)

sealed interface JobsEvent {
    data object Refresh : JobsEvent
}

/** 2e — scheduled jobs (jobs-tab design). Refreshes from the gateway on entry. */
class JobsViewModel(
    private val repo: JobsRepository,
    connectionManager: ConnectionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    init {
        viewModelScope.launch { repo.refresh() }
    }

    val state: StateFlow<JobsUiState> = combine(
        repo.state,
        connectionManager.state,
    ) { jobs, conn ->
        JobsUiState(
            items = jobs.jobs.map { it.toItem(clock()) },
            totalCount = jobs.jobs.size,
            pausedCount = jobs.jobs.count { !it.enabled },
            connState = conn,
            loaded = jobs.loaded,
            refreshFailed = jobs.refreshError != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobsUiState())

    fun onEvent(event: JobsEvent) {
        when (event) {
            JobsEvent.Refresh -> viewModelScope.launch { repo.refresh() }
        }
    }

    private fun Job.toItem(now: Long): JobListItem {
        val (label, tone) = when {
            !enabled -> "paused" to JobTone.Faint
            lastStatus == "error" -> "failed" to JobTone.Danger
            else -> "scheduled" to JobTone.Accent
        }
        return JobListItem(
            id = id,
            name = name,
            scheduleDisplay = scheduleDisplay,
            stateLabel = label,
            tone = tone,
            nextRunLabel = nextRunAt
                ?.takeIf { enabled }
                ?.let { TimeFormat.upcomingLabel(it, now, zone) },
        )
    }
}
