# Server-Synced Threads (Sessions API Migration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Threads created by any Hermes client (dashboard, CLI, cron) appear in the app and refresh automatically; the app's own sends migrate from `/v1/runs` to the Sessions API.

**Architecture:** Room inverts from system-of-record to cache of the gateway's session store. A `SessionSyncer` polls `GET /api/sessions` (foreground: on open + 30s ticker + after own runs) and rebuilds threads/turns from `GET /api/sessions/{id}/messages`, preserving local-only pin/unread/agent-profile state. Sending migrates to `POST /api/sessions/{id}/chat/stream` (named-event SSE, server-side history). Rename/delete propagate via PATCH/DELETE.

**Tech Stack:** Kotlin, Compose, Room 2.x, OkHttp, kotlinx.serialization, Robolectric + Roborazzi, JUnit4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-07-17-session-sync-design.md` — read it first; it contains the verified wire shapes all tasks decode.

## Global Constraints

- Base URL `https://api.gent.ino.ink`; Sessions API is under `/api/sessions/*` (NOT `/v1`).
- All JSON decoding via `HermesJson` (`ignoreUnknownKeys = true`); a malformed frame/body must never crash the app (spec 04).
- Float epoch **seconds** on the wire; Room stores epoch **millis** (`(x * 1000).toLong()`).
- Local-only thread state that sync must never clobber: `pinned`, `unread`, `agentName`, `agentGlyph`.
- Non-`api_server` sources get a faint source tag in the UI; `api_server` shows no tag.
- Room migration is destructive (version 1 → 2, `fallbackToDestructiveMigration` already configured).
- Test command: `./gradlew :app:testDebugUnitTest --tests "<pattern>"`. Full suite before each commit.
- Roborazzi golden re-record: `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`.
- Commit after every task; `git add` only the files the task touched (the tree has unrelated uncommitted work).

---

### Task 1: Sessions API DTOs

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/Dto.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/remote/DtoTest.kt`

**Interfaces:**
- Produces: `SessionDto`, `SessionListDto`, `SessionEnvelopeDto`, `SessionMessageDto`, `ToolCallDto`, `SessionMessagesDto`, `ChatRequestDto`, `CapabilitiesDto` (with `supportsSessions: Boolean`) — consumed by Tasks 5–9.

- [ ] **Step 1: Write failing decode tests** (append to `DtoTest.kt`, following its existing style; payloads are abbreviated forms of the verified wire shapes in the spec):

```kotlin
@Test
fun sessionList_decodes() {
    val json = """{"object":"list","data":[{"id":"run_abc","source":"cron","model":"m",
        "title":"Daily Digest","preview":"[IMPORTANT…","started_at":1784153421.0,
        "last_active":1784154105.2,"message_count":12,"extra_field":1}],
        "limit":3,"offset":0,"has_more":true}"""
    val dto = HermesJson.decodeFromString(SessionListDto.serializer(), json)
    assertEquals("run_abc", dto.data.single().id)
    assertEquals("cron", dto.data.single().source)
    assertEquals(12, dto.data.single().messageCount)
    assertTrue(dto.hasMore)
}

@Test
fun sessionMessages_decodes_toolCallsAndReasoning() {
    val json = """{"object":"list","session_id":"s1","data":[
        {"id":1761,"session_id":"s1","role":"user","content":"hi","timestamp":1784209432.5},
        {"id":1762,"session_id":"s1","role":"assistant","content":"",
         "tool_calls":[{"id":"c1","type":"function","function":{"name":"memory","arguments":"{\"a\":1}"}}],
         "reasoning":"planning","timestamp":1784209435.8,"finish_reason":"tool_calls"},
        {"id":1763,"session_id":"s1","role":"tool","content":"ok","tool_name":"memory","tool_call_id":"c1","timestamp":1784209436.0}]}"""
    val dto = HermesJson.decodeFromString(SessionMessagesDto.serializer(), json)
    assertEquals(3, dto.data.size)
    assertEquals("memory", dto.data[1].toolCalls!!.single().function!!.name)
    assertEquals("planning", dto.data[1].reasoning)
}

@Test
fun sessionEnvelope_decodes_createResponse() {
    val json = """{"object":"hermes.session","session":{"id":"api_1_a","source":"api_server","started_at":1.0}}"""
    assertEquals("api_1_a", HermesJson.decodeFromString(SessionEnvelopeDto.serializer(), json).session.id)
}

