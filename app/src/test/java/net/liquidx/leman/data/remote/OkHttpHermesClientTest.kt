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
                .setBody("""{"error":{"message":"session not found","type":"invalid_request_error","code":"session_not_found"}}"""),
        )
        val error = client.sessionMessages("nope").errorOrNull() as ApiError.Client
        assertEquals(404, error.code)
        assertEquals("session not found", error.message)
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
        assertEquals(ApiError.NotConfigured, fresh.sessionMessages("x").errorOrNull())
        fresh.shutdown()
    }

    @Test
    fun reconfigure_missingKey_isNotConfigured() = runTest {
        client.reconfigure(server.url("/").toString(), null)
        assertEquals(ApiError.NotConfigured, client.health().errorOrNull())
    }

    @Test
    fun listSessions_getsPagedPath_andDecodes() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","data":[{"id":"run_a","source":"cron","last_active":2.0}],"has_more":false}""",
        ))
        val result = client.listSessions(limit = 50, offset = 0) as ApiResult.Ok
        assertEquals("run_a", result.value.data.single().id)
        val req = server.takeRequest()
        assertEquals("/api/sessions?limit=50&offset=0", req.path)
        assertEquals("GET", req.method)
        assertTrue(req.getHeader("Authorization")!!.startsWith("Bearer "))
    }

    @Test
    fun sessionMessages_decodesList() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"object":"list","session_id":"s1","data":[{"id":1,"role":"user","content":"hi","timestamp":1.0}]}""",
        ))
        val result = client.sessionMessages("s1") as ApiResult.Ok
        assertEquals("hi", result.value.single().content)
        assertEquals("/api/sessions/s1/messages", server.takeRequest().path)
    }

    @Test
    fun createSession_postsEmptyObject_unwrapsEnvelope() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"object":"hermes.session","session":{"id":"api_1_a","source":"api_server"}}""",
        ))
        val result = client.createSession() as ApiResult.Ok
        assertEquals("api_1_a", result.value.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("{}", req.body.readUtf8())
    }

    @Test
    fun renameSession_patchesTitle() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        client.renameSession("s1", "new title")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/sessions/s1", req.path)
        assertEquals("""{"title":"new title"}""", req.body.readUtf8())
    }

    @Test
    fun deleteSession_deletes() = runTest {
        server.enqueue(MockResponse().setBody("""{"deleted":true}"""))
        assertTrue(client.deleteSession("s1") is ApiResult.Ok)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun chatStream_parsesNamedEvents() = runTest {
        server.enqueue(MockResponse().setBody(
            "event: run.started\ndata: {\"run_id\":\"r1\",\"ts\":1.0}\n\n" +
            "event: assistant.delta\ndata: {\"delta\":\"ok\",\"ts\":2.0}\n\n" +
            "event: run.completed\ndata: {\"messages\":[{\"role\":\"assistant\",\"content\":\"ok\",\"finish_reason\":\"stop\"}],\"ts\":3.0}\n\n",
        ))
        val events = client.chatStream("s1", "hello").toList()
        assertEquals(RunEvent.RunStarted("r1", 1.0), events[0])
        assertEquals(RunEvent.MessageDelta("ok", 2.0), events[1])
        assertEquals("ok", (events[2] as RunEvent.RunCompleted).output)
        val req = server.takeRequest()
        assertEquals("/api/sessions/s1/chat/stream", req.path)
        assertEquals("""{"message":"hello"}""", req.body.readUtf8())
    }

    @Test
    fun listJobs_getsAndUnwraps() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.load("wire/jobs.json")))
        val result = client.listJobs() as ApiResult.Ok
        assertEquals(listOf("c526238e14c7", "fc50f3d48141"), result.value.map { it.id })
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/jobs", req.path)
        assertEquals("Bearer testkey", req.getHeader("Authorization"))
    }

    @Test
    fun createJob_postsContract_unwrapsEnvelope() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"job":{"id":"3121d2d43d3f","name":"probe","prompt":"say hi","schedule_display":"0 5 1 1 *"}}""",
        ))
        val result = client.createJob(JobCreateDto("probe", "say hi", "0 5 1 1 *")) as ApiResult.Ok
        assertEquals("3121d2d43d3f", result.value.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/jobs", req.path)
        assertEquals("""{"name":"probe","prompt":"say hi","schedule":"0 5 1 1 *"}""", req.body.readUtf8())
    }

    @Test
    fun updateJob_patchesOnlySetFields() = runTest {
        server.enqueue(MockResponse().setBody("""{"job":{"id":"3121d2d43d3f","enabled":false}}"""))
        val result = client.updateJob("3121d2d43d3f", JobPatchDto(enabled = false)) as ApiResult.Ok
        assertEquals(false, result.value.enabled)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/jobs/3121d2d43d3f", req.path)
        assertEquals("""{"enabled":false}""", req.body.readUtf8())
    }

    @Test
    fun deleteJob_deletes() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(client.deleteJob("3121d2d43d3f") is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/jobs/3121d2d43d3f", req.path)
    }

    @Test
    fun jobsErrorMapping_stringErrorBody_surfacesMessage() = runTest {
        // /api/jobs errors are {"error":"…"} strings, not the /v1 object envelope;
        // the invalid-schedule 500 carries usage text the edit screen shows inline.
        server.enqueue(MockResponse().setResponseCode(500).setBody(
            """{"error":"Invalid schedule 'x'. Use:\n  - Cron: '0 9 * * *'"}""",
        ))
        val error = client.updateJob("3121d2d43d3f", JobPatchDto(schedule = "x")).errorOrNull() as ApiError.Server
        assertEquals(500, error.code)
        assertTrue(error.message!!.startsWith("Invalid schedule"))
    }

    @Test
    fun registerDevice_postsContract() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client.registerDevice("tok123", "dev-uuid") is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/notify/v1/devices", req.path)
        assertEquals("Bearer testkey", req.getHeader("Authorization"))
        assertEquals(
            """{"device_id":"dev-uuid","fcm_token":"tok123"}""",
            req.body.readUtf8(),
        )
    }

    @Test
    fun registerDevice_404_isClient() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        assertTrue(client.registerDevice("t", "d").errorOrNull() is ApiError.Client)
    }

    @Test
    fun unregisterDevice_deletesContract() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client.unregisterDevice("dev-uuid") is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/notify/v1/devices/dev-uuid", req.path)
        assertEquals("Bearer testkey", req.getHeader("Authorization"))
    }

    @Test
    fun unregisterDevice_404_isClient() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        assertTrue(client.unregisterDevice("dev-uuid").errorOrNull() is ApiError.Client)
    }
}
