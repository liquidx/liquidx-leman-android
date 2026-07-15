package net.liquidx.leman.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.liquidx.leman.data.remote.HealthDto
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.RunAcceptedDto
import net.liquidx.leman.data.remote.RunDto
import net.liquidx.leman.data.remote.WireMessage
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