@Test
fun capabilities_supportsSessions_requiresBothFlags() {
    val yes = """{"features":{"session_resources":true,"session_chat_streaming":true,"cors":false}}"""
    val no = """{"features":{"session_resources":true,"session_chat_streaming":false}}"""
    assertTrue(HermesJson.decodeFromString(CapabilitiesDto.serializer(), yes).supportsSessions)
    assertFalse(HermesJson.decodeFromString(CapabilitiesDto.serializer(), no).supportsSessions)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "net.liquidx.leman.data.remote.DtoTest"` — expected: compile error, unresolved `SessionListDto`.

- [ ] **Step 3: Implement** (append to `Dto.kt`):

```kotlin
@Serializable
data class SessionDto(
    val id: String,
    val source: String = "api_server",
    val model: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double = 0.0,
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class SessionListDto(
    val data: List<SessionDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** POST /api/sessions nests the created session under `session`. */
@Serializable
data class SessionEnvelopeDto(val session: SessionDto)

@Serializable
data class ToolCallFunctionDto(val name: String? = null, val arguments: String? = null)

@Serializable
data class ToolCallDto(val function: ToolCallFunctionDto? = null)

@Serializable
data class SessionMessageDto(
    val id: Long,
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val reasoning: String? = null,
    val timestamp: Double = 0.0,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class SessionMessagesDto(
    @SerialName("session_id") val sessionId: String = "",
    val data: List<SessionMessageDto> = emptyList(),
)

@Serializable
data class ChatRequestDto(val message: String)

@Serializable
data class SessionPatchDto(val title: String)

/** GET /v1/capabilities — gate the whole Sessions feature on these flags (spec §5). */
@Serializable
data class CapabilitiesDto(
    val version: String? = null,
    val features: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
) {
    private fun flag(name: String): Boolean =
        (features[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
    val supportsSessions: Boolean
        get() = flag("session_resources") && flag("session_chat_streaming")
}
```

Add imports `kotlinx.serialization.json.JsonPrimitive` / `booleanOrNull` as needed (match file's import style).

- [ ] **Step 4: Run tests** — same command — expected: PASS.
- [ ] **Step 5: Commit** — `git add app/src/main/java/net/liquidx/leman/data/remote/Dto.kt app/src/test/java/net/liquidx/leman/data/remote/DtoTest.kt && git commit -m "feat: add Sessions API DTOs"`

---

### Task 2: Named-event SSE frames

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/SseReader.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/remote/SseReaderTest.kt`

**Interfaces:**
- Produces: `data class SseFrame(val event: String?, val data: String)` and `fun readSseFrames(source: BufferedSource): Sequence<SseFrame>` — consumed by Tasks 3 and 5. Existing `readSseDataFrames` stays untouched until Task 13.

- [ ] **Step 1: Write failing tests** (append to `SseReaderTest.kt`, using the same Buffer-based setup as existing tests):

```kotlin
@Test
fun readSseFrames_pairsEventWithData() {
    val src = okio.Buffer().writeUtf8(
        "event: run.started\ndata: {\"run_id\":\"r1\"}\n\n" +
        "event: assistant.delta\ndata: {\"delta\":\"hi\"}\n\n",
    )
    val frames = readSseFrames(src).toList()
    assertEquals(listOf("run.started", "assistant.delta"), frames.map { it.event })
    assertEquals("{\"run_id\":\"r1\"}", frames[0].data)
}

@Test
fun readSseFrames_dataOnlyFrame_hasNullEvent_andBlankLineResetsEvent() {
    val src = okio.Buffer().writeUtf8(
        "event: done\ndata: {}\n\n" + "data: {\"orphan\":true}\n\n" + ": comment\n",
    )
    val frames = readSseFrames(src).toList()
    assertEquals(listOf("done", null), frames.map { it.event })
}
```

- [ ] **Step 2: Verify fail** — `./gradlew :app:testDebugUnitTest --tests "net.liquidx.leman.data.remote.SseReaderTest"` — compile error, unresolved `readSseFrames`.

- [ ] **Step 3: Implement** (append to `SseReader.kt`):

```kotlin
/** One chat-stream frame: `event:` name (null when absent) + `data:` payload. */
data class SseFrame(val event: String?, val data: String)

/**
 * Reads the Sessions chat stream's standard-ish framing (spec 02): `event:` +
 * `data:` line pairs separated by blank lines. A blank line resets the pending
 * event name so a dangling `event:` never leaks onto a later frame.
 */
fun readSseFrames(source: BufferedSource): Sequence<SseFrame> = sequence {
    var event: String? = null
    while (true) {
        val line = try {
            source.readUtf8Line() ?: break
        } catch (_: java.io.EOFException) {
            break
        }
        val trimmed = line.removeSuffix("\r")
        when {
            trimmed.isEmpty() -> event = null
            trimmed.startsWith("event:") -> event = trimmed.removePrefix("event:").trim()
            trimmed.startsWith("data:") -> yield(SseFrame(event, trimmed.removePrefix("data:").trimStart()))
            else -> Unit // comments and anything unrecognized
        }
    }
}
```

- [ ] **Step 4: Verify pass**, then full suite.
- [ ] **Step 5: Commit** — `git commit -m "feat: named-event SSE frame reader for sessions chat stream"` (add the two files only).

---

### Task 3: Chat event vocabulary → RunEvent

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/domain/model/RunEvent.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/RunEventParser.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/remote/ChatEventParserTest.kt` (create)

**Interfaces:**
- Produces: `RunEvent.RunStarted(runId, timestamp)`, `RunEvent.ReasoningDelta(delta, timestamp)`, `RunEvent.AssistantCompleted(content, interrupted, timestamp)`, and `fun parseChatEvent(frame: SseFrame): RunEvent`. Consumed by Tasks 4, 5, 9, 12.
- Chat wire facts (verified, spec §Chat): timestamp field is `ts`; tool fields are `tool_name`/`preview`; reasoning streams as `tool.progress` with `tool_name == "_thinking"`; `run.completed` carries `messages: […]` (no `output` field) — the final assistant message with non-empty `content` is the output; chat `tool.completed` has no `duration`/`error` fields.

- [ ] **Step 1: Add RunEvent variants** (inside the sealed interface in `RunEvent.kt`):

```kotlin
    /** Sessions chat stream: server accepted the message; carries the run id. */
    data class RunStarted(val runId: String, override val timestamp: Double) : RunEvent

    /** Sessions chat stream: incremental reasoning text (`tool.progress` on `_thinking`). */
    data class ReasoningDelta(val delta: String, override val timestamp: Double) : RunEvent

    /** Sessions chat stream: assistant message finished (may precede run.completed). */
    data class AssistantCompleted(
        val content: String,
        val interrupted: Boolean,
        override val timestamp: Double,
    ) : RunEvent
```

- [ ] **Step 2: Write failing parser tests** (new file, package `net.liquidx.leman.data.remote`, plain JUnit4 like `RunEventParserTest`):

```kotlin
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
```

- [ ] **Step 3: Verify fail**, then implement (append to `RunEventParser.kt`; reuse its private `requireString` helper):

```kotlin
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
```

Add imports: `jsonArray`, `JsonObject`. Also update the two debug helpers in `app/src/debug/java/net/liquidx/leman/debug/FakeHermesServer.kt` (`RunEvent.eventName()` / `payloadPreview()` `when`s) with the three new variants so the debug build compiles:

```kotlin
    is RunEvent.RunStarted -> "run.started"
    is RunEvent.ReasoningDelta -> "reasoning.delta"
    is RunEvent.AssistantCompleted -> "assistant.completed"
```
```kotlin
    is RunEvent.RunStarted -> runId
    is RunEvent.ReasoningDelta -> delta.take(80)
    is RunEvent.AssistantCompleted -> content.take(80)
```

- [ ] **Step 4: Verify pass**, full suite (debug variant compiles).
- [ ] **Step 5: Commit** — `git commit -m "feat: parse sessions chat-stream events into RunEvents"`.

---

### Task 4: TraceComposer handles chat-stream shapes

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/domain/TraceComposer.kt`
- Test: `app/src/test/java/net/liquidx/leman/domain/TraceComposerTest.kt`

**Interfaces:**
- `composeTrace(events)` signature unchanged. New behavior: consecutive `ReasoningDelta`s merge into one Reasoning step; `ToolCompleted` with `duration == 0.0` derives duration from `completed.timestamp - started.timestamp` (floor 0). `RunStarted`/`AssistantCompleted` are ignored.

- [ ] **Step 1: Write failing tests** (append to `TraceComposerTest.kt`):

```kotlin
@Test
fun reasoningDeltas_mergeIntoOneStep_flushedByToolBoundary() {
    val trace = composeTrace(
        listOf(
            RunEvent.ReasoningDelta("plan", 1.0),
            RunEvent.ReasoningDelta(" steps", 1.1),
            RunEvent.ToolStarted("web", "q", 2.0),
            RunEvent.ToolCompleted("web", 0.0, false, 5.5),
            RunEvent.ReasoningDelta("wrap up", 6.0),
        ),
    )!!
    assertEquals(3, trace.steps.size)
    assertEquals("plan steps", trace.steps[0].summary)
    assertEquals("wrap up", trace.steps[2].summary)
}

@Test
fun toolDuration_derivedFromTimestamps_whenWireDurationAbsent() {
    val trace = composeTrace(
        listOf(
            RunEvent.ToolStarted("web", null, 2.0),
            RunEvent.ToolCompleted("web", 0.0, false, 5.5),
        ),
    )!!
    assertEquals(3.5, trace.steps.single().durationSeconds, 1e-6)
}

@Test
fun explicitWireDuration_stillWins() {
    val trace = composeTrace(
        listOf(
            RunEvent.ToolStarted("ci.logs", null, 2.0),
            RunEvent.ToolCompleted("ci.logs", 24.0, false, 3.0),
        ),
    )!!
    assertEquals(24.0, trace.steps.single().durationSeconds, 1e-6)
}
```

- [ ] **Step 2: Verify fail** — `--tests "net.liquidx.leman.domain.TraceComposerTest"`.

- [ ] **Step 3: Implement** — replace `composeTrace`'s body in `TraceComposer.kt`:

```kotlin
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
```

- [ ] **Step 4: Verify pass** (existing TraceComposer tests must stay green — `Reasoning`, FIFO pairing, unmatched-completed behavior unchanged). Full suite.
- [ ] **Step 5: Commit** — `git commit -m "feat: compose traces from chat-stream reasoning deltas and timestamps"`.

---

### Task 5: HermesClient session endpoints (interface, OkHttp, test fake)

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/HermesClient.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/OkHttpHermesClient.kt`
- Modify: `app/src/test/java/net/liquidx/leman/testutil/FakeHermesClient.kt`
- Modify: `app/src/debug/java/net/liquidx/leman/debug/FakeHermesServer.kt` (stub overrides so debug compiles; real fake behavior lands in Task 12)
- Test: `app/src/test/java/net/liquidx/leman/data/remote/OkHttpHermesClientTest.kt`

**Interfaces:**
- Produces (added to `HermesClient`; run methods stay until Task 13):

```kotlin
    suspend fun capabilities(): ApiResult<CapabilitiesDto>
    suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto>
    suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>>
    suspend fun createSession(): ApiResult<SessionDto>
    suspend fun renameSession(id: String, title: String): ApiResult<Unit>
    suspend fun deleteSession(id: String): ApiResult<Unit>
    fun chatStream(id: String, message: String): Flow<RunEvent>
```

- [ ] **Step 1: Write failing MockWebServer tests** (append to `OkHttpHermesClientTest.kt`, mirroring its existing enqueue/assert style). Cover: `listSessions` sends `GET /api/sessions?limit=50&offset=0` with bearer header and decodes; `sessionMessages` hits `GET /api/sessions/s1/messages`; `createSession` POSTs `{}` and unwraps the `session` envelope; `renameSession` PATCHes `{"title":"new"}`; `deleteSession` DELETEs; `chatStream` parses a named-event body into `RunStarted → MessageDelta → RunCompleted`; a 404 error envelope maps through the existing `mapHttpFailure`. Full test code:

```kotlin
@Test
fun listSessions_getsPagedPath_andDecodes() = runTest {
    server.enqueue(MockResponse().setBody(
        """{"object":"list","data":[{"id":"run_a","source":"cron","last_active":2.0}],"has_more":false}""",
    ))
    val result = client.listSessions(limit = 50, offset = 0) as ApiResult.Ok
    assertEquals("run_a", result.value.data.single().id)
    val req = server.takeRequest()
    assertEquals("/api/sessions?limit=50&offset=0", req.path)
    assertEquals("GET", req.method)
    assertTrue(req.getHeader("Authorization")!!.startsWith("Bearer "))
}

@Test
fun sessionMessages_decodesList() = runTest {
    server.enqueue(MockResponse().setBody(
        """{"object":"list","session_id":"s1","data":[{"id":1,"role":"user","content":"hi","timestamp":1.0}]}""",
    ))
    val result = client.sessionMessages("s1") as ApiResult.Ok
    assertEquals("hi", result.value.single().content)
    assertEquals("/api/sessions/s1/messages", server.takeRequest().path)
}

@Test
fun createSession_postsEmptyObject_unwrapsEnvelope() = runTest {
    server.enqueue(MockResponse().setBody(
        """{"object":"hermes.session","session":{"id":"api_1_a","source":"api_server"}}""",
    ))
    val result = client.createSession() as ApiResult.Ok
    assertEquals("api_1_a", result.value.id)
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("{}", req.body.readUtf8())
}

@Test
fun renameSession_patchesTitle() = runTest {
    server.enqueue(MockResponse().setBody("{}"))
    client.renameSession("s1", "new title")
    val req = server.takeRequest()
    assertEquals("PATCH", req.method)
    assertEquals("/api/sessions/s1", req.path)
    assertEquals("""{"title":"new title"}""", req.body.readUtf8())
}

@Test
fun deleteSession_deletes() = runTest {
    server.enqueue(MockResponse().setBody("""{"deleted":true}"""))
    assertTrue(client.deleteSession("s1") is ApiResult.Ok)
    assertEquals("DELETE", server.takeRequest().method)
}

@Test
fun chatStream_parsesNamedEvents() = runTest {
    server.enqueue(MockResponse().setBody(
        "event: run.started\ndata: {\"run_id\":\"r1\",\"ts\":1.0}\n\n" +
        "event: assistant.delta\ndata: {\"delta\":\"ok\",\"ts\":2.0}\n\n" +
        "event: run.completed\ndata: {\"messages\":[{\"role\":\"assistant\",\"content\":\"ok\",\"finish_reason\":\"stop\"}],\"ts\":3.0}\n\n",
    ))
    val events = client.chatStream("s1", "hello").toList()
    assertEquals(RunEvent.RunStarted("r1", 1.0), events[0])
    assertEquals(RunEvent.MessageDelta("ok", 2.0), events[1])
    assertEquals("ok", (events[2] as RunEvent.RunCompleted).output)
    val req = server.takeRequest()
    assertEquals("/api/sessions/s1/chat/stream", req.path)
    assertEquals("""{"message":"hello"}""", req.body.readUtf8())
}
```

- [ ] **Step 2: Verify fail** (compile: interface members missing).

- [ ] **Step 3: Implement.** In `OkHttpHermesClient.kt`, generalize `Transport.request` with optional query params and add the endpoint methods:

```kotlin
    private fun Transport.request(
        path: String,
        accept: String = "application/json",
        query: List<Pair<String, String>> = emptyList(),
    ): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", accept)
            .header("User-Agent", userAgent)
    }

    override suspend fun capabilities(): ApiResult<CapabilitiesDto> =
        get("v1/capabilities", CapabilitiesDto.serializer()) { it.rest }

    override suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val request = t.request(
            "api/sessions",
            query = listOf("limit" to "$limit", "offset" to "$offset"),
        ).get().build()
        return execute(t.rest, request) { HermesJson.decodeFromString(SessionListDto.serializer(), it) }
    }

    override suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(t.rest, t.request("api/sessions/$id/messages").get().build()) {
            HermesJson.decodeFromString(SessionMessagesDto.serializer(), it).data
        }
    }

    override suspend fun createSession(): ApiResult<SessionDto> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val request = t.request("api/sessions").post("{}".toRequestBody(jsonMediaType)).build()
        return execute(t.rest, request) {
            HermesJson.decodeFromString(SessionEnvelopeDto.serializer(), it).session
        }
    }

    override suspend fun renameSession(id: String, title: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(SessionPatchDto.serializer(), SessionPatchDto(title))
        val request = t.request("api/sessions/$id").patch(body.toRequestBody(jsonMediaType)).build()
        // response body shape is unpinned upstream — success is all we need
        return execute(t.rest, request) { }
    }

    override suspend fun deleteSession(id: String): ApiResult<Unit> {
        val t = transport ?: return ApiResult.Err(ApiError.NotConfigured)
        return execute(t.rest, t.request("api/sessions/$id").delete().build()) { }
    }

    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        val t = transport ?: throw HermesStreamException(ApiError.NotConfigured)
        val body = HermesJson.encodeToString(ChatRequestDto.serializer(), ChatRequestDto(message))
        val request = t.request("api/sessions/$id/chat/stream", accept = "text/event-stream")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = try {
            t.stream.newCall(request).execute()
        } catch (e: Exception) {
            throw HermesStreamException(mapException(e))
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw HermesStreamException(mapHttpFailure(resp))
            val source = resp.body?.source()
                ?: throw HermesStreamException(ApiError.Protocol("empty stream body"))
            try {
                for (frame in readSseFrames(source)) emit(parseChatEvent(frame))
            } catch (e: java.io.IOException) {
                throw HermesStreamException(mapException(e))
            }
        }
    }.flowOn(Dispatchers.IO)
```

In `testutil/FakeHermesClient.kt` add scriptable state + overrides:

```kotlin
    var capabilitiesResult: ApiResult<CapabilitiesDto> = ApiResult.Ok(
        CapabilitiesDto(features = mapOf(
            "session_resources" to kotlinx.serialization.json.JsonPrimitive(true),
            "session_chat_streaming" to kotlinx.serialization.json.JsonPrimitive(true),
        )),
    )
    /** Consumed one per [listSessions] call; the last one repeats. */
    val listSessionsResults = ArrayDeque<ApiResult<SessionListDto>>()
    /** Keyed by session id; missing id → 404. */
    val messagesBySession = mutableMapOf<String, ApiResult<List<SessionMessageDto>>>()
    var createSessionResult: ApiResult<SessionDto> = ApiResult.Ok(SessionDto(id = "api_1_test"))
    var renameSessionResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var deleteSessionResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    /** Consumed one per [chatStream] open; the last one repeats. Items: RunEvent or Throwable. */
    val chatScripts = ArrayDeque<List<Any>>()

    val renameCalls = mutableListOf<Pair<String, String>>()
    val deleteCalls = mutableListOf<String>()
    val chatCalls = mutableListOf<Pair<String, String>>()
    var listSessionsCalls = 0
    var sessionMessagesCalls = 0

    override suspend fun capabilities() = capabilitiesResult

    override suspend fun listSessions(limit: Int, offset: Int): ApiResult<SessionListDto> {
        listSessionsCalls++
        return when {
            listSessionsResults.isEmpty() -> ApiResult.Ok(SessionListDto())
            listSessionsResults.size > 1 -> listSessionsResults.removeFirst()
            else -> listSessionsResults.first()
        }
    }

    override suspend fun sessionMessages(id: String): ApiResult<List<SessionMessageDto>> {
        sessionMessagesCalls++
        return messagesBySession[id] ?: ApiResult.Err(ApiError.Client(404, "session not found"))
    }

    override suspend fun createSession() = createSessionResult

    override suspend fun renameSession(id: String, title: String): ApiResult<Unit> {
        renameCalls += id to title
        return renameSessionResult
    }

    override suspend fun deleteSession(id: String): ApiResult<Unit> {
        deleteCalls += id
        return deleteSessionResult
    }

    override fun chatStream(id: String, message: String): Flow<RunEvent> = flow {
        chatCalls += id to message
        val script = when {
            chatScripts.isEmpty() -> emptyList()
            chatScripts.size > 1 -> chatScripts.removeFirst()
            else -> chatScripts.first()
        }
        for (item in script) {
            when (item) {
                is RunEvent -> emit(item)
                is Throwable -> throw item
                else -> error("bad script item $item")
            }
        }
    }
```

In debug `FakeHermesServer.kt` and `SwitchableHermesClient`, add compiling stubs (real behavior in Task 12): `FakeHermesServer` returns `ApiResult.Err(ApiError.Client(404, "not implemented"))` / empty flows; `SwitchableHermesClient` forwards each new method to `active` (and `chatStream` through the same chaos/log wrapper as `runEvents`).

- [ ] **Step 4: Verify pass**, full suite.
- [ ] **Step 5: Commit** — `git commit -m "feat: HermesClient sessions endpoints over OkHttp"`.

---

### Task 6: Room v2 — thread rows keyed by session id, `source` column

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/local/Entities.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/local/LemanDatabase.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/local/LocalMapping.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/local/Daos.kt`
- Modify: `app/src/main/java/net/liquidx/leman/domain/model/Models.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/ThreadRepository.kt` (compile-only: `sessionId` fallout)
- Test: `app/src/test/java/net/liquidx/leman/data/local/LocalMappingTest.kt`, `app/src/test/java/net/liquidx/leman/data/local/DaoTest.kt`

**Interfaces:**
- `ThreadEntity`: `sessionId: String?` → `source: String` (thread id IS the session id from here on). `@Database` version 1 → 2.
- Domain `Thread`: `sessionId: String?` → `source: String`.
- `ThreadDao` gains `suspend fun getThreads(): List<ThreadEntity>`; `TurnDao` gains `suspend fun deleteTurnsFor(threadId: String)` — consumed by Task 8.

- [ ] **Step 1: Update tests first.** In `LocalMappingTest.kt` replace `sessionId` fixtures/assertions with `source = "cron"` round-trip. In `DaoTest.kt` add:

```kotlin
@Test
fun deleteTurnsFor_removesOnlyThatThreadsTurns() = runTest {
    // insert two threads with one turn each (reuse the file's existing entity builders)
    turnDao.deleteTurnsFor(threadA.id)
    assertEquals(0, turnDao.getTurns(threadA.id).size)
    assertEquals(1, turnDao.getTurns(threadB.id).size)
}
```

- [ ] **Step 2: Implement.**
  - `Entities.kt`: replace `val sessionId: String?` with `val source: String` (comment: `// api_server | cron | cli — server-owned (spec 03)`).
  - `LemanDatabase.kt`: `version = 2`.
  - `Daos.kt`: add
    ```kotlin
    @Query("SELECT * FROM threads")
    suspend fun getThreads(): List<ThreadEntity>
    ```
    to `ThreadDao` and
    ```kotlin
    @Query("DELETE FROM turns WHERE threadId = :id")
    suspend fun deleteTurnsFor(id: String)
    ```
    to `TurnDao`.
  - `Models.kt`: `Thread.sessionId: String?` → `source: String`.
  - `LocalMapping.kt`: map `source` both directions.
  - `ThreadRepository.kt`, minimal compile fixes only (real migration is Task 9): `createThread` builds `ThreadEntity(…, source = "api_server", …)` instead of `sessionId = null`; in `runTurn`, replace the session bookkeeping with `client.startRun(input, threadId)` and delete the `if (it.sessionId == null)` block; in `exportJson`, replace the `sessionId` line with `put("source", t.source)`.
  - Fix remaining `sessionId` references: run `grep -rn "sessionId" app/src/main app/src/test` — expect hits only in `data/remote` (wire DTOs — keep those) and the files above. Update any test fixture that constructs `ThreadEntity`/`Thread`.

- [ ] **Step 3: Full suite** — `./gradlew :app:testDebugUnitTest` — expected: PASS (ThreadRepositoryTest assertions about session ids may need the `threadId`-as-session adjustment).
- [ ] **Step 4: Commit** — `git commit -m "feat!: Room v2 — threads keyed by server session id with source column"`.

---

### Task 7: Server messages → turns mapper

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/data/repo/SessionTurns.kt`
- Test: `app/src/test/java/net/liquidx/leman/data/repo/SessionTurnsTest.kt` (create)

**Interfaces:**
- Produces: `fun sessionTurns(sessionId: String, messages: List<SessionMessageDto>): List<TurnEntity>` — consumed by Tasks 8 and 9. Deterministic ids: user/agent turns `msg-<sessionId>-<messageId>`, trace turns `trace-<sessionId>-<anchorMessageId>` — re-running the mapper yields identical rows (idempotent rebuild, spec §1).

- [ ] **Step 1: Write failing tests** (plain JUnit4, package `net.liquidx.leman.data.repo`):

```kotlin
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
}
```

- [ ] **Step 2: Verify fail**, then implement `SessionTurns.kt`:

```kotlin
package net.liquidx.leman.data.repo

