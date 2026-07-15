package net.liquidx.leman.debug

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import net.liquidx.leman.data.remote.HealthDto
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.HermesStreamException
import net.liquidx.leman.data.remote.RunAcceptedDto
import net.liquidx.leman.data.remote.RunDto
import net.liquidx.leman.data.remote.WireMessage
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.TokenUsage

enum class FakeScenario { Demo, Streaming, NeedsYou, Hostile }

/**
 * In-process fake gateway (spec 08): implements [HermesClient] directly — no
 * socket — mirroring the real contract: async runs, `data:`-style event
 * vocabulary, full replay for finished runs, poll backstop.
 */
class FakeHermesServer : HermesClient {

    val scenario = MutableStateFlow(FakeScenario.Demo)

    private data class FakeRun(
        val id: String,
        val scenario: FakeScenario,
        val startedAt: Long,
        val events: List<RunEvent>,
        val output: String,
        val streamOpens: AtomicInteger = AtomicInteger(0),
    ) {
        /** Streaming scenario "runs" for its scripted duration; others complete instantly. */
        fun completed(now: Long): Boolean = when (scenario) {
            FakeScenario.Streaming -> now - startedAt > events.size * STREAM_DELAY_MS
            FakeScenario.Hostile -> streamOpens.get() >= 2
            else -> true
        }
    }

    private val runs = ConcurrentHashMap<String, FakeRun>()
    private val counter = AtomicInteger(0)

    override suspend fun health(): ApiResult<HealthDto> =
        ApiResult.Ok(HealthDto("ok", "hermes-agent (fake)", "0.18.0-fake"))

    override suspend fun models(): ApiResult<List<String>> = ApiResult.Ok(listOf("hermes-agent"))

    override suspend fun startRun(
        messages: List<WireMessage>,
        sessionId: String?,
    ): ApiResult<RunAcceptedDto> {
        val id = "fake-run-${counter.incrementAndGet()}"
        val userText = messages.lastOrNull()?.content.orEmpty()
        val current = scenario.value
        runs[id] = FakeRun(
            id = id,
            scenario = current,
            startedAt = System.currentTimeMillis(),
            events = scriptFor(current, userText),
            output = outputFor(current, userText),
        )
        return ApiResult.Ok(RunAcceptedDto(id, "started"))
    }

    override suspend fun getRun(id: String): ApiResult<RunDto> {
        val run = runs[id] ?: return ApiResult.Err(ApiError.Client(404, "run not found"))
        val done = run.completed(System.currentTimeMillis())
        return ApiResult.Ok(
            RunDto(
                objectType = "hermes.run",
                runId = run.id,
                status = if (done) "completed" else "running",
                sessionId = run.id,
                model = "hermes-agent",
                createdAt = run.startedAt / 1000.0,
                updatedAt = System.currentTimeMillis() / 1000.0,
                lastEvent = null,
                output = if (done) run.output else null,
                usage = null,
            ),
        )
    }

    override fun runEvents(id: String): Flow<RunEvent> = flow {
        val run = runs[id] ?: throw HermesStreamException(ApiError.Client(404, "run not found"))
        val opens = run.streamOpens.incrementAndGet()
        when (run.scenario) {
            FakeScenario.Streaming -> {
                // replays from the beginning, like the real gateway (spec 02)
                for (event in run.events) {
                    emit(event)
                    delay(STREAM_DELAY_MS)
                }
            }
            FakeScenario.Hostile -> {
                if (opens == 1) {
                    // drop mid-run before completion → exercises the poll backstop
                    for (event in run.events.take(run.events.size / 2)) {
                        emit(event)
                        delay(STREAM_DELAY_MS / 2)
                    }
                    throw HermesStreamException(ApiError.Network(IOException("hostile drop")))
                }
                for (event in run.events) emit(event)
            }
            else -> for (event in run.events) emit(event)
        }
    }

    override fun reconfigure(baseUrl: String?, apiKey: String?) = Unit

