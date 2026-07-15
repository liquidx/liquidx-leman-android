package net.liquidx.leman.data.remote

import net.liquidx.leman.domain.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEventParserTest {

    @Test
    fun parse_messageDelta_returnsDeltaAndTimestamp() {
        val event = parseRunEvent("""{"event":"message.delta","run_id":"r","timestamp":2.5,"delta":"hi"}""")
        assertEquals(RunEvent.MessageDelta("hi", 2.5), event)
    }

    @Test
    fun parse_reasoningAvailable_returnsText() {
        val event = parseRunEvent("""{"event":"reasoning.available","timestamp":1.0,"text":"thinking"}""")
        assertEquals(RunEvent.Reasoning("thinking", 1.0), event)
    }

    @Test
    fun parse_toolStarted_carriesPreview() {
        val event = parseRunEvent("""{"event":"tool.started","timestamp":1.0,"tool":"web_search","preview":"query"}""")
        assertEquals(RunEvent.ToolStarted("web_search", "query", 1.0), event)
    }

    @Test
    fun parse_toolStarted_missingPreview_isNull() {
        val event = parseRunEvent("""{"event":"tool.started","timestamp":1.0,"tool":"web_search"}""")
        assertEquals(RunEvent.ToolStarted("web_search", null, 1.0), event)
    }

    @Test
    fun parse_toolCompleted_carriesDurationAndError() {
        val event = parseRunEvent("""{"event":"tool.completed","timestamp":9.0,"tool":"ci.logs","duration":5.939,"error":true}""")
        assertEquals(RunEvent.ToolCompleted("ci.logs", 5.939, true, 9.0), event)
    }

    @Test
    fun parse_runCompleted_carriesOutputAndUsage() {
        val event = parseRunEvent(
            """{"event":"run.completed","timestamp":20.0,"output":"done","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}""",
        ) as RunEvent.RunCompleted
        assertEquals("done", event.output)
        assertEquals(3, event.usage?.totalTokens)
    }

    @Test
    fun parse_unknownEventName_mapsToUnknown() {
        val raw = """{"event":"totally.new","timestamp":5.0}"""
        val event = parseRunEvent(raw)
        assertTrue(event is RunEvent.Unknown)
        assertEquals(5.0, (event as RunEvent.Unknown).timestamp, 0.0)
    }

    @Test
    fun parse_malformedJson_mapsToUnknownNotCrash() {
        val event = parseRunEvent("not-even-json")
        assertTrue(event is RunEvent.Unknown)
        assertEquals("not-even-json", (event as RunEvent.Unknown).raw)
    }

    @Test
    fun parse_eventMissingRequiredField_mapsToUnknown() {
        // message.delta without a delta field must not crash the stream.
        val event = parseRunEvent("""{"event":"message.delta","timestamp":1.0}""")
        assertTrue(event is RunEvent.Unknown)
    }
}