import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.local.encodeTrace
import net.liquidx.leman.data.remote.SessionMessageDto
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind

/**
 * Rebuilds a synced thread's turns from the server's message history (spec 03).
 * Deterministic ids (`msg-`/`trace-` + server message id) make the rebuild
 * idempotent. Tool results (`role: tool`) carry no timing, so trace steps map
 * tool_calls + reasoning only; durations stay 0 for synced history.
 */
fun sessionTurns(sessionId: String, messages: List<SessionMessageDto>): List<TurnEntity> {
    val turns = mutableListOf<TurnEntity>()
    val pendingSteps = mutableListOf<TraceStep>()
    var seq = 0L

    fun turn(id: String, kind: String, createdAt: Long, markdown: String?, traceJson: String? = null) =
        TurnEntity(
            id = id, threadId = sessionId, seq = ++seq, kind = kind, createdAt = createdAt,
            markdown = markdown, blocksJson = null, traceJson = traceJson, runId = null,
            sendState = "synced", viaButton = false,
        )

    fun flushTrace(anchor: String, createdAt: Long) {
        if (pendingSteps.isEmpty()) return
        turns += turn(
            id = "trace-$sessionId-$anchor", kind = "trace", createdAt = createdAt,
            markdown = null, traceJson = encodeTrace(Trace(pendingSteps.toList())),
        )
        pendingSteps.clear()
    }

    for (m in messages) {
        val createdAt = (m.timestamp * 1000).toLong()
        when (m.role) {
            "user" -> {
                if (m.content.isNullOrEmpty()) continue
                turns += turn("msg-$sessionId-${m.id}", "user", createdAt, m.content)
            }
            "assistant" -> {
                m.reasoning?.takeIf { it.isNotBlank() }?.let {
                    pendingSteps += TraceStep(TraceStepKind.Reasoning, summary = it)
                }
                m.toolCalls.orEmpty().forEach { call ->
                    val name = call.function?.name ?: return@forEach
                    pendingSteps += TraceStep(
                        TraceStepKind.Tool, tool = name,
                        summary = call.function.arguments?.take(120),
                    )
                }
                if (!m.content.isNullOrEmpty()) {
                    flushTrace(anchor = "${m.id}", createdAt = createdAt)
                    turns += turn("msg-$sessionId-${m.id}", "agent", createdAt, m.content)
                }
            }
            else -> Unit // tool results: no user-visible turn, no timing data
        }
    }
    val tailAt = messages.lastOrNull()?.let { (it.timestamp * 1000).toLong() } ?: 0L
    flushTrace(anchor = "tail", createdAt = tailAt)
    return turns
}
```

- [ ] **Step 3: Verify pass**, full suite.
- [ ] **Step 4: Commit** — `git commit -m "feat: map server session messages to thread turns"`.

---

### Task 8: SessionSyncer

**Files:**
- Create: `app/src/main/java/net/liquidx/leman/data/repo/SessionSyncer.kt`
- Create: `app/src/main/java/net/liquidx/leman/data/repo/Snippets.kt` (move `String.snippet` out of `ThreadRepository` so the syncer shares it)
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/ThreadRepository.kt` (delete its private `snippet`, import the shared one)
- Test: `app/src/test/java/net/liquidx/leman/data/repo/SessionSyncerTest.kt` (create)

