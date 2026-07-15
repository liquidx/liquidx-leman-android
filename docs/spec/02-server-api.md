# 02 — Server API: connection, streaming, updates

## Status of this contract — VERIFIED

This contract was probed live against the running gateway on 2026-07-15 (gateway
`version 0.18.0`, model id `hermes-agent`) and reflects what the server actually does, not
an assumption. Everything wire-specific still lives in `data/remote` so a future gateway
change touches one package: DTOs, `HermesClient`, and the DTO→domain mappers.

The single most important finding: **the gateway has no server-side thread/conversation
store.** It is a stateless *agent-run runner*. There is no thread list, no global event
feed, and no server-side pins/read/history. The **app is the system of record** for
threads — which is why Room is primary storage, not a cache (03).

### Base URL & host

- Base URL is **`https://api.gent.ino.ink`** — note the `api.` subdomain.
- The bare host `gent.ino.ink` is a **different service**: a cookie-SSO web UI that
  302-redirects API paths to `/auth/login`. Pointing the app there fails with redirects,
  not JSON. The config screen's default `server_url` is the `api.` origin, and URL
  validation should accept any `https` origin the user enters (self-host friendly).

### Auth

- `Authorization: Bearer <apiKey>` on every request. The key is a single **shared static
  token** (64 hex chars), not per-user — provisioned out of band (repo `.env`
  `HERMES_API_KEY` during development; entered in 2d on device).
- Missing/invalid key → `401` with an OpenAI-style envelope:
  `{"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}`.
- No cookie, no token exchange, no login round-trip on the `api.` host.

## Transport

- OkHttp singleton, HTTP/2, gzip. Timeouts: connect 10s, read 30s (REST) / none (run
  event stream), write 15s. Agent runs can take minutes, so the **run is async** (below)
  rather than a long-held request — the socket that matters is the event stream.
- Base URL and API key come from settings; changing either tears down and rebuilds the
  client (`ConnectionManager.reconfigure()`).
- Headers: `Accept: application/json` (REST) / `text/event-stream` (events),
  `Content-Type: application/json`, `User-Agent: leman-android/<versionName>`.
- Cleartext HTTP is allowed **only** in debug builds (mock server / LAN gateways) via a
  debug-only network security config; the real gateway is TLS.

## Endpoints (verified)

| Method & path | Status | Purpose |
|---|---|---|
| `GET /v1/health` | 200 | connectivity probe + version banner (2d "test connection"). Returns `{ status, platform, version }` |
| `GET /v1/models` | 200 | model list; `data[].id` = `hermes-agent`. Used to validate a connection and populate the agent model |
| `POST /v1/runs` | 202 | **start an async agent run** (the core call). Body `{ model, input, session_id? }` → `{ run_id, status:"started" }` |
| `GET /v1/runs/{id}` | 200 | poll run status/result. Returns the `hermes.run` object |
| `GET /v1/runs/{id}/events` | 200 | **SSE event stream** for a run: streaming text, reasoning, tool steps, completion. Live while running; **replays full history for a finished run** |
| `POST /v1/chat/completions` | 200 | standard OpenAI chat (stream + non-stream). Available as a fallback / simple path; not used for the primary agent UX |
| `POST /v1/responses` | 200 | OpenAI Responses API (`output[]`, `store`). Not used by the app; noted for completeness |

Confirmed **absent** (all 404, or 405 for list): `GET /v1/runs` (no list), `/v1/threads*`,
`/v1/events`, `/openapi.json`, `/docs`. The app must not depend on any of these.

### `input` carries the conversation — history is client-managed

`POST /v1/runs` accepts `input` as **either** a plain string **or a messages array**
`[{ role, content }]`. Passing the full prior conversation as the array is how the agent
"remembers" — verified: a run given `[user, assistant, user]` correctly answered a
question about the first user message.

