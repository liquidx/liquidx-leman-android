package net.liquidx.leman.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ConnState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {

    // NOTE: backgroundScope tasks are not driven by advanceUntilIdle/advanceTimeBy in the
    // same way as an explicit scope on the shared test scheduler, so give the scheduler its
    // own scope here (mirrors ConnectionManagerTest's managerScope()).
    private fun TestScope.schedulerScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun foreground_syncsImmediately_thenEveryInterval_whileOnline() = runTest {
        val scope = schedulerScope()
        val connState = MutableStateFlow<ConnState>(ConnState.Online("0.18.0"))
        var syncCalls = 0
        val scheduler = SyncScheduler(
            syncNow = { syncCalls++ },
            connState = connState,
            scope = scope,
            intervalMillis = 30_000,
        )

        scheduler.onForeground()
        runCurrent()
        assertEquals(1, syncCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, syncCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, syncCalls)

        scope.cancel()
    }

    @Test
    fun background_stopsTicking() = runTest {
        val scope = schedulerScope()
        val connState = MutableStateFlow<ConnState>(ConnState.Online("0.18.0"))
        var syncCalls = 0
        val scheduler = SyncScheduler(
            syncNow = { syncCalls++ },
            connState = connState,
            scope = scope,
            intervalMillis = 30_000,
        )

        scheduler.onForeground()
        runCurrent()
        assertEquals(1, syncCalls)

        scheduler.onBackground()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, syncCalls) // no further ticks once backgrounded

        scope.cancel()
    }

    @Test
    fun offline_ticksSkipSync() = runTest {
        val scope = schedulerScope()
        val connState = MutableStateFlow<ConnState>(ConnState.Offline(ApiError.Timeout))
        var syncCalls = 0
        val scheduler = SyncScheduler(
            syncNow = { syncCalls++ },
            connState = connState,
            scope = scope,
            intervalMillis = 30_000,
        )

        scheduler.onForeground()
        runCurrent()
        assertEquals(0, syncCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, syncCalls)

        // Comes online mid-flight: the next tick should sync.
        connState.value = ConnState.Online("0.18.0")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, syncCalls)

        scope.cancel()
    }
}