**Interfaces:**
- Produces:

```kotlin
class SessionSyncer(
    private val db: LemanDatabase,
    private val client: HermesClient,
    private val isRunActive: (String) -> Boolean,
    private val visibleThreadId: () -> String?,
) { suspend fun syncOnce(): ApiResult<Unit> }
```
```kotlin
// Snippets.kt
package net.liquidx.leman.data.repo
internal fun String.snippet(max: Int): String  // body moved verbatim from ThreadRepository
```

- [ ] **Step 1: Write failing tests** (Robolectric + in-memory Room, mirroring `ThreadRepositoryTest`'s `setQueryCoroutineContext` harness; `FakeHermesClient` from Task 5):

```kotlin
@RunWith(RobolectricTestRunner::class)
class SessionSyncerTest {
    // db/client/harness setup identical in shape to ThreadRepositoryTest.repo()

    private fun session(id: String, lastActive: Double, source: String = "api_server",
                        title: String? = null, preview: String? = null, count: Int = 2) =
        SessionDto(id = id, source = source, title = title, preview = preview,
                   startedAt = lastActive - 10, lastActive = lastActive, messageCount = count)

    private fun syncer(active: Set<String> = emptySet(), visible: String? = null) =
        SessionSyncer(db, client, isRunActive = { it in active }, visibleThreadId = { visible })

    @Test
    fun newForeignSession_createsThreadWithTurns_notUnread() = runTest {
        client.listSessionsResults.add(ApiResult.Ok(SessionListDto(
            data = listOf(session("run_x", 200.0, source = "cron", title = "Digest")), hasMore = false)))
        client.messagesBySession["run_x"] = ApiResult.Ok(listOf(
            SessionMessageDto(1, "user", "prompt", timestamp = 190.0),
            SessionMessageDto(2, "assistant", "answer", timestamp = 195.0, finishReason = "stop"),
        ))
        assertTrue(syncer().syncOnce() is ApiResult.Ok)
        val thread = db.threadDao().getThread("run_x")!!
        assertEquals("Digest", thread.title)
        assertEquals("cron", thread.source)
        assertEquals(200_000L, thread.lastActiveAt)
        assertFalse(thread.unread)
        assertEquals(listOf("user", "agent"), db.turnDao().getTurns("run_x").map { it.kind })
    }

    @Test
    fun changedSession_rebuildsTurns_marksUnreadUnlessVisible() = runTest { /* seed local thread
        at lastActiveAt=100_000 with stale turn; serve session(lastActive=200.0) + 3 messages;
        after sync: turns rebuilt (3 rows), unread=true. Re-run with visible="run_x": unread=false. */ }

    @Test
    fun unchangedSession_skipsMessagesFetch() = runTest { /* local lastActiveAt == server*1000
        → sessionMessagesCalls stays 0 */ }

    @Test
    fun localSidecar_pinAndProfile_surviveRebuild() = runTest { /* seed pinned=true,
        agentName="lem" locally; sync a changed session; both survive */ }

    @Test
    fun sessionGoneFromServer_deletesLocalThread_unlessRunActive() = runTest { /* seed two local
        threads; server lists only one; syncer(active=setOf(otherId)) keeps the active one */ }

    @Test
    fun pagination_followsHasMore() = runTest { /* two pages: hasMore=true then false;
        both sessions land; listSessionsCalls == 2 */ }

    @Test
    fun titleFallback_serverNull_usesFirstUserMessage_thenPreview() = runTest { /* title=null,
        messages with user "fix the pipeline…" → title == its snippet(80) */ }

    @Test
    fun listFailure_returnsErr_touchesNothing() = runTest {
        client.listSessionsResults.add(ApiResult.Err(ApiError.Network(java.io.IOException("down"))))
        assertTrue(syncer().syncOnce() is ApiResult.Err)
    }
}
```

Write every `/* … */` sketch above as real code in the test file — the comments here define the exact scenario and assertions each must make.

- [ ] **Step 2: Verify fail**, then implement `SessionSyncer.kt`:

```kotlin
package net.liquidx.leman.data.repo

import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.domain.model.ApiResult

/**
 * Pulls the server's session store into Room (spec 03: Room is a cache; the
 * gateway is the system of record). Local-only sidecar state — pinned, unread,
 * agentName/agentGlyph — survives every rebuild. Threads with an in-flight
 * local run are skipped and reconcile after the run completes.
 */
class SessionSyncer(
    private val db: LemanDatabase,
    private val client: HermesClient,
    private val isRunActive: (String) -> Boolean,
    private val visibleThreadId: () -> String?,
) {
    suspend fun syncOnce(): ApiResult<Unit> {
        val sessions = mutableListOf<SessionDto>()
        var offset = 0
        while (true) {
            when (val page = client.listSessions(PAGE_SIZE, offset)) {
                is ApiResult.Err -> return ApiResult.Err(page.error)
                is ApiResult.Ok -> {
                    sessions += page.value.data
                    if (!page.value.hasMore || page.value.data.isEmpty()) break
                    offset += page.value.data.size
                }
            }
        }

        val threadDao = db.threadDao()
        val turnDao = db.turnDao()
        val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
        for (local in threadDao.getThreads()) {
            if (local.id !in serverIds && !isRunActive(local.id)) threadDao.deleteThread(local.id)
        }

        for (session in sessions) {
            if (isRunActive(session.id)) continue
            val local = threadDao.getThread(session.id)
            val lastActiveMs = (session.lastActive * 1000).toLong()
            if (local != null && local.lastActiveAt == lastActiveMs) continue

            val messages = when (val result = client.sessionMessages(session.id)) {
                is ApiResult.Err -> continue // partial sync is fine; next tick retries
                is ApiResult.Ok -> result.value
            }
            val turns = sessionTurns(session.id, messages)
            val lastMarkdown = turns.lastOrNull { it.markdown != null }?.markdown
            val firstUser = turns.firstOrNull { it.kind == "user" }?.markdown
            threadDao.upsertThread(
                ThreadEntity(
                    id = session.id,
                    title = session.title ?: (firstUser ?: session.preview.orEmpty()).snippet(80),
                    preview = (lastMarkdown ?: session.preview.orEmpty()).snippet(120),
                    state = "idle",
                    pinned = local?.pinned ?: false,
                    // brand-new rows arrive read (first sync would otherwise flood);
                    // an advance on a known thread is unread unless it's on screen
                    unread = local != null && visibleThreadId() != session.id,
                    createdAt = (session.startedAt * 1000).toLong(),
                    lastActiveAt = lastActiveMs,
                    source = session.source,
                    agentName = local?.agentName,
                    agentGlyph = local?.agentGlyph,
                ),
            )
            turnDao.deleteTurnsFor(session.id)
            turnDao.upsertTurns(turns)
        }
        return ApiResult.Ok(Unit)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
```

Create `Snippets.kt` with the `snippet` body moved verbatim from `ThreadRepository` (marked `internal`), delete the private copy, and keep `ThreadRepository` compiling.

- [ ] **Step 3: Verify pass**, full suite.
- [ ] **Step 4: Commit** — `git commit -m "feat: SessionSyncer pulls server sessions into Room"`.

---

### Task 9: ThreadRepository — send path over chat/stream, recovery, propagated mutations

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/ThreadRepository.kt`
- Modify: `app/src/main/java/net/liquidx/leman/ui/newthread/NewThreadViewModel.kt` (+ its test) — `createThread` becomes fallible
- Test: `app/src/test/java/net/liquidx/leman/data/repo/ThreadRepositoryTest.kt` (rewrite run-lifecycle tests)

**Interfaces:**
- `createThread(firstMessage, profile): String?` — null when `POST /api/sessions` fails (caller shows the existing send-failure affordance).
- `renameThread`/`deleteThread` return `Boolean` (false = server refused; caller leaves local state alone).
- New: `suspend fun syncNow(): ApiResult<Unit>` delegating to an internally-owned `SessionSyncer`.
- Removed internally: `buildInput`, `streamLoop`, `runTurn`, `recoverIfRunning`'s runs-API poll (replaced by message polling). `StreamingRun` shape unchanged (UI untouched).

- [ ] **Step 1: Rewrite the affected repository tests first.** Key new tests (full code; adapt the existing harness):

```kotlin
@Test
fun createThread_usesServerSessionId_andStreamsChat() = runTest {
    client.createSessionResult = ApiResult.Ok(SessionDto(id = "api_9_z"))
    client.chatScripts.add(listOf(
        RunEvent.RunStarted("r1", 1.0),
        RunEvent.MessageDelta("hello there", 2.0),
        RunEvent.RunCompleted("hello there", null, 3.0),
    ))
    val repo = repo()
    val id = repo.createThread("fix the flaky ci pipeline please")
    advanceUntilIdle()
    assertEquals("api_9_z", id)
    assertEquals("api_9_z" to "fix the flaky ci pipeline please", client.chatCalls.single())
    val turns = repo.observeTurns("api_9_z").first()
    assertEquals(SendState.Synced, turns.first { it.kind == TurnKind.User }.sendState)
    assertEquals("r1", turns.first { it.kind == TurnKind.User }.runId)
    assertEquals("hello there", turns.first { it.kind == TurnKind.Agent }.markdown)
}

@Test
fun createThread_sessionCreateFails_returnsNull_noThreadRow() = runTest {
    client.createSessionResult = ApiResult.Err(ApiError.Network(IOException("down")))
    val repo = repo()
    assertNull(repo.createThread("hi"))
    assertTrue(repo.observeThreads().first().isEmpty())
}

@Test
fun droppedStream_afterRunStarted_recoversByPollingMessages() = runTest {
    client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
    client.chatScripts.add(listOf(
        RunEvent.RunStarted("r1", 1.0),
        RunEvent.MessageDelta("par", 2.0),
        HermesStreamException(ApiError.Network(IOException("drop"))),
    ))
    client.messagesBySession["s1"] = ApiResult.Ok(listOf(
        SessionMessageDto(1, "user", "hi", timestamp = 1.0),
        SessionMessageDto(2, "assistant", "partial answer done", timestamp = 5.0, finishReason = "stop"),
    ))
    val repo = repo()
    repo.createThread("hi")
    advanceUntilIdle()
    val thread = repo.observeThreads().first().single()
    assertEquals(ThreadState.Idle, thread.state)
    assertEquals("partial answer done",
        repo.observeTurns("s1").first().last { it.kind == TurnKind.Agent }.markdown)
}

@Test
fun droppedStream_beforeRunStarted_failsTurn() = runTest {
    client.createSessionResult = ApiResult.Ok(SessionDto(id = "s1"))
    client.chatScripts.add(listOf(HermesStreamException(ApiError.Network(IOException("refused")))))
    client.messagesBySession["s1"] = ApiResult.Ok(emptyList())
    val repo = repo()
    repo.createThread("hi")
    advanceUntilIdle()
    assertEquals(ThreadState.Failed, repo.observeThreads().first().single().state)
    assertEquals(SendState.Failed,
        repo.observeTurns("s1").first().single { it.kind == TurnKind.User }.sendState)
}

@Test
fun retryTurn_messageAlreadyOnServer_pollsInsteadOfResending() = runTest {
    // seed a failed turn whose text the server already has, plus a completed reply
    // …setup as in droppedStream test, then:
    repo.retryTurn(failedTurnId)
    advanceUntilIdle()
    assertEquals(0, client.chatCalls.size) // no duplicate send
    assertEquals(ThreadState.Idle, repo.observeThreads().first().single().state)
}

@Test
fun retryTurn_notOnServer_resends() = runTest { /* messagesBySession returns empty list;
    chatScripts completes normally; assert chatCalls.size == 1 and turn ends Synced */ }

@Test
fun renameThread_patchesServer_localOnlyOnOk() = runTest {
    // Ok path: renameCalls records (id, title), Room title updated, returns true.
    // Err path: returns false, Room untouched.
}

@Test
fun deleteThread_deletesServerThenLocal_404TreatedAsGone() = runTest {
    // Ok → local row gone, true. Err(Client(404,…)) → local row also gone (already
    // deleted remotely), true. Err(Network) → local row stays, false.
}

@Test
fun authFailure_onChatStream_poisonsConnState_andFailsTurn() = runTest { /* chatScripts throws
    HermesStreamException(ApiError.Auth(401)); assert authFailures == 1, turn Failed */ }

@Test
fun runCompletion_triggersSyncNow() = runTest { /* after a completed chat script,
    client.listSessionsCalls >= 1 */ }
```

Write every `/* … */` scenario as real code. Delete tests that exercised removed machinery (`buildsInputFromHistory`, runs-API poll backstop, replay-reset) — history assembly and stream replay are gone by design.

- [ ] **Step 2: Verify fail**, then rewrite `ThreadRepository`. Shape (complete except unchanged members — `observeThreads/observeTurns/setVisibleThread/setPinned/markRead/clearAll/exportJson/streamingFrom/setStreaming/markInterrupted/clearStreaming/failTurn/finalize` stay as-is):

```kotlin
    val syncer = SessionSyncer(
        db = db,
        client = client,
        isRunActive = { activeRuns[it]?.isActive == true },
        visibleThreadId = { visibleThreadId },
    )

    suspend fun syncNow(): ApiResult<Unit> = syncer.syncOnce()

    suspend fun createThread(firstMessage: String, profile: AgentProfile? = null): String? {
        val session = when (val result = client.createSession()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> {
                if (result.error is ApiError.Auth) onAuthFailure((result.error as ApiError.Auth).code)
                return null
            }
        }
        val now = clock()
        threadDao.upsertThread(
            ThreadEntity(
                id = session.id,
                title = firstMessage.snippet(80),
                preview = firstMessage.snippet(120),
                state = "idle",
                pinned = false,
                unread = false,
                createdAt = now,
                lastActiveAt = now,
                source = session.source,
                agentName = profile?.name,
                agentGlyph = profile?.glyph,
            ),
        )
        sendMessage(session.id, firstMessage)
        return session.id
    }

    suspend fun sendMessage(threadId: String, text: String, viaButton: Boolean = false) {
        val thread = threadDao.getThread(threadId) ?: return
        val now = clock()
        val turnId = newId()
        turnDao.upsertTurn(
            TurnEntity(
                id = turnId, threadId = threadId, seq = (turnDao.maxSeq(threadId) ?: 0) + 1,
                kind = "user", createdAt = now, markdown = text, blocksJson = null,
                traceJson = null, runId = null, sendState = "sending", viaButton = viaButton,
            ),
        )
        threadDao.upsertThread(thread.copy(state = "running", lastActiveAt = now, preview = text.snippet(120)))
        launchChat(threadId, turnId, text)
    }

    suspend fun retryTurn(turnId: String) {
        val turn = turnDao.getTurn(turnId) ?: return
        if (turn.kind != "user") return
        turnDao.upsertTurn(turn.copy(sendState = "sending"))
        threadDao.getThread(turn.threadId)?.let {
            threadDao.upsertThread(it.copy(state = "running", lastActiveAt = clock()))
        }
        // dedup: if the server already has this message, don't send it twice (spec §3)
        val onServer = (client.sessionMessages(turn.threadId) as? ApiResult.Ok)
            ?.value?.any { it.role == "user" && it.content == turn.markdown } == true
        if (onServer) {
            turnDao.getTurn(turnId)?.let { turnDao.upsertTurn(it.copy(sendState = "synced")) }
            activeRuns[turn.threadId]?.cancel()
            activeRuns[turn.threadId] = scope.launch { recoverByPolling(turn.threadId, turnId) }
        } else {
            launchChat(turn.threadId, turnId, turn.markdown.orEmpty())
        }
    }

    /** Propagates to the server first; local state only changes on success (spec §4). */
    suspend fun renameThread(threadId: String, title: String): Boolean =
        when (val result = client.renameSession(threadId, title)) {
            is ApiResult.Ok -> {
                threadDao.getThread(threadId)?.let { threadDao.upsertThread(it.copy(title = title)) }
                true
            }
            is ApiResult.Err -> {
                if (result.error is ApiError.Auth) onAuthFailure((result.error as ApiError.Auth).code)
                false
            }
        }

    suspend fun deleteThread(threadId: String): Boolean {
        val result = client.deleteSession(threadId)
        val gone = result is ApiResult.Ok ||
            (result as? ApiResult.Err)?.error.let { it is ApiError.Client && it.status == 404 }
        if (gone) {
            activeRuns.remove(threadId)?.cancel()
            clearStreaming(threadId)
            threadDao.deleteThread(threadId)
        } else if ((result as ApiResult.Err).error is ApiError.Auth) {
            onAuthFailure((result.error as ApiError.Auth).code)
        }
        return gone
    }

    /** On thread open / process restart: a thread stuck `running` recovers via message polling. */
    suspend fun recoverIfRunning(threadId: String) {
        if (activeRuns[threadId]?.isActive == true) return
        val thread = threadDao.getThread(threadId) ?: return
        if (thread.state != "running") return
        val userTurn = turnDao.getTurns(threadId).lastOrNull { it.kind == "user" }
        if (userTurn == null) {
            threadDao.upsertThread(thread.copy(state = "idle"))
            return
        }
        activeRuns[threadId] = scope.launch { recoverByPolling(threadId, userTurn.id) }
    }

    // ---- chat lifecycle ------------------------------------------------------

    private suspend fun launchChat(threadId: String, userTurnId: String, text: String) {
        activeRuns[threadId]?.cancel()
        activeRuns[threadId] = scope.launch { chatTurn(threadId, userTurnId, text) }
    }

    private suspend fun chatTurn(threadId: String, userTurnId: String, text: String) {
        val events = mutableListOf<RunEvent>()
        var runId = ""
        var completed: RunEvent.RunCompleted? = null
        var streamError: ApiError? = null
        setStreaming(StreamingRun(threadId, runId, "", null, interrupted = false))
        try {
            client.chatStream(threadId, text).collect { event ->
                when (event) {
                    is RunEvent.RunStarted -> {
                        runId = event.runId
                        turnDao.getTurn(userTurnId)?.let {
                            turnDao.upsertTurn(it.copy(sendState = "synced", runId = event.runId))
                        }
                    }
                    is RunEvent.RunCompleted -> completed = event
                    else -> Unit
                }
                events += event
                setStreaming(streamingFrom(threadId, runId, events))
            }
        } catch (e: HermesStreamException) {
            streamError = e.apiError
        }
        try {
            completed?.let {
                finalize(threadId, runId, it.output, events)
                syncNow() // reconcile ids + siblings right after our own run (spec §2)
                return
            }
            if (streamError is ApiError.Auth) {
                onAuthFailure((streamError as ApiError.Auth).code)
                failTurn(threadId, userTurnId)
                return
            }
            val started = turnDao.getTurn(userTurnId)?.sendState == "synced"
            if (started) recoverByPolling(threadId, userTurnId) else failTurn(threadId, userTurnId)
        } finally {
            clearStreaming(threadId)
        }
    }

    /**
     * A POST stream can't be re-attached, so a dropped chat recovers by polling
     * the session's message history until the assistant reply lands (spec §3).
     */
    private suspend fun recoverByPolling(threadId: String, userTurnId: String) {
        markInterrupted(threadId)
        val backoff = backoffFactory()
        var polls = 0
        while (polls < MAX_RECOVERY_POLLS) {
            polls++
            when (val result = client.sessionMessages(threadId)) {
                is ApiResult.Err -> {
                    val error = result.error
                    if (error is ApiError.Auth) {
                        onAuthFailure(error.code)
                        failTurn(threadId, userTurnId)
                        return
                    }
                    if (error is ApiError.Client && error.status == 404) {
                        // session deleted remotely — the thread is gone
                        threadDao.deleteThread(threadId)
                        clearStreaming(threadId)
                        return
                    }
                }
                is ApiResult.Ok -> {
                    val messages = result.value
                    val userTurn = turnDao.getTurn(userTurnId)
                    val userIndex = messages.indexOfLast {
                        it.role == "user" && it.content == userTurn?.markdown
                    }
                    val replied = userIndex >= 0 && messages.drop(userIndex + 1).any {
                        it.role == "assistant" && !it.content.isNullOrEmpty()
                    }
                    if (replied) {
                        turnDao.deleteTurnsFor(threadId)
                        turnDao.upsertTurns(sessionTurns(threadId, messages))
                        val now = clock()
                        threadDao.getThread(threadId)?.let {
                            threadDao.upsertThread(
                                it.copy(
                                    state = "idle",
                                    preview = (messages.lastOrNull { m -> !m.content.isNullOrEmpty() }
                                        ?.content ?: "").snippet(120),
                                    lastActiveAt = now,
                                    unread = visibleThreadId != threadId,
                                ),
                            )
                        }
                        clearStreaming(threadId)
                        return
                    }
                }
            }
            delay(backoff.nextDelayMillis())
        }
        failTurn(threadId, userTurnId)
    }
```

Constants: replace `MAX_POLL_FAILURES` with `const val MAX_RECOVERY_POLLS = 20`. Delete `buildInput`, `runTurn`, `streamLoop`, and the `WireMessage`/`RunStatus` imports. Note `ApiError.Client` — check its actual constructor in `ApiError.kt` (status/message field names) and match.

  Then `NewThreadViewModel`: `createThread` now returns `String?` — on null, stay on the compose screen and surface the same error state the screen already uses for failures (adapt to the file's current shape; it has uncommitted edits — do not revert them). Update `NewThreadViewModelTest` accordingly.

- [ ] **Step 3: Verify** — full suite green.
- [ ] **Step 4: Commit** — `git commit -m "feat!: send path over sessions chat/stream with message-poll recovery"`.

---

### Task 10: Wiring — capabilities gate, foreground sync scheduler

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/domain/model/ConnState.kt`
- Modify: `app/src/main/java/net/liquidx/leman/data/repo/ConnectionManager.kt`
- Modify: `app/src/main/java/net/liquidx/leman/ui/components/Chrome.kt` (StatusRow branch)
- Create: `app/src/main/java/net/liquidx/leman/data/repo/SyncScheduler.kt`
- Modify: `app/src/main/java/net/liquidx/leman/di/AppContainer.kt`, `app/src/main/java/net/liquidx/leman/LemanApp.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (lifecycle-process)
- Test: `app/src/test/java/net/liquidx/leman/data/repo/ConnectionManagerTest.kt`, `app/src/test/java/net/liquidx/leman/data/repo/SyncSchedulerTest.kt` (create)

**Interfaces:**
- `ConnState.Unsupported(version: String)` — gateway reachable but lacks the Sessions API.
- `SyncScheduler(repository, connState, scope, intervalMillis = 30_000)` with `onForeground()`/`onBackground()`.

- [ ] **Step 1: Tests first.**

```kotlin
// ConnectionManagerTest additions
@Test
fun healthyGateway_withoutSessionsApi_isUnsupported() = runTest {
    client.capabilitiesResult = ApiResult.Ok(CapabilitiesDto()) // no flags
    // reconfigure with valid key/url as existing tests do
    advanceUntilIdle()
    assertTrue(manager.state.value is ConnState.Unsupported)
}

@Test
fun capabilities404_oldGateway_isUnsupported() = runTest {
    client.capabilitiesResult = ApiResult.Err(ApiError.Client(404, "no capabilities"))
    advanceUntilIdle()
    assertTrue(manager.state.value is ConnState.Unsupported)
}
```

```kotlin
// SyncSchedulerTest (plain runTest, no Robolectric — inject a fake syncNow recorder)
@Test
fun foreground_syncsImmediately_thenEveryInterval_whileOnline() = runTest { … }
@Test
fun background_stopsTicking() = runTest { … }
@Test
fun offline_ticksSkipSync() = runTest { … }
```

To keep `SyncScheduler` testable without Robolectric, give it a `syncNow: suspend () -> Unit` lambda instead of the whole repository:

```kotlin
class SyncScheduler(
    private val syncNow: suspend () -> Unit,
    private val connState: kotlinx.coroutines.flow.StateFlow<ConnState>,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 30_000,
) {
    private var job: Job? = null

    fun onForeground() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                if (connState.value is ConnState.Online) syncNow()
                delay(intervalMillis)
            }
        }
    }

    fun onBackground() {
        job?.cancel()
        job = null
    }
}
```

- [ ] **Step 2: Implement.**
  - `ConnState.kt`: add `data class Unsupported(val version: String) : ConnState`.
  - `ConnectionManager.applyResult` Ok branch becomes:
    ```kotlin
    is ApiResult.Ok -> {
        backoff.reset()
        val caps = client.capabilities()
        _state.value = if (caps is ApiResult.Ok && caps.value.supportsSessions) {
            ConnState.Online(result.value.version)
        } else {
            ConnState.Unsupported(result.value.version)
        }
    }
    ```
  - `Chrome.kt` StatusRow `when`: add `is ConnState.Unsupported -> Triple(LemanColors.danger, "hermes · gateway lacks sessions api", false)`. Check other exhaustive `when(connState)` sites: `grep -rn "ConnState\." app/src/main app/src/test` and add the branch wherever the compiler demands.
  - `libs.versions.toml`: `androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }`; `build.gradle.kts`: `implementation(libs.androidx.lifecycle.process)`.
  - `AppContainer`: `val syncScheduler by lazy { SyncScheduler(syncNow = { threadRepository.syncNow() }, connState = connectionManager.state, scope = appScope) }`.
  - `LemanApp.kt` (application class): register
    ```kotlin
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = container.syncScheduler.onForeground()
        override fun onStop(owner: LifecycleOwner) = container.syncScheduler.onBackground()
    })
    ```
    (adapt to how `LemanApp` exposes its container).

- [ ] **Step 3: Verify** — full suite; also `./gradlew :app:assembleDebug` compiles.
- [ ] **Step 4: Commit** — `git commit -m "feat: capabilities gate and foreground sync scheduler"`.

---

### Task 11: UI — source tags, clear-local-cache copy

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/ui/components/ThreadList.kt` (ThreadRow)
- Modify: `app/src/main/java/net/liquidx/leman/ui/threads/ThreadsViewModel.kt` (+ `ThreadsScreen.kt` call site)
- Modify: `app/src/main/java/net/liquidx/leman/ui/config/ConfigScreen.kt` (copy only)
- Test: `app/src/test/java/net/liquidx/leman/ui/threads/*`, screenshot goldens

