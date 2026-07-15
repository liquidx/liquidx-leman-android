package net.liquidx.leman.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.TokenUsage

/**
 * Decodes one `data:` payload into a [RunEvent]. The event type is a field
 * inside the JSON (`.event`) — spec 02. Anything malformed or unrecognized
 * becomes [RunEvent.Unknown]; a bad frame must never kill the stream.
 */
fun parseRunEvent(payload: String): RunEvent {
    return try {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val timestamp = obj["timestamp"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        when (obj["event"]?.jsonPrimitive?.contentOrNull) {
            "message.delta" -> RunEvent.MessageDelta(
                delta = obj.requireString("delta"),
                timestamp = timestamp,
            )
            "reasoning.available" -> RunEvent.Reasoning(
                text = obj.requireString("text"),
                timestamp = timestamp,
            )
            "tool.started" -> RunEvent.ToolStarted(
                tool = obj.requireString("tool"),
                preview = obj["preview"]?.jsonPrimitive?.contentOrNull,
                timestamp = timestamp,
            )
            "tool.completed" -> RunEvent.ToolCompleted(
                tool = obj.requireString("tool"),
                duration = obj["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                error = obj["error"]?.jsonPrimitive?.booleanOrNull ?: false,
                timestamp = timestamp,
            )
            "run.completed" -> RunEvent.RunCompleted(
                output = obj.requireString("output"),
                usage = obj["usage"]?.let { usage ->
                    HermesJson.decodeFromJsonElement(UsageDto.serializer(), usage)
                        .let { TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens) }
                },
                timestamp = timestamp,
            )
            else -> RunEvent.Unknown(payload, timestamp)
        }
    } catch (_: IllegalArgumentException) {
        RunEvent.Unknown(payload)
    } catch (_: kotlinx.serialization.SerializationException) {
        RunEvent.Unknown(payload)
    }
}

private fun kotlinx.serialization.json.JsonObject.requireString(key: String): String =
    requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "missing $key" }
