package net.liquidx.leman.data.repo

import app.cash.turbine.test
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.Settings
import net.liquidx.leman.testutil.FakeHermesClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionManagerTest {

    // NOTE: backgroundScope tasks are not driven by advanceUntilIdle, so the
    // manager gets its own scope on the shared test scheduler.
    private fun TestScope.managerScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private fun manager(
        scope: CoroutineScope,
        client: FakeHermesClient,
        apiKey: String? = "key",
    ) = ConnectionManager(
        client = client,
        settings = flowOf(Settings()),
        apiKey = { apiKey },
        scope = scope,
        backoff = Backoff(random = Random(1)),
    )

    @Test
    fun reconfigure_noKey_goesNotConfigured_withoutProbing() = runTest {
        val scope = managerScope()
        val client = FakeHermesClient()
        val cm = manager(scope, client, apiKey = null)
        cm.reconfigure()
        advanceUntilIdle()
        assertEquals(ConnState.NotConfigured, cm.state.value)
        assertEquals(0, client.healthCalls)
        scope.cancel()
    }

    @Test
    fun reconfigure_healthOk_transitionsCheckingThenOnline() = runTest {
        val scope = managerScope()
        val client = FakeHermesClient()
        val cm = manager(scope, client)
        cm.state.test {
            assertEquals(ConnState.NotConfigured, awaitItem())
            cm.reconfigure()
            assertEquals(ConnState.Checking, awaitItem())
            assertEquals(ConnState.Online("0.18.0"), awaitItem())
        }
        assertEquals(listOf<Pair<String?, String?>>(Settings.DEFAULT_SERVER_URL to "key"), client.reconfigureCalls)
        scope.cancel()
    }

    @Test
    fun probe_authFailure_goesUnauthorized_andDoesNotRetry() = runTest {
        val scope = managerScope()
        val client = FakeHermesClient()
        client.healthResult = ApiResult.Err(ApiError.Auth(401))
        val cm = manager(scope, client)
        cm.reconfigure()
        advanceUntilIdle()
        assertTrue(cm.state.value is ConnState.Unauthorized)
        val calls = client.healthCalls
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(calls, client.healthCalls) // no retry loop for auth
        scope.cancel()
    }

    @Test
    fun probe_networkFailure_goesOffline_thenRetriesWithBackoffUntilOnline() = runTest {
        val scope = managerScope()
        val client = FakeHermesClient()
        client.healthResult = ApiResult.Err(ApiError.Network(IOException("down")))
        val cm = manager(scope, client)
        cm.reconfigure()
        runCurrent()
        assertTrue(cm.state.value is ConnState.Offline)
        val failedCalls = client.healthCalls

        client.healthResult = ApiResult.Ok(net.liquidx.leman.data.remote.HealthDto("ok", "hermes-agent", "0.19.0"))
        advanceTimeBy(2_000) // backoff base 1s ±20% elapses
        runCurrent()
        assertEquals(ConnState.Online("0.19.0"), cm.state.value)
        assertTrue(client.healthCalls > failedCalls)
        scope.cancel()
    }

    @Test
    fun onAuthFailure_fromARun_flipsGlobalStateToUnauthorized() = runTest {
        val scope = managerScope()
        val client = FakeHermesClient()
        val cm = manager(scope, client)
        cm.reconfigure()
        advanceUntilIdle()
        assertEquals(ConnState.Online("0.18.0"), cm.state.value)
        cm.onAuthFailure(401)
        assertTrue(cm.state.value is ConnState.Unauthorized)
        scope.cancel()
    }
}
