package net.liquidx.leman.data.remote

import okio.BufferedSource

/** One chat-stream frame: `event:` name (null when absent) + `data:` payload. */
data class SseFrame(val event: String?, val data: String)

/**
 * Reads the Sessions chat stream's standard-ish framing (spec 02): `event:` +
 * `data:` line pairs separated by blank lines. A blank line resets the pending
 * event name so a dangling `event:` never leaks onto a later frame.
 */
fun readSseFrames(source: BufferedSource): Sequence<SseFrame> = sequence {
    var event: String? = null
    while (true) {
        val line = try {
            source.readUtf8Line() ?: break
        } catch (_: java.io.EOFException) {
            break
        }
        val trimmed = line.removeSuffix("\r")
        when {
            trimmed.isEmpty() -> event = null
            trimmed.startsWith("event:") -> event = trimmed.removePrefix("event:").trim()
            trimmed.startsWith("data:") -> yield(SseFrame(event, trimmed.removePrefix("data:").trimStart()))
            else -> Unit // comments and anything unrecognized
        }
    }
}