`session_id` (echoed back on every run, defaults to the run's own id) **groups** runs but
does **not** auto-replay history into model context — verified: a follow-up run that
passed only `session_id` (and not the history) did *not* recall an earlier fact. So:

> To advance a thread, the app POSTs a run with `input =` the thread's full turn history
> (mapped to `{role,content}`) plus the new user message. `session_id` is retained for
> correlation/telemetry, not relied on for memory.

### Wire models (DTOs)

```kotlin
@Serializable data class HealthDto(         // GET /v1/health
    val status: String,                     // "ok"
    val platform: String,                   // "hermes-agent"
    val version: String,                    // "0.18.0"
)

@Serializable data class RunAcceptedDto(    // POST /v1/runs (202)
    @SerialName("run_id") val runId: String,
    val status: String,                     // "started"
)

@Serializable data class RunDto(            // GET /v1/runs/{id}
    val `object`: String,                   // "hermes.run"
    @SerialName("run_id") val runId: String,
    val status: String,                     // started | running | completed | failed
    @SerialName("session_id") val sessionId: String,
    val model: String,
    @SerialName("created_at") val createdAt: Double,   // float epoch seconds
    @SerialName("updated_at") val updatedAt: Double,
    @SerialName("last_event") val lastEvent: String? = null,
    val output: String? = null,             // present when completed
    val usage: UsageDto? = null,
)

@Serializable data class UsageDto(
    @SerialName("input_tokens")  val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("total_tokens")  val totalTokens: Int,
)
```

`Json { ignoreUnknownKeys = true }` everywhere; unknown `status`/event strings map to an
explicit `Unknown` domain value rather than crashing — the gateway evolves independently.

## Run events (SSE) — the streaming + trace source

`GET /v1/runs/{id}/events` is a `text/event-stream`. Framing note: the server sends
**`data:`-only frames** — there are **no `id:`, `event:`, or `retry:` lines**; the event
*type* is a field *inside* each JSON payload. Parse each `data:` line as JSON and switch on
`.event`. The stream ends with a `: stream closed` comment.

```
data: {"event":"message.delta",       "run_id":…, "timestamp":…, "delta":"2"}
data: {"event":"reasoning.available",  "run_id":…, "timestamp":…, "text":"…summary…"}
data: {"event":"tool.started",         "run_id":…, "timestamp":…, "tool":"web_search", "preview":"query text"}
data: {"event":"tool.completed",       "run_id":…, "timestamp":…, "tool":"web_search", "duration":5.939, "error":false}
data: {"event":"run.completed",        "run_id":…, "timestamp":…, "output":"…final text…", "usage":{…}}
: stream closed
```

```kotlin
@Serializable sealed interface RunEvent {          // custom decoder keyed on "event"
    val timestamp: Double
    @Serializable data class MessageDelta(val delta: String, override val timestamp: Double) : RunEvent
    @Serializable data class Reasoning(val text: String, override val timestamp: Double) : RunEvent
    @Serializable data class ToolStarted(val tool: String, val preview: String?, override val timestamp: Double) : RunEvent
    @Serializable data class ToolCompleted(val tool: String, val duration: Double, val error: Boolean, override val timestamp: Double) : RunEvent
    @Serializable data class RunCompleted(val output: String, val usage: UsageDto?, override val timestamp: Double) : RunEvent
    data class Unknown(val raw: String, override val timestamp: Double = 0.0) : RunEvent
}
```

### How events map to the UI

- **`message.delta`** — appends to the streaming agent turn's markdown (05 streaming
  rules). This is the visible answer text.
- **`reasoning.available`** — the agent's thinking summary. Rendered as (or folded into)
  the **trace** turn, muted + collapsed by default (design requirement).
- **`tool.started` / `tool.completed`** — a matched pair per tool call = one **trace
  step**: tool name, `preview` (the args/query summary, gated by `show_tool_args`),
  `duration` seconds, and `error`. These build the trace table and the rollup
  (`▸ trace · N steps · web_search ×k · Xm Ys`) — 05.