    private fun scriptFor(scenario: FakeScenario, userText: String): List<RunEvent> {
        val output = outputFor(scenario, userText)
        return when (scenario) {
            FakeScenario.Demo -> buildList {
                add(RunEvent.Reasoning("user asked: ${userText.take(80)} · planning steps", 1.0))
                add(RunEvent.ToolStarted("repo.search", "scan for relevant context", 2.0))
                add(RunEvent.ToolCompleted("repo.search", 3.4, false, 5.0))
                add(RunEvent.MessageDelta(output, 6.0))
                add(RunEvent.RunCompleted(output, TokenUsage(812, 344, 1156), 7.0))
            }

            FakeScenario.Streaming -> buildList {
                add(RunEvent.Reasoning("user reports intermittent failures on main · start from recent runs", 1.0))
                add(RunEvent.ToolStarted("ci.logs", "fetch last 20 runs of pipeline main", 2.0))
                add(RunEvent.ToolCompleted("ci.logs", 24.0, false, 3.0))
                add(RunEvent.ToolStarted("repo.search", "grep test_retry_backoff", 4.0))
                add(RunEvent.ToolCompleted("repo.search", 12.0, false, 5.0))
                SampleCorpus.ciDiagnosisMarkdown.chunked(24).forEachIndexed { i, chunk ->
                    add(RunEvent.MessageDelta(chunk, 6.0 + i * 0.1))
                }
                add(RunEvent.ToolStarted("monitor.add", "watch pipeline main for 48h", 20.0))
                add(RunEvent.ToolCompleted("monitor.add", 8.0, false, 21.0))
                add(RunEvent.RunCompleted(output, TokenUsage(812, 344, 1156), 22.0))
            }

            FakeScenario.NeedsYou -> buildList {
                add(RunEvent.Reasoning("found candidate trains · needs user choice", 1.0))
                add(RunEvent.ToolStarted("web_search", "trains to geneva friday morning", 2.0))
                add(RunEvent.ToolCompleted("web_search", 5.9, false, 8.0))
                add(RunEvent.MessageDelta(output, 9.0))
                add(RunEvent.RunCompleted(output, null, 10.0))
            }

            FakeScenario.Hostile -> buildList {
                add(RunEvent.Reasoning("𝖍𝖔𝖘𝖙𝖎𝖑𝖊 mode · exercising the defensive parser · العربية · 🎉", 1.0))
                repeat(25) { i ->
                    add(RunEvent.ToolStarted("tool.$i", "step $i args ".repeat(6), 2.0 + i))
                    add(RunEvent.ToolCompleted("tool.$i", i * 1.3, i % 5 == 0, 3.0 + i))
                }
                add(RunEvent.Unknown("""{"event":"totally.unknown","payload":"???"}""", 30.0))
                output.chunked(48).forEachIndexed { i, chunk ->
                    add(RunEvent.MessageDelta(chunk, 31.0 + i * 0.05))
                }
                add(RunEvent.RunCompleted(output, TokenUsage(9001, 4242, 13243), 40.0))
            }
        }
    }

    private fun outputFor(scenario: FakeScenario, userText: String): String = when (scenario) {
        FakeScenario.Demo -> "done. *${userText.take(60).ifBlank { "your task" }}* is handled — summary:\n\n- looked at the relevant context\n- did the thing\n- verified it twice\n\nanything else?"
        FakeScenario.Streaming -> SampleCorpus.ciDiagnosisMarkdown
        FakeScenario.NeedsYou -> SampleCorpus.genevaOptionsMarkdown
        FakeScenario.Hostile -> buildString {
            append("hostile output with **everything**: `inline`, [links](https://example.com), emoji 🎉, rtl العربية.\n\n")
            append("```diff\n@@ -1,3 +1,300 @@\n")
            repeat(150) { append("-old line $it\n+new line $it\n") }
            append("```\n\nsurvived.")
        }
    }

    private companion object {
        const val STREAM_DELAY_MS = 300L
    }
}

/** Runtime real↔mock switch for the GATEWAY section (spec 08). */
class SwitchableHermesClient(
    val real: HermesClient,
    val fake: FakeHermesServer,
    private val chaos: ChaosState,
    private val bus: DebugLogBus,
) : HermesClient {

    val useFake = MutableStateFlow(false)

    private val active: HermesClient get() = if (useFake.value) fake else real

    override suspend fun health() = active.health()
    override suspend fun models() = active.models()
    override suspend fun startRun(messages: List<WireMessage>, sessionId: String?) =
        active.startRun(messages, sessionId)

    override suspend fun getRun(id: String) = active.getRun(id)

    override fun runEvents(id: String): Flow<RunEvent> = flow {
        var count = 0
        active.runEvents(id).collect { event ->
            bus.logEvent(event.eventName(), event.timestamp, event.payloadPreview())
            if (chaos.flags.value.dropStream && ++count >= 5) {
                throw HermesStreamException(ApiError.Network(IOException("chaos stream drop")))
            }
            emit(event)
        }
    }

    override fun reconfigure(baseUrl: String?, apiKey: String?) = real.reconfigure(baseUrl, apiKey)
}

fun RunEvent.eventName(): String = when (this) {
    is RunEvent.MessageDelta -> "message.delta"
    is RunEvent.Reasoning -> "reasoning.available"
    is RunEvent.ToolStarted -> "tool.started"
    is RunEvent.ToolCompleted -> "tool.completed"
    is RunEvent.RunCompleted -> "run.completed"
    is RunEvent.Unknown -> "unknown"
}

fun RunEvent.payloadPreview(): String = when (this) {
    is RunEvent.MessageDelta -> delta.take(80)
    is RunEvent.Reasoning -> text.take(80)
    is RunEvent.ToolStarted -> "$tool · ${preview.orEmpty().take(60)}"
    is RunEvent.ToolCompleted -> "$tool · ${"%.1f".format(duration)}s${if (error) " · error" else ""}"
    is RunEvent.RunCompleted -> "output ${output.length} chars"
    is RunEvent.Unknown -> raw.take(80)
}
