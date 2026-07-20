package net.liquidx.leman.debug

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import net.liquidx.leman.data.remote.CapabilitiesDto
import net.liquidx.leman.data.remote.HealthDto
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.HermesStreamException
import net.liquidx.leman.data.remote.JobCreateDto
import net.liquidx.leman.data.remote.JobDto
import net.liquidx.leman.data.remote.JobPatchDto
import net.liquidx.leman.data.remote.JobScheduleDto
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.data.remote.SessionListDto
import net.liquidx.leman.data.remote.SessionMessageDto
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

    /** Shared id namespace for synthetic run ids surfaced on [RunEvent.RunStarted]. */
    private val counter = AtomicInteger(0)

    /** Mutable holder so seed + chat traffic can update a session's dto/messages in place. */
    private class SessionState(dto: SessionDto) {
        @Volatile var dto: SessionDto = dto
        val messages = CopyOnWriteArrayList<SessionMessageDto>()

        fun touch(atMs: Long, messageCount: Int) {
            dto = dto.copy(lastActive = atMs / 1000.0, messageCount = messageCount)
        }
    }

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val messageIdSeq = AtomicLong(1)
    private val sessionApiCounter = AtomicInteger(0)

    init {
        val nowMs = System.currentTimeMillis()
        val hour = 3_600_000L
        val minute = 60_000L

        // (1) cron digest: source "cron", a single assistant message, no user turn.
        val cronAtMs = nowMs - 20 * hour
        sessions["fake-cron-1"] = SessionState(
            SessionDto(
                id = "fake-cron-1",
                source = "cron",
                title = "daily digest",
                startedAt = cronAtMs / 1000.0,
                lastActive = cronAtMs / 1000.0,
                messageCount = 1,
            ),
        ).also { state ->
            state.messages.add(
                SessionMessageDto(
                    id = messageIdSeq.getAndIncrement(),
                    role = "assistant",
                    content = SampleCorpus.ciDiagnosisMarkdown,
                    timestamp = cronAtMs / 1000.0,
                ),
            )
        }

        // (2) api_server conversation from the geneva/needs-you corpus.
        val genevaUserAtMs = nowMs - 3 * hour - 4 * minute
        val genevaAssistantAtMs = nowMs - 3 * hour
        sessions["fake-session-2"] = SessionState(
            SessionDto(
                id = "fake-session-2",
                source = "api_server",
                startedAt = genevaUserAtMs / 1000.0,
                lastActive = genevaAssistantAtMs / 1000.0,
                messageCount = 2,
            ),
        ).also { state ->
            state.messages.add(
                SessionMessageDto(
                    id = messageIdSeq.getAndIncrement(),
                    role = "user",
                    content = "book me a train to geneva next friday morning, under 80 chf if possible",
                    timestamp = genevaUserAtMs / 1000.0,
                ),
            )
            state.messages.add(
                SessionMessageDto(
                    id = messageIdSeq.getAndIncrement(),
                    role = "assistant",
                    content = SampleCorpus.genevaOptionsMarkdown,
                    timestamp = genevaAssistantAtMs / 1000.0,
                ),
            )
        }

        // (3) empty, just-created session — nothing sent yet.
        val freshAtMs = nowMs - minute
        sessions["fake-session-3"] = SessionState(
            SessionDto(
                id = "fake-session-3",
                source = "api_server",
                startedAt = freshAtMs / 1000.0,
                lastActive = freshAtMs / 1000.0,
                messageCount = 0,
            ),
        )
    }

    override suspend fun health(): ApiResult<HealthDto> =
        ApiResult.Ok(HealthDto("ok", "hermes-agent (fake)", "0.18.0-fake"))

    override suspend fun models(): ApiResult<List<String>> = ApiResult.Ok(listOf("hermes-agent"))

    override fun reconfigure(baseUrl: String?, apiKey: String?) = Unit

    /** Both session flags on — the fake always looks like a fully-capable gateway. */
    override suspend fun capabilities(): ApiResult<CapabilitiesDto> = ApiResult.Ok(
        CapabilitiesDto(
            version = "0.18.0-fake",
            features = mapOf(
                "session_resources" to JsonPrimitive(true),
                "session_chat_streaming" to JsonPrimitive(true),
            ),
        ),
    )

    override suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto> {
        val all = sessions.values.map { it.dto }.sortedByDescending { it.lastActive }
        val page = all.drop(offset).take(limit)
        return ApiResult.Ok(SessionListDto(data = page, hasMore = offset + page.size < all.size))
    }

    override suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>> {
        val state = sessions[id] ?: return ApiResult.Err(ApiError.Client(404, "session not found"))
        return ApiResult.Ok(state.messages.toList())
    }

    override suspend fun createSession(): ApiResult<SessionDto> {
        val id = "fake-api-${sessionApiCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        val dto = SessionDto(
            id = id,
            source = "api_server",
            startedAt = now / 1000.0,
            lastActive = now / 1000.0,
            messageCount = 0,
        )
        sessions[id] = SessionState(dto)
        return ApiResult.Ok(dto)
    }

    override suspend fun renameSession(id: String, title: String): ApiResult<Unit> {
        val state = sessions[id] ?: return ApiResult.Err(ApiError.Client(404, "session not found"))
        state.dto = state.dto.copy(title = title)
        return ApiResult.Ok(Unit)
    }

    override suspend fun deleteSession(id: String): ApiResult<Unit> {
        val removed = sessions.remove(id) != null
        return if (removed) ApiResult.Ok(Unit) else ApiResult.Err(ApiError.Client(404, "session not found"))
    }

    override suspend fun registerDevice(fcmToken: String, deviceId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun unregisterDevice(deviceId: String): ApiResult<Unit> = ApiResult.Ok(Unit)

    private val jobs = ConcurrentHashMap<String, JobDto>().apply {
        put(
            "fakejob000001",
            JobDto(
                id = "fakejob000001",
                name = "daily digest",
                prompt = "summarize the day's email",
                schedule = JobScheduleDto(kind = "cron", expr = "0 7 * * *", display = "0 7 * * *"),
                scheduleDisplay = "0 7 * * *",
                nextRunAt = "2026-07-21T07:00:00+09:00",
                lastRunAt = "2026-07-20T07:00:58+09:00",
                lastStatus = "ok",
            ),
        )
        put(
            "fakejob000002",
            JobDto(
                id = "fakejob000002",
                name = "hourly check",
                prompt = "check the monitors",
                schedule = JobScheduleDto(kind = "interval", minutes = 60, display = "every 60m"),
                scheduleDisplay = "every 60m",
                enabled = false,
                state = "paused",
                lastStatus = "error",
                lastError = "tool timeout",
            ),
        )
    }
    private val jobCounter = AtomicInteger(2)

    override suspend fun listJobs(): ApiResult<List<JobDto>> = ApiResult.Ok(jobs.values.sortedBy { it.name })

    override suspend fun createJob(job: JobCreateDto): ApiResult<JobDto> {
        if (job.name.isBlank()) return ApiResult.Err(ApiError.Client(400, "Name is required"))
        val id = "fakejob%06d".format(jobCounter.incrementAndGet())
        val dto = JobDto(
            id = id,
            name = job.name,
            prompt = job.prompt,
            schedule = JobScheduleDto(kind = "cron", expr = job.schedule, display = job.schedule),
            scheduleDisplay = job.schedule,
        )
        jobs[id] = dto
        return ApiResult.Ok(dto)
    }

    override suspend fun updateJob(id: String, patch: JobPatchDto): ApiResult<JobDto> {
        val current = jobs[id] ?: return ApiResult.Err(ApiError.Client(404, "Job not found"))
        val updated = current.copy(
            name = patch.name ?: current.name,
            prompt = patch.prompt ?: current.prompt,
            scheduleDisplay = patch.schedule ?: current.scheduleDisplay,
            enabled = patch.enabled ?: current.enabled,
        )
        jobs[id] = updated
        return ApiResult.Ok(updated)
    }

    override suspend fun deleteJob(id: String): ApiResult<Unit> =
        if (jobs.remove(id) != null) ApiResult.Ok(Unit) else ApiResult.Err(ApiError.Client(404, "Job not found"))

    /**
     * Translates [scriptFor]'s run-vocabulary events into the chat vocabulary:
     * `run.started` first, deltas/tool events unchanged (with Streaming's
     * pacing), `run.completed` last. Appends both sides of the exchange to the
     * session store on completion so the next [listSessions]/[sessionMessages]
     * (or [net.liquidx.leman.data.repo.SessionSyncer] tick) sees them.
     */
    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        val state = sessions[id] ?: throw HermesStreamException(ApiError.Client(404, "session not found"))
        val current = scenario.value

        val userNow = System.currentTimeMillis()
        state.messages.add(
            SessionMessageDto(
                id = messageIdSeq.getAndIncrement(),
                role = "user",
                content = message,
                timestamp = userNow / 1000.0,
            ),
        )
        state.touch(userNow, state.messages.size)

        val runId = "fake-run-${counter.incrementAndGet()}"
        emit(RunEvent.RunStarted(runId, 0.5))

        var output = ""
        val reasoningParts = mutableListOf<String>()
        for (event in scriptFor(current, message)) {
            if (event is RunEvent.Reasoning) reasoningParts += event.text
            if (event is RunEvent.RunCompleted) output = event.output
            emit(event)
            if (current == FakeScenario.Streaming) delay(STREAM_DELAY_MS)
        }

        val assistantNow = System.currentTimeMillis()
        state.messages.add(
            SessionMessageDto(
                id = messageIdSeq.getAndIncrement(),
                role = "assistant",
                content = output,
                reasoning = reasoningParts.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
                timestamp = assistantNow / 1000.0,
            ),
        )
        state.touch(assistantNow, state.messages.size)
    }

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

    override fun reconfigure(baseUrl: String?, apiKey: String?) = real.reconfigure(baseUrl, apiKey)

    override suspend fun capabilities() = active.capabilities()
    override suspend fun listSessions(limit: Int, offset: Int) = active.listSessions(limit, offset)
    override suspend fun sessionMessages(id: String) = active.sessionMessages(id)
    override suspend fun createSession() = active.createSession()
    override suspend fun renameSession(id: String, title: String) = active.renameSession(id, title)
    override suspend fun deleteSession(id: String) = active.deleteSession(id)
    override suspend fun listJobs() = active.listJobs()
    override suspend fun createJob(job: JobCreateDto) = active.createJob(job)
    override suspend fun updateJob(id: String, patch: JobPatchDto) = active.updateJob(id, patch)
    override suspend fun deleteJob(id: String) = active.deleteJob(id)
    override suspend fun registerDevice(fcmToken: String, deviceId: String) = real.registerDevice(fcmToken, deviceId)
    override suspend fun unregisterDevice(deviceId: String) = real.unregisterDevice(deviceId)

    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        var count = 0
        active.chatStream(id, message).collect { event ->
            bus.logEvent(event.eventName(), event.timestamp, event.payloadPreview())
            if (chaos.flags.value.dropStream && ++count >= 5) {
                throw HermesStreamException(ApiError.Network(IOException("chaos stream drop")))
            }
            emit(event)
        }
    }
}

