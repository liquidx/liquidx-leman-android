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

    /** Tear down and rebuild transport when base URL or key changes (spec 02). */
    fun reconfigure(baseUrl: String?, apiKey: String?)

    suspend fun capabilities(): ApiResult<CapabilitiesDto>
    suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto>
    suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>>
    suspend fun createSession(): ApiResult<SessionDto>
    suspend fun renameSession(id: String, title: String): ApiResult<Unit>
    suspend fun deleteSession(id: String): ApiResult<Unit>

    /**
     * Cold flow of a Sessions chat run's events (spec 02 §Sessions). Completes
     * normally when the server closes the stream; fails with
     * [HermesStreamException] carrying the mapped
     * [net.liquidx.leman.domain.model.ApiError] when the socket dies — callers
     * own reconnect policy (spec 02).
     */
    fun chatStream(id: String, message: String): Flow<RunEvent>
}

class HermesStreamException(val apiError: net.liquidx.leman.domain.model.ApiError) :
    Exception("run event stream failed: $apiError")
