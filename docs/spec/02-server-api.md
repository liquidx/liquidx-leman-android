# 02 — Server API: connection, sessions, streaming

## Status of this contract — VERIFIED

This contract was probed live against the running gateway on 2026-07-17 (gateway
`version 0.18.0`, model id `hermes-agent`) and reflects what the server actually does, not
an assumption. Everything wire-specific still lives in `data/remote` so a future gateway
change touches one package: DTOs, `HermesClient`, and the DTO→domain mappers.

The single most important finding — and a reversal of the original design — is that **the
gateway does have a server-side session store.** It exposes a **Sessions API** under
`/api/sessions/*` (note the `/api` prefix, not `/v1`) that persists every session and its
full message history, regardless of which client created it: the dashboard, a cron job, the
CLI, or this app. That makes the **gateway the system of record for threads**; the app's
Room database is a cache rebuilt from it (03). The older `/v1/runs` run-runner API this
contract used to describe is gone from the app — the app sends exclusively through the
Sessions chat endpoints below.

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

- OkHttp singleton, HTTP/2, gzip. Timeouts: connect 10s, read 30s (REST) / none (chat
  stream), write 15s. Agent turns can take minutes, so the chat stream's socket is the one
  that matters — everything else is a short REST call.
- Base URL and API key come from settings; changing either tears down and rebuilds the
  client (`ConnectionManager.reconfigure()`).
- Headers: `Accept: application/json` (REST) / `text/event-stream` (chat stream),
  `Content-Type: application/json`, `User-Agent: leman-android/<versionName>`.
- Cleartext HTTP is allowed **only** in debug builds (mock server / LAN gateways) via a
  debug-only network security config; the real gateway is TLS.

## Capabilities gate

`GET /v1/capabilities` returns feature flags plus an endpoint map. The app requires both
`session_resources` and `session_chat_streaming` to be true before treating the gateway as
usable:

```kotlin
@Serializable data class CapabilitiesDto(
    val version: String? = null,
    val features: Map<String, JsonElement> = emptyMap(),
) {
    val supportsSessions: Boolean   // both flags true
}
```

Checked right after a successful `GET /v1/health` probe, as part of `ConnectionManager`'s
state machine (below). A gateway that answers health but lacks the Sessions API surfaces an
explicit `ConnState.Unsupported(version)` — "gateway does not support sessions" — rather than
the app silently breaking against endpoints that don't exist.

## Endpoints (verified)

| Method & path | Status | Purpose |
|---|---|---|
| `GET /v1/health` | 200 | connectivity probe + version banner (2d "test connection"). Returns `{ status, platform, version }` |
| `GET /v1/models` | 200 | model list; `data[].id` = `hermes-agent` |
| `GET /v1/capabilities` | 200 | feature flags gating the Sessions API (above) |
| `GET /api/sessions` | 200 | paginated session list — see below |
| `GET /api/sessions/{id}/messages` | 200 | full message history for one session, **including original user prompts** |
| `POST /api/sessions` | 200 | create a session, `{}` body → `{"object":"hermes.session","session":{…}}` (nested under `session`) |
| `PATCH /api/sessions/{id}` | 200 | update `title` / `end_reason` |
| `DELETE /api/sessions/{id}` | 200 | `{"object":"hermes.session.deleted","id":…,"deleted":true}` |
| `POST /api/sessions/{id}/chat` | 200 | synchronous chat turn (not used by the app — streaming is) |
| `POST /api/sessions/{id}/chat/stream` | 200 | **the send path** — SSE chat turn, named events (below) |

Nonexistent session on any `/api/sessions/{id}/*` path → 404 envelope
`{"error":{…,"code":"session_not_found"}}`.

### Session list and history

`GET /api/sessions?limit&offset&source&include_children` →
`{"object":"list","data":[…],"limit","offset","has_more"}`. Each session:

```kotlin
@Serializable data class SessionDto(
    val id: String,
    val source: String = "api_server",        // api_server | cron | cli | …
    val model: String? = null,
    val title: String? = null,                // nullable — falls back to preview (03)
    val preview: String? = null,
    @SerialName("started_at") val startedAt: Double = 0.0,     // float epoch seconds
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double = 0.0,
    @SerialName("message_count") val messageCount: Int = 0,
)
```

New session ids look like `api_<epoch>_<hex>`. Sessions created implicitly by other
clients keep whatever id that client minted (e.g. `run_<hex>` from a legacy caller).

`GET /api/sessions/{id}/messages` returns every message the gateway holds for that
session, oldest first:

```kotlin
@Serializable data class SessionMessageDto(
    val id: Long,
    val role: String,                          // user | assistant | tool
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,   // OpenAI-style function calls
    @SerialName("tool_name") val toolName: String? = null,
    val reasoning: String? = null,
    val timestamp: Double = 0.0,
    @SerialName("finish_reason") val finishReason: String? = null,
)
```

## Chat — the send path

`POST /api/sessions/{id}/chat/stream` with body `{"message": "…"}` is how every user
message reaches the agent. It replaces the old async run-and-poll flow entirely: no
separate "start" call returning an id to poll, no client-assembled history — the server
already has the full conversation for `id`, and **replays it into model context on this
call** (verified: a fact from turn 1 is recalled in turn 2 without the client resending
anything). This is strictly better than the old `session_id`-on-a-run behavior, which did
*not* auto-replay history — one more reason the migration is a full one, not a fallback.

The stream is standard-ish SSE with **named events** — `event:` line + `data:` line pairs,
a monotonic `seq`, and every frame carrying `session_id`/`run_id`/`ts`:

