package net.liquidx.leman.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Production [HermesClient] over OkHttp (spec 02 Transport). Timeouts: connect
 * 10s, read 30s REST / none on the event stream, write 15s; health probes use a
 * 5s call timeout so the status row settles fast (spec 04).
 */
class OkHttpHermesClient(
    private val userAgent: String,
    /** Debug hook: extra interceptors (network log, chaos — spec 08). */
    private val interceptors: List<okhttp3.Interceptor> = emptyList(),
) : HermesClient {

    private data class Transport(
        val baseUrl: HttpUrl,
        val apiKey: String,
        val rest: OkHttpClient,
        val stream: OkHttpClient,
        val probe: OkHttpClient,
    )

    @Volatile
    private var transport: Transport? = null

    private val jsonMediaType = "application/json".toMediaType()

    override fun reconfigure(baseUrl: String?, apiKey: String?) {
        val url = baseUrl?.toHttpUrlOrNull()
        if (url == null || apiKey.isNullOrBlank()) {
            transport?.close()
            transport = null
            return
        }
        // Nothing changed — keep the warm connection pool. configurePushClient()
        // calls this on every push and every registration; rebuilding would shut
        // down the executor and evict live connections each time.
        val current = transport
        if (current != null && current.baseUrl == url && current.apiKey == apiKey) return
        val rest = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .apply { interceptors.forEach(::addInterceptor) }
            .build()
        transport?.close()
        transport = Transport(
            baseUrl = url,
            apiKey = apiKey,
            rest = rest,
            stream = rest.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
            probe = rest.newBuilder().callTimeout(5, TimeUnit.SECONDS).build(),
        )
    }

    fun shutdown() {
        transport?.close()
        transport = null
    }

    private fun Transport.close() {
        rest.dispatcher.executorService.shutdown()
        rest.connectionPool.evictAll()
    }

    private fun Transport.request(
        path: String,
        accept: String = "application/json",
        query: List<Pair<String, String>> = emptyList(),
    ): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", accept)
            .header("User-Agent", userAgent)
    }

    override suspend fun health(): ApiResult<HealthDto> =
        get("v1/health", HealthDto.serializer()) { it.probe }

    override suspend fun models(): ApiResult<List<String>> =
        getRaw("v1/models") { body ->
            HermesJson.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }
                ?: emptyList()
        }

    override suspend fun capabilities(): ApiResult<CapabilitiesDto> =
        get("v1/capabilities", CapabilitiesDto.serializer()) { it.rest }

    override suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val request = t.request(
            "api/sessions",
            query = listOf("limit" to "$limit", "offset" to "$offset"),
        ).get().build()
        return execute(t.rest, request) { HermesJson.decodeFromString(SessionListDto.serializer(), it) }
    }

    override suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(t.rest, t.request("api/sessions/$id/messages").get().build()) {
            HermesJson.decodeFromString(SessionMessagesDto.serializer(), it).data
        }
    }

    override suspend fun createSession(): ApiResult<SessionDto> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val request = t.request("api/sessions").post("{}".toRequestBody(jsonMediaType)).build()
        return execute(t.rest, request) {
            HermesJson.decodeFromString(SessionEnvelopeDto.serializer(), it).session
        }
    }

    override suspend fun renameSession(id: String, title: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(SessionPatchDto.serializer(), SessionPatchDto(title))
        val request = t.request("api/sessions/$id").patch(body.toRequestBody(jsonMediaType)).build()
        // response body shape is unpinned upstream — success is all we need
        return execute(t.rest, request) { }
    }

    override suspend fun deleteSession(id: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(t.rest, t.request("api/sessions/$id").delete().build()) { }
    }

    override suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(
            DeviceRegistrationDto.serializer(),
            DeviceRegistrationDto(fcmToken, deviceId, "android"),
        )
        val request = t.request("api/devices").post(body.toRequestBody(jsonMediaType)).build()
        return execute(t.rest, request) { }
    }

    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        val t = transport ?: throw HermesStreamException(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(ChatRequestDto.serializer(), ChatRequestDto(message))
        val request = t.request("api/sessions/$id/chat/stream", accept = "text/event-stream")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = try {
            t.stream.newCall(request).execute()
        } catch (e: Exception) {
            throw HermesStreamException(mapException(e))
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw HermesStreamException(mapHttpFailure(resp))
            val source = resp.body?.source()
                ?: throw HermesStreamException(ApiError.Protocol("empty stream body"))
            try {
                for (frame in readSseFrames(source)) emit(parseChatEvent(frame))
            } catch (e: java.io.IOException) {
                throw HermesStreamException(mapException(e))
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        clientOf: (Transport) -> OkHttpClient,
    ): ApiResult<T> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(clientOf(t), t.request(path).get().build()) {
            HermesJson.decodeFromString(serializer, it)
        }
    }

    private suspend fun <T> getRaw(path: String, decode: (String) -> T): ApiResult<T> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(t.rest, t.request(path).get().build(), decode)
    }

    private suspend fun <T> execute(
        client: OkHttpClient,
        request: Request,
        decode: (String) -> T,
    ): ApiResult<T> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ApiResult.Err(mapHttpFailure(response))
                } else {
                    val body = response.body?.string().orEmpty()
                    try {
                        ApiResult.Ok(decode(body))
                    } catch (e: Exception) {
                        ApiResult.Err(ApiError.Protocol(e.message ?: "undecodable body"))
                    }
                }
            }
        } catch (e: Exception) {
            ApiResult.Err(mapException(e))
        }
    }
}
