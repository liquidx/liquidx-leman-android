package net.liquidx.leman.debug

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Debug-only OkHttp interceptor (spec 08): network log ring buffer + chaos
 * fault injection. The Authorization header is always masked — the key never
 * appears in any log path.
 */
class DebugInterceptor(
    private val bus: DebugLogBus,
    private val chaos: ChaosState,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val flags = chaos.flags.value
        if (flags.extraLatencyMs > 0) Thread.sleep(flags.extraLatencyMs)

        val requestBody = request.body?.let {
            val buffer = Buffer()
            it.writeTo(buffer)
            buffer.readUtf8()
        }
        val curl = buildString {
            append("curl -X ").append(request.method)
            request.headers.forEach { (name, value) ->
                val shown = if (name.equals("Authorization", true)) {
                    "Bearer " + net.liquidx.leman.util.LemanLog.maskKey(value.removePrefix("Bearer "))
                } else {
                    value
                }
                append(" -H '").append(name).append(": ").append(shown).append("'")
            }
            requestBody?.let { append(" -d '").append(it.replace("'", "\\'")).append("'") }
            append(" '").append(request.url).append("'")
        }

        val start = System.nanoTime()
        val response: Response = if (chaos.consumeFailToken()) {
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("chaos")
                .body("""{"error":{"message":"chaos-injected failure"}}""".toResponseBody())
                .build()
        } else {
            chain.proceed(request)
        }
        val durationMs = (System.nanoTime() - start) / 1_000_000

        // Only peek non-stream bodies; the event stream must keep streaming.
        val isStream = response.header("Content-Type")?.contains("event-stream") == true
        var loggedBody: String? = null
        var result = response
        if (!isStream) {
            val peeked = response.peekBody(64_000).string()
            loggedBody = peeked
            if (chaos.consumeCorruptToken()) {
                result = response.newBuilder()
                    .body(("garbage∅" + peeked.reversed()).toResponseBody(response.body?.contentType()))
                    .build()
            }
        }

        bus.logNet { id ->
            NetLogEntry(
                id = id,
                method = request.method,
                path = request.url.encodedPath,
                status = result.code,
                durationMs = durationMs,
                requestBytes = requestBody?.length?.toLong() ?: 0,
                responseBytes = loggedBody?.length?.toLong() ?: -1,
                requestBody = requestBody,
                responseBody = loggedBody,
                curl = curl,
            )
        }
        return result
    }
}
