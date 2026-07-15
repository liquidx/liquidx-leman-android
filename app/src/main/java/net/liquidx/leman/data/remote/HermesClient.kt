package net.liquidx.leman.data.remote

import kotlinx.coroutines.flow.Flow
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent

/**
 * The only surface that knows the Hermes wire format (spec 01/02). Implemented
 * by [OkHttpHermesClient] in production and the in-process fake in debug/tests.
 */
interface HermesClient {
    suspend fun health(): ApiResult<HealthDto>
    suspend fun models(): ApiResult<List<String>>
    suspend fun startRun(messages: List<WireMessage>, sessionId: String?): ApiResult<RunAcceptedDto>
    suspend fun getRun(id: String): ApiResult<RunDto>

    /**
     * Cold flow of a run's events. Completes normally when the server closes the
     * stream; fails with [HermesStreamException] carrying the mapped [net.liquidx.leman.domain.model.ApiError]
     * when the socket dies — callers own reconnect policy (spec 02).
     */
    fun runEvents(id: String): Flow<RunEvent>

    /** Tear down and rebuild transport when base URL or key changes (spec 02). */
    fun reconfigure(baseUrl: String?, apiKey: String?)
}

class HermesStreamException(val apiError: net.liquidx.leman.domain.model.ApiError) :
    Exception("run event stream failed: $apiError")