**Interfaces:**
- `ThreadRow` gains `sourceLabel: String? = null`, rendered as faint meta text before `stateLabel`.
- Threads list item model gains `sourceLabel: String?` = `source.takeIf { it != "api_server" }` (so `cron`/`cli`/… show verbatim, app threads show nothing).

- [ ] **Step 1: ViewModel test first** — in the ThreadsViewModel test, a thread with `source = "cron"` yields an item with `sourceLabel == "cron"`; `source = "api_server"` yields null.
- [ ] **Step 2: Implement** — add the param to `ThreadRow` (faint `LemanType.meta` text in `LemanColors.textFaint`, matching how `stateLabel` is laid out), thread the value through the ThreadsViewModel item model and the `ThreadRow(...)` call in `ThreadsScreen.kt`. In `ConfigScreen.kt` change the button copy `"clear all threads"` → `"clear local cache"` (the armed-confirm string keeps its "tap again to confirm" form) and update `ConfigViewModelTest` if it asserts the label. Both screens carry uncommitted edits — modify in place, don't revert.
- [ ] **Step 3: Screenshots** — run the suite; if Roborazzi diffs are expected (row layout changed), re-record: `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`, then verify green. Eyeball the changed goldens before committing them.
- [ ] **Step 4: Commit** — `git commit -m "feat: source tags on synced threads; clear-local-cache copy"` (include re-recorded goldens).

