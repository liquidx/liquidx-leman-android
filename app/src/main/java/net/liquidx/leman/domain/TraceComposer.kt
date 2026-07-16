package net.liquidx.leman.domain

import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind

/**
 * Folds a run's events into a trace (spec 05): each `tool.started`/`tool.completed`
 * pair is one tool step (FIFO-matched per tool name); `reasoning.available`
 * becomes a reasoning step. Returns null when the run produced no trace events.
 */
fun composeTrace(events: List<RunEvent>): Trace? {
    val steps = mutableListOf<TraceStep>()
    val pendingByTool = mutableMapOf<String, ArrayDeque<Pair<Int, Double>>>()
    val reasoningBuffer = StringBuilder()

    fun flushReasoning() {
        val text = reasoningBuffer.toString().trim()
        if (text.isNotEmpty()) steps += TraceStep(TraceStepKind.Reasoning, summary = text)
        reasoningBuffer.clear()
    }

    for (event in events) {
        when (event) {
            is RunEvent.ReasoningDelta -> reasoningBuffer.append(event.delta)

            is RunEvent.Reasoning -> {
                flushReasoning()
                steps += TraceStep(TraceStepKind.Reasoning, summary = event.text)
            }

            is RunEvent.ToolStarted -> {
                flushReasoning()
                steps += TraceStep(TraceStepKind.Tool, tool = event.tool, summary = event.preview)
                pendingByTool.getOrPut(event.tool) { ArrayDeque() }
                    .addLast(steps.lastIndex to event.timestamp)
            }

            is RunEvent.ToolCompleted -> {
                flushReasoning()
                val pending = pendingByTool[event.tool]?.removeFirstOrNull()
                // chat frames carry no duration; fall back to the started→completed gap
                fun durationOf(startedAt: Double): Double =
                    if (event.duration > 0.0) event.duration
                    else (event.timestamp - startedAt).coerceAtLeast(0.0)
                if (pending != null) {
                    val (index, startedAt) = pending
                    steps[index] = steps[index].copy(
                        durationSeconds = durationOf(startedAt),
                        error = event.error,
                    )
                } else {
                    steps += TraceStep(
                        TraceStepKind.Tool,
                        tool = event.tool,
                        durationSeconds = event.duration,
                        error = event.error,
                    )
                }
            }

            else -> Unit // deltas/started/completion/unknown are not trace material
        }
    }
    flushReasoning()
    return if (steps.isEmpty()) null else Trace(steps)
}

/**
 * Collapsed-line rollup (spec 05): `trace · 6 steps · web_search ×4 · repo.read ×2 · 3m 12s`
 * — histogram desc by count (top 2 tools), duration = sum of step durations.
 * The `▸` arrow is rendered by the component, not included here.
 */
fun Trace.rollupText(): String {
    val parts = mutableListOf("trace", if (steps.size == 1) "1 step" else "${steps.size} steps")

    val histogram = steps.filter { it.kind == TraceStepKind.Tool }
        .groupingBy { it.tool.orEmpty() }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(2)
    for ((tool, count) in histogram) {
        parts += if (count > 1) "$tool ×$count" else tool
    }

    val totalSeconds = steps.sumOf { it.durationSeconds }
    if (totalSeconds > 0) parts += formatDuration(totalSeconds)

    return parts.joinToString(" · ")
}

fun formatDuration(seconds: Double): String {
    val whole = seconds.toLong()
    return when {
        whole >= 60 -> "${whole / 60}m ${whole % 60}s"
        seconds < 10 -> "%.1fs".format(seconds)
        else -> "${whole}s"
    }
}
