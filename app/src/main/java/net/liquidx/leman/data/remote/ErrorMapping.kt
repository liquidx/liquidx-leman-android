package net.liquidx.leman.data.remote

import net.liquidx.leman.domain.model.ApiError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Response

/** Everything thrown by OkHttp/serialization maps into [ApiError] (spec 04). */
fun mapException(e: Exception): ApiError = when (e) {
    is SocketTimeoutException -> ApiError.Timeout
    is InterruptedIOException -> ApiError.Timeout // OkHttp call timeouts
    is IOException -> ApiError.Network(e)
    is kotlinx.serialization.SerializationException -> ApiError.Protocol(e.message ?: "bad payload")
    is IllegalArgumentException -> ApiError.Protocol(e.message ?: "bad payload")
    else -> ApiError.Protocol(e.message ?: e.javaClass.simpleName)
}

/** 401/403 → Auth; 5xx and 429 → Server (retryable per 04); other 4xx → Client. */
fun mapHttpFailure(response: Response): ApiError {
    val code = response.code
    // `/v1/*` wraps errors as {"error":{"message":…}}; `/api/jobs` uses a bare
    // {"error":"…"} string (e.g. the invalid-schedule 500 whose message the edit
    // screen surfaces). Accept both.
    val message = runCatching {
        response.body?.string()?.let { body ->
            when (val error = HermesJson.parseToJsonElement(body).jsonObject["error"]) {
                is JsonPrimitive -> error.contentOrNull
                is JsonObject -> HermesJson.decodeFromJsonElement(ErrorBodyDto.serializer(), error).message
                else -> null
            }
        }
    }.getOrNull()
    return when {
        code == 401 || code == 403 -> ApiError.Auth(code)
        code >= 500 || code == 429 -> ApiError.Server(code, message)
        else -> ApiError.Client(code, message)
    }
}
