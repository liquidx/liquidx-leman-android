package net.liquidx.leman.data.repo

import net.liquidx.leman.data.local.decodeTrace
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.data.remote.ToolCallDto
import net.liquidx.leman.data.remote.ToolCallFunctionDto
import net.liquidx.leman.domain.model.TraceStepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTurnsTest {
    private fun msg(
        id: Long, role: String, content: String? = null,
        toolCalls: List<ToolCallDto>? = null, reasoning: String? = null,
        ts: Double = 100.0, finish: String? = null,
    ) = SessionMessageDto(id, role, content, toolCalls, null, reasoning, ts, finish)

    @Test
    fun plainConversation_mapsUserAndAgentTurns() {
        val turns = sessionTurns("s1", listOf(
            msg(1, "user", "hi", ts = 10.0),
            msg(2, "assistant", "hello", ts = 11.0, finish = "stop"),
        ))
        assertEquals(listOf("user", "agent"), turns.map { it.kind })
        assertEquals("msg-s1-1", turns[0].id)
        assertEquals(10_000L, turns[0].createdAt)
        assertEquals(listOf(1L, 2L), turns.map { it.seq })
        assertTrue(turns.all { it.sendState == "synced" })
    }

    @Test
    fun toolCallsAndReasoning_composeTraceTurn_beforeAgentTurn() {
        val turns = sessionTurns("s1", listOf(
            msg(1, "user", "do it"),
            msg(2, "assistant", "", reasoning = "planning",
                toolCalls = listOf(ToolCallDto(ToolCallFunctionDto("memory", """{"a":1}"""))),
                finish = "tool_calls"),
            msg(3, "tool", """{"ok":true}"""),
            msg(4, "assistant", "done", finish = "stop"),
        ))
        assertEquals(listOf("user", "trace", "agent"), turns.map { it.kind })
        val trace = decodeTrace(turns[1].traceJson!!)!!
        assertEquals(listOf(TraceStepKind.Reasoning, TraceStepKind.Tool), trace.steps.map { it.kind })
        assertEquals("memory", trace.steps[1].tool)
        assertEquals("trace-s1-4", turns[1].id)
        assertEquals("msg-s1-4", turns[2].id)
    }

    @Test
    fun trailingToolWork_withoutFinalAssistant_flushesTailTrace() {
        val turns = sessionTurns("s1", listOf(
            msg(1, "user", "go"),
            msg(2, "assistant", "", toolCalls = listOf(ToolCallDto(ToolCallFunctionDto("web", "{}")))),
        ))
        assertEquals(listOf("user", "trace"), turns.map { it.kind })
        assertEquals("trace-s1-tail", turns[1].id)
    }

    @Test
    fun rerun_isIdempotent() {
        val messages = listOf(msg(1, "user", "hi"), msg(2, "assistant", "yo", finish = "stop"))
        assertEquals(sessionTurns("s1", messages), sessionTurns("s1", messages))
    }

    @Test
    fun importantPrefixed_userMessage_mapsToSystemKind() {
        val turns = sessionTurns(
            "s1",
            listOf(
                msg(1, "user", "[IMPORTANT: You are running as a scheduled cron job. DELIVERY: ...]"),
                msg(2, "assistant", "digest sent", finish = "stop"),
            ),
        )
        assertEquals(listOf("system", "agent"), turns.map { it.kind })
        assertEquals("msg-s1-1", turns[0].id) // id scheme unchanged by the kind switch
        assertEquals("synced", turns[0].sendState)
    }

    @Test
    fun systemPrefixed_userMessage_mapsToSystemKind() {
        val turns = sessionTurns(
            "s1",
            listOf(
                msg(1, "user", "[System: The previous response was cut off by a network error mid-stream.]"),
            ),
        )
        assertEquals(listOf("system"), turns.map { it.kind })
    }

    @Test
    fun bracketedButNotAPreamble_userMessage_staysUserKind() {
        val turns = sessionTurns(
            "s1",
            listOf(
                msg(1, "user", "[link] check this out"),
                msg(2, "user", "plain text message", ts = 11.0),
            ),
        )
        assertEquals(listOf("user", "user"), turns.map { it.kind })
    }
}
