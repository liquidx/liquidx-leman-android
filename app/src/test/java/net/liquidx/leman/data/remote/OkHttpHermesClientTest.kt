package net.liquidx.leman.data.remote

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.errorOrNull
import net.liquidx.leman.domain.model.getOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpHermesClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpHermesClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpHermesClient(userAgent = "leman-android/test")
        client.reconfigure(server.url("/").toString(), "testkey")
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.shutdown()
    }

    @Test
    fun health_ok_returnsDtoAndSendsBearer() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.load("wire/health.json")))
        val result = client.health()
        assertEquals("0.18.0", result.getOrNull()?.version)
        val request = server.takeRequest()
        assertEquals("/v1/health", request.path)
        assertEquals("Bearer testkey", request.getHeader("Authorization"))
        assertEquals("leman-android/test", request.getHeader("User-Agent"))
    }

    @Test
    fun startRun_postsHistoryAndReturnsRunId() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody(Fixtures.load("wire/run-accepted.json")))
        val result = client.startRun(
            messages = listOf(WireMessage("user", "hello"), WireMessage("assistant", "hi"), WireMessage("user", "fix ci")),
            sessionId = "sess_91",
        )
        assertEquals("run_7f3a", result.getOrNull()?.runId)
        val request = server.takeRequest()
        assertEquals("/v1/runs", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""model":"hermes-agent""""))
        assertTrue(body.contains(""""session_id":"sess_91""""))
        assertTrue(body.indexOf("hello") < body.indexOf("fix ci"))
    }

    @Test
    fun getRun_returnsRunDto() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.load("wire/run-completed.json")))
        val result = client.getRun("run_7f3a")
        assertEquals(RunStatus.Completed, result.getOrNull()?.runStatus)
        assertEquals("/v1/runs/run_7f3a", server.takeRequest().path)
    }

    @Test
    fun runEvents_streamsFixtureFramesAsEvents() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Fixtures.load("wire/events-stream.txt")),
        )
        val events = client.runEvents("run_7f3a").toList()
        assertEquals(10, events.size)
        assertTrue(events[0] is RunEvent.Reasoning)
        assertTrue(events[3] is RunEvent.MessageDelta)
        assertTrue(events[7] is RunEvent.Unknown)
        assertTrue(events[8] is RunEvent.Unknown)
        val completed = events.last() as RunEvent.RunCompleted
        assertEquals("the pipeline is fixed.", completed.output)
        assertEquals("/v1/runs/run_7f3a/events", server.takeRequest().path)
    }

    @Test
    fun errorMapping_401_isAuth() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}"""),
        )
        assertEquals(ApiError.Auth(401), client.health().errorOrNull())
    }

    @Test
    fun errorMapping_404_isClientWithMessage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"message":"run not found","type":"invalid_request_error","code":"run_not_found"}}"""),
        )
        val error = client.getRun("nope").errorOrNull() as ApiError.Client
        assertEquals(404, error.code)
        assertEquals("run not found", error.message)
    }

    @Test
    fun errorMapping_500_isServer() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        assertTrue(client.health().errorOrNull() is ApiError.Server)
    }

    @Test
    fun errorMapping_429_isServerSoItRetries() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client.health().errorOrNull() is ApiError.Server)
    }

    @Test
    fun errorMapping_garbageBody_isProtocol() = runTest {
        server.enqueue(MockResponse().setBody("<html>definitely not json</html>"))
        assertTrue(client.health().errorOrNull() is ApiError.Protocol)
    }

    @Test
    fun errorMapping_connectionFailure_isNetwork() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(client.health().errorOrNull() is ApiError.Network)
    }

    @Test
    fun notConfigured_returnsNotConfiguredWithoutTouchingNetwork() = runTest {
        val fresh = OkHttpHermesClient(userAgent = "t")
        assertEquals(ApiError.NotConfigured, fresh.health().errorOrNull())
        assertEquals(ApiError.NotConfigured, fresh.getRun("x").errorOrNull())
        fresh.shutdown()
    }

    @Test
    fun reconfigure_missingKey_isNotConfigured() = runTest {
        client.reconfigure(server.url("/").toString(), null)
        assertEquals(ApiError.NotConfigured, client.health().errorOrNull())
    }
}