fun RunEvent.eventName(): String = when (this) {
    is RunEvent.MessageDelta -> "message.delta"
    is RunEvent.Reasoning -> "reasoning.available"
    is RunEvent.ToolStarted -> "tool.started"
    is RunEvent.ToolCompleted -> "tool.completed"
    is RunEvent.RunCompleted -> "run.completed"
    is RunEvent.RunStarted -> "run.started"
    is RunEvent.ReasoningDelta -> "reasoning.delta"
    is RunEvent.AssistantCompleted -> "assistant.completed"
    is RunEvent.Unknown -> "unknown"
}

fun RunEvent.payloadPreview(): String = when (this) {
    is RunEvent.MessageDelta -> delta.take(80)
    is RunEvent.Reasoning -> text.take(80)
    is RunEvent.ToolStarted -> "$tool · ${preview.orEmpty().take(60)}"
    is RunEvent.ToolCompleted -> "$tool · ${"%.1f".format(duration)}s${if (error) " · error" else ""}"
    is RunEvent.RunCompleted -> "output ${output.length} chars"
    is RunEvent.RunStarted -> runId
    is RunEvent.ReasoningDelta -> delta.take(80)
    is RunEvent.AssistantCompleted -> content.take(80)
    is RunEvent.Unknown -> raw.take(80)
}