- **`run.completed`** — authoritative final `output` + `usage`. Replaces the accumulated
  streaming text and is what gets persisted as the agent turn. Marks the thread idle/done.
- **`error: true`** on `tool.completed`, or a `status:"failed"` run, surfaces per 04.

## Run lifecycle & the ConnectionManager

There is no persistent app-wide socket (no global feed). "Connection" means *can we reach
the gateway and is a run in flight*. The status row reflects a lightweight health state
plus any active run.

```kotlin
sealed interface ConnState {
    data object NotConfigured : ConnState             // no url/key yet
    data object Checking : ConnState                  // health probe in flight
    data class Online(val version: String) : ConnState   // GET /v1/health ok → "hermes 0.18.0"
    data class Offline(val error: ApiError) : ConnState  // health/network failure
    data class Unauthorized(val error: ApiError) : ConnState  // 401
}
class ConnectionManager { val state: StateFlow<ConnState>; fun reconfigure(); suspend fun probe(): ApiResult<HealthDto> }
```

- On app start / reconfigure: `probe()` → `GET /v1/health`. Success = `Online(version)`
  and drives `▪ hermes · 0.18.0` / `connected` in the status row and the 2d caption.
- Health probe has a 5s timeout so the status row settles fast (04).

### Running a thread turn

`RunController` (in `data/repo`, per thread) drives one run:

1. `POST /v1/runs` with `input =` mapped history + new message → `run_id`.
2. Open `GET /v1/runs/{id}/events` as a cold `Flow<RunEvent>` (`okhttp-sse` or a raw
   buffered read of `data:` lines — the server's non-standard framing means a hand-rolled
   line reader is simplest and is unit-tested against fixtures).
3. Fold events into the in-memory streaming turn + live trace (05).
4. On `run.completed` (or stream close), persist the finalized agent turn + trace to Room,
   clear the streaming state, set thread state idle.
5. Also poll `GET /v1/runs/{id}` as a **backstop** if the event stream drops before
   `run.completed` — the run may finish server-side while the socket is gone; the poll
   recovers `output`/`usage` and the run status.

### Reconnect (no Last-Event-ID)

Because the stream carries no `id:` offsets, a dropped stream **cannot resume mid-position**.
Recovery is:

- **If the run may still be running:** re-`GET /v1/runs/{id}` for status. If still
  running, re-open `/events`. **The re-opened stream replays from the beginning** (verified
  on finished runs), so the client **resets the accumulated streaming text and rebuilds**
  from the replay rather than appending — deltas are idempotent only if you reset first.
- **If the run finished:** `GET /v1/runs/{id}` returns the full `output`; persist directly,
  skip the stream. `/events` on a finished run also still replays the whole trace, which is
  how a **cold-loaded thread lazily fetches an old run's trace** on demand.
- Backoff between reconnect attempts: 1s, 2s, 4s… cap 30s, ±20% jitter; reset on success.
  While reconnecting, status row shows an amber `connecting…` dot; the thread keeps its
  accumulated text visible (04).
- No 60s heartbeat watchdog is needed — the server sends no keepalive comments; liveness is
  the poll backstop plus the socket's own read behavior. A run that produces no events for
  an extended period is checked via `GET /v1/runs/{id}` rather than assumed dead.

## Update strategy summary

| Data | How it stays fresh |
|------|--------------------|
| Thread **list** | purely local (Room) — there is no server list to sync. Threads are created and mutated on-device |
| Open thread, **live run** | `POST /v1/runs` → `/events` stream; `GET /v1/runs/{id}` poll backstop |
| Old run's **trace** | lazily `GET /v1/runs/{id}/events` (replays) when the user expands a persisted trace that wasn't fully stored |
| Connection **health** | `GET /v1/health` on start/reconfigure + before a send; manual "test connection" (2d) |

There is deliberately **no polling of a thread list and no global SSE** — earlier drafts
assumed both; the live gateway provides neither, and the app doesn't need them because it
owns the thread list itself.
