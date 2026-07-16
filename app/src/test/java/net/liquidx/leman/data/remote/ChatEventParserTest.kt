package net.liquidx.leman.data.remote

import net.liquidx.leman.domain.model.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEventParserTest {
    private fun frame(event: String, data: String) = SseFrame(event, data)

    @Test
    fun runStarted_carriesRunId() {
        val e = parseChatEvent(frame("run.started", """{"run_id":"r1","seq":1,"ts":2.5}"""))
        assertEquals(RunEvent.RunStarted("r1", 2.5), e)
    }

    @Test
    fun assistantDelta_mapsToMessageDelta() {
        val e = parseChatEvent(frame("assistant.delta", """{"delta":"hi","ts":1.0}"""))
        assertEquals(RunEvent.MessageDelta("hi", 1.0), e)
    }

    @Test
    fun toolStarted_usesToolNameField_thinkingFiltered() {
        val started = parseChatEvent(frame("tool.started", """{"tool_name":"memory","preview":"+user","ts":1.0}"""))
        assertEquals(RunEvent.ToolStarted("memory", "+user", 1.0), started)
        val thinking = parseChatEvent(frame("tool.started", """{"tool_name":"_thinking","ts":1.0}"""))
        assertTrue(thinking is RunEvent.Unknown)
    }

    @Test
    fun toolProgress_thinking_isReasoningDelta_othersUnknown() {
        val r = parseChatEvent(frame("tool.progress", """{"tool_name":"_thinking","delta":"hm","ts":3.0}"""))
        assertEquals(RunEvent.ReasoningDelta("hm", 3.0), r)
        assertTrue(parseChatEvent(frame("tool.progress", """{"tool_name":"web","delta":"…","ts":3.0}""")) is RunEvent.Unknown)
    }

    @Test
    fun runCompleted_outputIsLastNonEmptyAssistantContent() {
        val data = """{"messages":[
            {"role":"assistant","content":"","tool_calls":[{"id":"c"}],"finish_reason":"tool_calls"},
            {"role":"tool","content":"{}","tool_name":"memory"},
            {"role":"assistant","content":"noted","finish_reason":"stop"}],
            "usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12},"ts":9.0}"""
        val e = parseChatEvent(frame("run.completed", data)) as RunEvent.RunCompleted
        assertEquals("noted", e.output)
        assertEquals(12, e.usage!!.totalTokens)
    }

    @Test
    fun malformed_orUnknownEvent_neverThrows() {
        assertTrue(parseChatEvent(frame("run.started", "{not json")) is RunEvent.Unknown)
        assertTrue(parseChatEvent(frame("message.started", """{"ts":1.0}""")) is RunEvent.Unknown)
        assertTrue(parseChatEvent(frame(null.toString(), "{}")) is RunEvent.Unknown)
    }
}
