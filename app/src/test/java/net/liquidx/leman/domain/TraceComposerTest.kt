package net.liquidx.leman.domain

import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.TraceStepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceComposerTest {

    @Test
    fun composeTrace_foldsReasoningAndToolPairsInOrder() {
        val trace = composeTrace(
            listOf(
                RunEvent.Reasoning("user wants ci fixed", 1.0),
                RunEvent.ToolStarted("ci.logs", "fetch last 20 runs", 2.0),
                RunEvent.ToolCompleted("ci.logs", 5.9, false, 8.0),
                RunEvent.ToolStarted("repo.search", "grep flaky", 9.0),
                RunEvent.ToolCompleted("repo.search", 3.2, true, 12.0),
                RunEvent.MessageDelta("ignored by trace", 13.0),
            ),
        )!!
        assertEquals(3, trace.steps.size)
        assertEquals(TraceStepKind.Reasoning, trace.steps[0].kind)
        assertEquals("user wants ci fixed", trace.steps[0].summary)
        assertEquals("ci.logs", trace.steps[1].tool)
        assertEquals(5.9, trace.steps[1].durationSeconds, 0.001)
        assertTrue(trace.steps[2].error)
    }

    @Test
    fun composeTrace_pairsRepeatedToolCallsInFifoOrder() {
        val trace = composeTrace(
            listOf(
                RunEvent.ToolStarted("web_search", "first", 1.0),
                RunEvent.ToolStarted("web_search", "second", 2.0),
                RunEvent.ToolCompleted("web_search", 1.5, false, 3.0),
                RunEvent.ToolCompleted("web_search", 2.5, true, 4.0),
            ),
        )!!
        assertEquals("first", trace.steps[0].summary)
        assertEquals(1.5, trace.steps[0].durationSeconds, 0.001)
        assertEquals("second", trace.steps[1].summary)
        assertTrue(trace.steps[1].error)
    }

    @Test
    fun composeTrace_unmatchedStart_keepsStepWithZeroDuration() {
        val trace = composeTrace(listOf(RunEvent.ToolStarted("web_search", "q", 1.0)))!!
        assertEquals(1, trace.steps.size)
        assertEquals(0.0, trace.steps[0].durationSeconds, 0.0)
    }

    @Test
    fun composeTrace_completedWithoutStart_stillProducesStep() {
        val trace = composeTrace(listOf(RunEvent.ToolCompleted("ci.logs", 2.0, false, 1.0)))!!
        assertEquals("ci.logs", trace.steps[0].tool)
        assertEquals(2.0, trace.steps[0].durationSeconds, 0.001)
    }

    @Test
    fun composeTrace_noTraceEvents_returnsNull() {
        assertNull(composeTrace(listOf(RunEvent.MessageDelta("hi", 1.0))))
        assertNull(composeTrace(emptyList()))
    }

    @Test
    fun rollup_countsStepsHistogramTop2AndDuration() {
        val trace = composeTrace(
            listOf(
                RunEvent.Reasoning("think", 0.0),
                RunEvent.ToolStarted("ci.logs", null, 1.0), RunEvent.ToolCompleted("ci.logs", 60.0, false, 2.0),
                RunEvent.ToolStarted("ci.logs", null, 3.0), RunEvent.ToolCompleted("ci.logs", 60.0, false, 4.0),
                RunEvent.ToolStarted("ci.logs", null, 5.0), RunEvent.ToolCompleted("ci.logs", 30.0, false, 6.0),
                RunEvent.ToolStarted("repo.search", null, 7.0), RunEvent.ToolCompleted("repo.search", 21.0, false, 8.0),
                RunEvent.ToolStarted("repo.search", null, 9.0), RunEvent.ToolCompleted("repo.search", 21.0, false, 10.0),
                RunEvent.ToolStarted("monitor.add", null, 11.0), RunEvent.ToolCompleted("monitor.add", 0.5, false, 12.0),
            ),
        )!!
        // 9 steps: 1 reasoning + 6 ci.logs/repo.search pairs folded to 5 tool steps + 1 monitor.add
        assertEquals("trace · 7 steps · ci.logs ×3 · repo.search ×2 · 3m 12s", trace.rollupText())
    }

    @Test
    fun rollup_singleToolSingleUse_omitsCountMarker() {
        val trace = composeTrace(
            listOf(
                RunEvent.ToolStarted("web_search", null, 1.0),
                RunEvent.ToolCompleted("web_search", 5.9, false, 2.0),
            ),
        )!!
        assertEquals("trace · 1 step · web_search · 5.9s", trace.rollupText())
    }

    @Test
    fun rollup_reasoningOnly_hasNoHistogramOrDuration() {
        val trace = composeTrace(listOf(RunEvent.Reasoning("thought", 1.0)))!!
        assertEquals("trace · 1 step", trace.rollupText())
    }

    @Test
    fun rollup_secondsUnderSixty_formatWholeSeconds() {
        val trace = composeTrace(
            listOf(
                RunEvent.ToolStarted("a.b", null, 1.0),
                RunEvent.ToolCompleted("a.b", 42.4, false, 2.0),
            ),
        )!!
        assertEquals("trace · 1 step · a.b · 42s", trace.rollupText())
    }
}
