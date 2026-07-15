package net.liquidx.leman.data.remote

import okio.BufferedSource

/**
 * Reads the gateway's non-standard `data:`-only event framing (spec 02): no
 * `id:`/`event:`/`retry:` lines, one JSON payload per `data:` line, comment
 * lines start with `:` (the stream ends with `: stream closed`). Standard SSE
 * multi-line data accumulation is deliberately not implemented — the server
 * never emits it, and a hand-rolled reader stays unit-testable.
 */
fun readSseDataFrames(source: BufferedSource): Sequence<String> = sequence {
    while (true) {
        val line = try {
            source.readUtf8Line() ?: break
        } catch (_: java.io.EOFException) {
            break
        }
        val trimmed = line.removeSuffix("\r")
        when {
            trimmed.isEmpty() -> Unit
            trimmed.startsWith("data:") -> yield(trimmed.removePrefix("data:").trimStart())
            else -> Unit // comments and anything unrecognized
        }
    }
}
