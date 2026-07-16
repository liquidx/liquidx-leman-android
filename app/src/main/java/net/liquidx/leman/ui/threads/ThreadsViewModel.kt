package net.liquidx.leman.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Thread
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.ui.format.DayBucket
import net.liquidx.leman.ui.format.TimeFormat

enum class StateTone { Accent, Warn, Danger, Faint }

data class ThreadListItem(
    val id: String,
    val title: String,
    val preview: String,
    val unread: Boolean,
    val pinned: Boolean,
    val running: Boolean,
    val failed: Boolean,
    val stateLabel: String,
    val tone: StateTone,
    val timeLabel: String,
)

data class ThreadSection(
    val key: String,       // PINNED / TODAY / YESTERDAY / EARLIER
    val count: Int,
    val dateLabel: String?,
    val items: List<ThreadListItem>,
)

data class ThreadsUiState(
    val sections: List<ThreadSection> = emptyList(),
    val filter: String = "",
    val totalCount: Int = 0,
    val runningCount: Int = 0,
    val connState: ConnState = ConnState.NotConfigured,
    val loaded: Boolean = false,
)

sealed interface ThreadsEvent {
    data class SetFilter(val query: String) : ThreadsEvent
    data class TogglePin(val threadId: String) : ThreadsEvent
}

class ThreadsViewModel(
    private val repo: ThreadRepository,
    connectionManager: ConnectionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    // A minute ticker so TODAY/YESTERDAY re-bucket at local midnight (spec 04).
    // Injectable: the infinite loop must not run under virtual-time tests.
    private val tick: kotlinx.coroutines.flow.Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    },
) : ViewModel() {

    private val filter = MutableStateFlow("")

    val state: StateFlow<ThreadsUiState> = combine(
        repo.observeThreads(),
        filter,
        connectionManager.state,
        tick,
    ) { threads, query, conn, _ ->
        build(threads, query, conn)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadsUiState())

    fun onEvent(event: ThreadsEvent) {
        when (event) {
            is ThreadsEvent.SetFilter -> filter.value = event.query
            is ThreadsEvent.TogglePin -> viewModelScope.launch {
                val current = state.value.sections.flatMap { it.items }
                    .firstOrNull { it.id == event.threadId }?.pinned ?: return@launch
                repo.setPinned(event.threadId, !current)
            }
        }
    }

    private fun build(threads: List<Thread>, query: String, conn: ConnState): ThreadsUiState {
        val now = clock()
        // Case-insensitive substring on title + preview (spec 03/07).
        val visible = if (query.isBlank()) {
            threads
        } else {
            threads.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.preview.contains(query, ignoreCase = true)
            }
        }
        val (pinned, unpinned) = visible.partition { it.pinned }
        val byBucket = unpinned.groupBy { TimeFormat.bucket(it.lastActiveAt, now, zone) }

        val sections = buildList {
            if (pinned.isNotEmpty()) {
                add(ThreadSection("PINNED", pinned.size, null, pinned.map { it.toItem(now) }))
            }
            byBucket[DayBucket.Today]?.let {
                add(ThreadSection("TODAY", it.size, TimeFormat.monthDay(now, zone), it.map { t -> t.toItem(now) }))
            }
            byBucket[DayBucket.Yesterday]?.let {
                add(
                    ThreadSection(
                        "YESTERDAY", it.size,
                        TimeFormat.monthDay(now - 86_400_000L, zone),
                        it.map { t -> t.toItem(now) },
                    ),
                )
            }
            byBucket[DayBucket.Earlier]?.let {
                add(ThreadSection("EARLIER", it.size, null, it.map { t -> t.toItem(now) }))
            }
        }
        return ThreadsUiState(
            sections = sections,
            filter = query,
            totalCount = threads.size,
            runningCount = threads.count { it.state == ThreadState.Running },
            connState = conn,
            loaded = true,
        )
    }

    private fun Thread.toItem(now: Long): ThreadListItem {
        // A Room-cached thread always reflects at least one server run (spec 03);
        // there's no longer a "never ran" idle distinct from "done" idle.
        val (label, tone) = when {
            state == ThreadState.Running -> "running" to StateTone.Accent
            state == ThreadState.Failed -> "failed" to StateTone.Danger
            else -> "done" to StateTone.Faint
        }
        return ThreadListItem(
            id = id,
            title = title,
            preview = preview,
            unread = unread,
            pinned = pinned,
            running = state == ThreadState.Running,
            failed = state == ThreadState.Failed,
            stateLabel = label,
            tone = tone,
            timeLabel = if (state == ThreadState.Running) "now" else TimeFormat.timeLabel(lastActiveAt, now, zone),
        )
    }
}
