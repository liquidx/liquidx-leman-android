package net.liquidx.leman.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.liquidx.leman.data.remote.CapabilitiesDto
import net.liquidx.leman.data.remote.HealthDto
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.RunAcceptedDto
import net.liquidx.leman.data.remote.RunDto
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.data.remote.SessionListDto
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.data.remote.WireMessage
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent

/**
 * Scriptable fake for unit tests. Streams are scripted per open: a list of
 * [RunEvent]s to emit, optionally ending with a [Throwable] to fail the stream.
 */
class FakeHermesClient : HermesClient {

    var healthResult: ApiResult<HealthDto> =
        ApiResult.Ok(HealthDto("ok", "hermes-agent", "0.18.0"))
    var startRunResult: ApiResult<RunAcceptedDto> =
        ApiResult.Ok(RunAcceptedDto("run_1", "started"))

    /** Consumed one per [getRun] call; the last one repeats. */
    val getRunResults = ArrayDeque<ApiResult<RunDto>>()

    /** Consumed one per [runEvents] open; the last one repeats. */
    val eventScripts = ArrayDeque<List<Any>>()

    val startRunCalls = mutableListOf<Pair<List<WireMessage>, String?>>()
    var healthCalls = 0
    var getRunCalls = 0
    var streamOpens = 0
    var reconfigureCalls = mutableListOf<Pair<String?, String?>>()

    override suspend fun health(): ApiResult<HealthDto> {
        healthCalls++
        return healthResult
    }

    override suspend fun models(): ApiResult<List<String>> = ApiResult.Ok(listOf("hermes-agent"))

    override suspend fun startRun(
        messages: List<WireMessage>,
        sessionId: String?,
    ): ApiResult<RunAcceptedDto> {
        startRunCalls += messages to sessionId
        return startRunResult
    }

    override suspend fun getRun(id: String): ApiResult<RunDto> {
        getRunCalls++
        return when {
            getRunResults.isEmpty() -> ApiResult.Ok(runDto("running"))
            getRunResults.size > 1 -> getRunResults.removeFirst()
            else -> getRunResults.first()
        }
    }

    override fun runEvents(id: String): Flow<RunEvent> = flow {
        streamOpens++
        val script = when {
            eventScripts.isEmpty() -> emptyList()
            eventScripts.size > 1 -> eventScripts.removeFirst()
            else -> eventScripts.first()
        }
        for (item in script) {
            when (item) {
                is RunEvent -> emit(item)
                is Throwable -> throw item
                else -> error("bad script item $item")
            }
        }
    }

    override fun reconfigure(baseUrl: String?, apiKey: String?) {
        reconfigureCalls += baseUrl to apiKey
    }

    var capabilitiesResult: ApiResult<CapabilitiesDto> = ApiResult.Ok(
        CapabilitiesDto(features = mapOf(
            "session_resources" to kotlinx.serialization.json.JsonPrimitive(true),
            "session_chat_streaming" to kotlinx.serialization.json.JsonPrimitive(true),
        )),
    )
    /** Consumed one per [listSessions] call; the last one repeats. */
    val listSessionsResults = ArrayDeque<ApiResult<SessionListDto>>()
    /** Keyed by session id; missing id → 404. */
    val messagesBySession = mutableMapOf<String, ApiResult<List<SessionMessageDto>>>()
    var createSessionResult: ApiResult<SessionDto> = ApiResult.Ok(SessionDto(id = "api_1_test"))
    var renameSessionResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var deleteSessionResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    /** Consumed one per [chatStream] open; the last one repeats. Items: RunEvent or Throwable. */
    val chatScripts = ArrayDeque<List<Any>>()

    val renameCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()
    val chatCalls = mutableListOf<Pair<String, String>>()
    var listSessionsCalls = 0
    var sessionMessagesCalls = 0

    override suspend fun capabilities() = capabilitiesResult

    override suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto> {
        listSessionsCalls++
        return when {
            listSessionsResults.isEmpty() -> ApiResult.Ok(SessionListDto())
            listSessionsResults.size > 1 -> listSessionsResults.removeFirst()
            else -> listSessionsResults.first()
        }
    }

    override suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>> {
        sessionMessagesCalls++
        return messagesBySession[id] ?: ApiResult.Err(ApiError.Client(404, "session not found"))
    }

    override suspend fun createSession() = createSessionResult

    override suspend fun renameSession(id: String, title: String): ApiResult<Unit> {
        renameCalls += id to title
        return renameSessionResult
    }

    override suspend fun deleteSession(id: String): ApiResult<Unit> {
        deleteCalls += id
        return deleteSessionResult
    }

    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        chatCalls += id to message
        val script = when {
            chatScripts.isEmpty() -> emptyList()
            chatScripts.size > 1 -> chatScripts.removeFirst()
            else -> chatScripts.first()
        }
        for (item in script) {
            when (item) {
                is RunEvent -> emit(item)
                is Throwable -> throw item
                else -> error("bad script item $item")
            }
        }
    }

    fun runDto(status: String, output: String? = null) = RunDto(
        objectType = "hermes.run",
        runId = "run_1",
        status = status,
        sessionId = "sess_1",
        model = "hermes-agent",
        createdAt = 0.0,
        updatedAt = 1.0,
        lastEvent = null,
        output = output,
        usage = null,
    )
}

class FakeApiKeyStore(private var key: String? = null) : net.liquidx.leman.data.settings.ApiKeyStore {
    override suspend fun get(): String? = key
    override suspend fun set(value: String) {
        key = value
    }
    override suspend fun clear() {
        key = null
    }
}