```
event: run.started
data: {"run_id":"run_abc","user_message":{…},"session_id":"s1","seq":1,"ts":1.0}

event: tool.started
data: {"tool_name":"web_search","preview":"query text","session_id":"s1","seq":2,"ts":2.0}

event: tool.progress
data: {"tool_name":"_thinking","delta":"…reasoning chunk…","session_id":"s1","seq":3,"ts":2.4}

event: tool.completed
data: {"tool_name":"web_search","session_id":"s1","seq":4,"ts":5.9}

event: assistant.delta
data: {"delta":"par","message_id":"m1","session_id":"s1","seq":5,"ts":6.0}

event: assistant.completed
data: {"content":"…final text…","completed":true,"partial":false,"interrupted":false,"seq":6,"ts":7.0}

event: run.completed
data: {"messages":[…full turn incl. tool_calls, reasoning…],"usage":{…},"seq":7,"ts":7.1}

event: done
data: {}
```

- `run.started` — server accepted the message; carries the server-assigned `run_id`, kept
  on the user turn for correlation and retry dedup (03).
- `message.started` — an assistant message id was allocated.
- `tool.started` / `tool.progress` / `tool.completed` — the reasoning stream rides inside
  this vocabulary: `tool.progress` on the pseudo-tool `_thinking` carries an incremental
  reasoning `delta`; real tools carry `preview`/`args` on start, no duration/error on
  completion (unlike the old run-runner events — the composer derives duration from
  timestamps instead).
- `assistant.delta` — appends to the streaming agent turn's markdown.
- `assistant.completed` — the assistant message finished (may precede `run.completed`);
  carries `interrupted` for a dropped/cancelled turn.
- `run.completed` — the full turn as persisted server-side: `messages[]` (including tool
  calls and reasoning) + `usage`. This is what gets finalized into Room immediately, without
  waiting for the next sync tick (03).
- `done` — stream end marker.

```kotlin
sealed interface RunEvent {
    val timestamp: Double
    data class MessageDelta(val delta: String, override val timestamp: Double) : RunEvent
    data class Reasoning(val text: String, override val timestamp: Double) : RunEvent
    data class ToolStarted(val tool: String, val preview: String?, override val timestamp: Double) : RunEvent
    data class ToolCompleted(val tool: String, val duration: Double, val error: Boolean, override val timestamp: Double) : RunEvent
    data class RunCompleted(val output: String, val usage: TokenUsage?, override val timestamp: Double) : RunEvent
    data class RunStarted(val runId: String, override val timestamp: Double) : RunEvent
    data class ReasoningDelta(val delta: String, override val timestamp: Double) : RunEvent
    data class AssistantCompleted(val content: String, val interrupted: Boolean, override val timestamp: Double) : RunEvent
    data class Unknown(val raw: String, override val timestamp: Double = 0.0) : RunEvent
}
```

`readSseFrames(source)` parses the `event:`/`data:` pairing (a blank line resets the
pending event name so a dangling `event:` never leaks onto a later frame);
`parseChatEvent(frame)` decodes one frame into a `RunEvent`. Malformed or unrecognized
frames become `RunEvent.Unknown` rather than killing the stream — the gateway evolves
independently.

### How events map to the UI

Unchanged from the original design (05 owns the rendering rules): deltas append to the
streaming agent turn, reasoning renders as (or folds into) the muted collapsed **trace**
turn, matched tool start/complete pairs become trace steps, and `run.completed` supplies
the authoritative final content that gets persisted.

## Mutations

- `PATCH /api/sessions/{id}` `{"title": "…"}` — rename, propagated from the app's local
  rename action (03 §4).
- `DELETE /api/sessions/{id}` — delete, propagated from the app's local delete action.
- Pin and read/unread state have no server equivalent and stay purely local (03).

## `ConnectionManager` and capabilities

```kotlin
sealed interface ConnState {
    data object NotConfigured : ConnState
    data object Checking : ConnState
    data class Online(val version: String) : ConnState        // health ok + capabilities support sessions
    data class Unsupported(val version: String) : ConnState    // health ok, gateway too old for sessions
    data class Offline(val error: ApiError) : ConnState
    data class Unauthorized(val error: ApiError) : ConnState
}
class ConnectionManager { val state: StateFlow<ConnState>; fun reconfigure(); suspend fun probe(): ApiResult<HealthDto> }
```

- On app start / reconfigure: `probe()` → `GET /v1/health`, then (on success)
  `GET /v1/capabilities`. Both flags on → `Online(version)`, drives `▪ hermes · 0.18.0` /
  `connected` in the status row. Health ok but capabilities missing → `Unsupported(version)`.
- Health probe has a 5s timeout so the status row settles fast (04).
- A `401` anywhere (chat stream included) poisons the whole app to `Unauthorized`.

## Recovery — no resumable stream

A `POST … /chat/stream` response cannot be re-attached like a GET-based SSE connection with
`Last-Event-ID`. If the socket dies mid-turn (process death, dropped connection), recovery
is by **polling `GET /api/sessions/{id}/messages`** with backoff until an assistant message
newer than the pending user turn appears, or the poll budget is exhausted → the turn fails
with a `retry` affordance (03 §3, §4).

## Update strategy summary

| Data | How it stays fresh |
|------|--------------------|
| Thread **list** | `SessionSyncer` pages `GET /api/sessions` on foreground, every 30s while foregrounded, and after each own send completes (03 §2) |
| Thread **history** | `GET /api/sessions/{id}/messages`, rebuilt wholesale into Room turns when a session's `last_active`/`message_count` changed |
| Open thread, **live send** | `POST /api/sessions/{id}/chat/stream`; poll-by-messages backstop on drop |
| Connection **health** | `GET /v1/health` + `GET /v1/capabilities` on start/reconfigure; manual "test connection" (2d) |

There is no global SSE / server push — sync is pull-based on the cadence above, which is
enough for a foreground-first app (01).