---

### Task 12: Debug fake gateway serves sessions

**Files:**
- Modify: `app/src/debug/java/net/liquidx/leman/debug/FakeHermesServer.kt`
- Modify: `app/src/debug/java/net/liquidx/leman/debug/SampleCorpus.kt` (only if it lacks reusable markdown for seeds)
- Test: manual — DEBUG panel run

**Interfaces:**
- `FakeHermesServer` implements the full session surface with an in-memory `ConcurrentHashMap<String, MutableList<SessionMessageDto>>`, seeded with three sessions: a `cron`-source "daily digest" (uses an existing SampleCorpus markdown as the assistant message), an `api_server` conversation (geneva options corpus), and an empty session. `chatStream` translates the existing `scriptFor(scenario, userText)` events into the chat vocabulary: prepend `RunEvent.RunStarted("fake-run-N", 0.5)`, keep deltas/tools, and append messages to the session store on completion so subsequent syncs see them. `listSessions` returns seeded + created sessions sorted by lastActive desc, honoring limit/offset with `hasMore`. `createSession`/`renameSession`/`deleteSession` mutate the map. Capabilities returns both session flags true.

- [ ] **Step 1: Implement** the above (this is debug-only code; unit coverage comes free via the DEBUG panel's usage, and the testutil fake covers repo logic).
- [ ] **Step 2: Manual verify** — `./gradlew :app:assembleDebug`, then in the debug app: switch GATEWAY to fake, confirm the seeded cron session appears in the thread list with a `cron` tag, open it, send a message in a new thread, watch it stream, kill/reopen to confirm recovery, and confirm "clear local cache" repopulates after the next tick.
- [ ] **Step 3: Commit** — `git commit -m "feat: fake gateway serves sessions for debug + demos"`.

---

### Task 13: Cleanup — delete the runs-API send surface, rewrite specs

**Files:**
- Modify: `app/src/main/java/net/liquidx/leman/data/remote/HermesClient.kt`, `OkHttpHermesClient.kt`, `Dto.kt`, `SseReader.kt`, `RunEventParser.kt`
- Modify: `app/src/test/java/net/liquidx/leman/testutil/FakeHermesClient.kt` and affected tests
- Modify: `docs/spec/01-architecture.md`, `docs/spec/02-server-api.md`, `docs/spec/03-sync-and-cache.md`

- [ ] **Step 1: Delete** `startRun`, `getRun`, `runEvents` from the interface and all implementations (production, testutil fake, debug fake, `SwitchableHermesClient`); delete `RunRequestDto`, `RunAcceptedDto`, `RunDto`, `RunStatus`, `WireMessage`, `readSseDataFrames`, `parseRunEvent`, and their dedicated tests (`RunEventParserTest`, the data-frame cases in `SseReaderTest`, run-DTO cases in `DtoTest`, `Fixtures.kt` entries). Keep `RunEvent.Reasoning` (TraceComposer + fakes still emit it). `grep -rn "startRun\|getRun\|runEvents\|WireMessage\|RunDto\|readSseDataFrames\|parseRunEvent(" app/src` must come back empty outside git history.
- [ ] **Step 2: Rewrite specs.** `02-server-api.md`: replace the runs-API send contract with the Sessions API surface (copy endpoint shapes from `docs/superpowers/specs/2026-07-17-session-sync-design.md` §Verified API contract). `03-sync-and-cache.md`: invert the storage story — server is the system of record, Room is a cache, sidecar fields listed, sync triggers and rebuild semantics documented. `01-architecture.md`: update the component diagram/text for `SessionSyncer` + `SyncScheduler`. Keep each doc's existing voice and structure.
- [ ] **Step 3: Full suite + assembleDebug**, expected green.
- [ ] **Step 4: Commit** — `git commit -m "refactor!: drop runs-API send surface; specs describe sessions architecture"`.

---

## Plan Self-Review (done at write time)

- **Spec coverage:** decisions table → Tasks: server-truth rebuild (6–8), cadence (10), mutations (9), source labels (11), full send migration (9, 13), capabilities gate (10), fake gateway + DEBUG (12), spec docs (13), destructive migration (6). Unread semantics + title fallback in 8. Retry dedup + 404-deleted-session handling in 9.
- **Type consistency:** `SseFrame`/`readSseFrames` (T2) consumed in T3/T5; `parseChatEvent` (T3) in T5; `sessionTurns` (T7) in T8/T9; `getThreads`/`deleteTurnsFor` (T6) in T8/T9; `SessionDto` fields match T1 across all tasks; `createThread(): String?` and Boolean mutations (T9) consumed by ViewModel updates in T9/T11.
- **Known judgment calls an implementer may hit:** `ApiError.Client` field names (verify in `ApiError.kt`); `LemanApp` container access pattern; ThreadsViewModel item model name. Each is flagged inline where it occurs.
