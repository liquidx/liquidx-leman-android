package net.liquidx.leman.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonObject
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

/**
 * Decodes one Sessions chat-stream frame into a [RunEvent] (spec 02). The type
 * is the SSE `event:` name; `ts` is the timestamp; reasoning streams as the
 * `_thinking` pseudo-tool. Malformed/unrecognized frames become [RunEvent.Unknown].
 */
fun parseChatEvent(frame: SseFrame): RunEvent {
    return try {
        val obj = Json.parseToJsonElement(frame.data).jsonObject
        val ts = obj["ts"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        fun toolName() = obj["tool_name"]?.jsonPrimitive?.contentOrNull
        when (frame.event) {
            "run.started" -> RunEvent.RunStarted(obj.requireString("run_id"), ts)
            "assistant.delta" -> RunEvent.MessageDelta(obj.requireString("delta"), ts)
            "tool.started" -> when (val tool = toolName()) {
                null, "_thinking" -> RunEvent.Unknown(frame.data, ts)
                else -> RunEvent.ToolStarted(tool, obj["preview"]?.jsonPrimitive?.contentOrNull, ts)
            }
            "tool.progress" -> when (toolName()) {
                "_thinking" -> RunEvent.ReasoningDelta(
                    obj["delta"]?.jsonPrimitive?.contentOrNull.orEmpty(), ts,
                )
                else -> RunEvent.Unknown(frame.data, ts)
            }
            "tool.completed" -> when (val tool = toolName()) {
                null, "_thinking" -> RunEvent.Unknown(frame.data, ts)
                // chat frames carry no duration/error; the composer derives duration from timestamps
                else -> RunEvent.ToolCompleted(tool, duration = 0.0, error = false, timestamp = ts)
            }
            "assistant.completed" -> RunEvent.AssistantCompleted(
                content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                interrupted = obj["interrupted"]?.jsonPrimitive?.booleanOrNull ?: false,
                timestamp = ts,
            )
            "run.completed" -> RunEvent.RunCompleted(
                output = obj["messages"]?.jsonArray
                    ?.mapNotNull { it as? JsonObject }
                    ?.lastOrNull {
                        it["role"]?.jsonPrimitive?.contentOrNull == "assistant" &&
                            !it["content"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()
                    }
                    ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty(),
                usage = obj["usage"]?.let {
                    HermesJson.decodeFromJsonElement(UsageDto.serializer(), it)
                        .let { u -> TokenUsage(u.inputTokens, u.outputTokens, u.totalTokens) }
                },
                timestamp = ts,
            )
            else -> RunEvent.Unknown(frame.data, ts)
        }
    } catch (_: IllegalArgumentException) {
        RunEvent.Unknown(frame.data)
    } catch (_: kotlinx.serialization.SerializationException) {
        RunEvent.Unknown(frame.data)
    }
}
