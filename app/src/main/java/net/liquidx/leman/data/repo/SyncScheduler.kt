package net.liquidx.leman.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.liquidx.leman.domain.model.ConnState

/**
 * Foreground sync loop (spec §2): while the process is in the foreground and the
 * gateway is [ConnState.Online], syncs immediately and then every [intervalMillis].
 * Backgrounding cancels the loop outright — no sync while the app isn't visible.
 */
class SyncScheduler(
    private val syncNow: suspend () -> Unit,
    private val connState: StateFlow<ConnState>,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 30_000,
) {
    private var job: Job? = null

    fun onForeground() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                // Suspend until Online rather than skip-and-poll — this fires the first
                // sync the instant the gateway comes up (e.g. Checking→Online after
                // install), not a full interval later. Returns immediately if already
                // Online. runCatching so an unexpected throw in syncNow can't kill the
                // loop (or, via the appScope, crash the process).
                connState.first { it is ConnState.Online }
                runCatching { syncNow() }
                delay(intervalMillis)
            }
        }
    }

    fun onBackground() {
        job?.cancel()
        job = null
    }
}
