# 02 — Server API: connection, streaming, updates

## Status of this contract

The repo README says the gateway is **OpenAI-compatible**. Chat streaming therefore
follows the OpenAI SSE shape (`chat.completion.chunk` deltas). Threads, traces, and
thread-list events are **Hermes extensions**; the contract below is the client's
assumption and must be verified against the running gateway (`https://hermes.liquidx.net`)
before implementation hardens. Everything wire-specific lives in `data/remote` so a
contract correction touches one package: DTOs, `HermesClient`, and the DTO→domain mappers.

## Transport

- OkHttp singleton, HTTP/2, gzip. Timeouts: connect 10s, read 30s (REST) / none (SSE),
  write 15s.
- Base URL and API key come from settings; changing either tears down and rebuilds the
  client (`ConnectionManager.reconfigure()`).
- Auth: `Authorization: Bearer <apiKey>` on every request.
- Headers: `Accept: application/json` (REST) / `text/event-stream` (SSE),
  `User-Agent: leman-android/<versionName>`.
- Cleartext HTTP is allowed **only** in debug builds (for the mock server / LAN
  gateways) via a debug-only network security config.

## Endpoints

| Method & path | Purpose |
|---|---|
| `GET /v1/health` | connectivity + latency probe; also used by "test connection" in 2d. Returns `{ status, protocol: "v2", streaming: true }` |
| `GET /v1/threads?limit=50&cursor=&updated_after=` | thread summaries, newest-activity first, cursor-paginated |
| `POST /v1/threads` `{ message, agent_profile? }` | create thread from first user message; returns full thread |
| `GET /v1/threads/{id}` | thread detail (summary + agent profile) |
| `GET /v1/threads/{id}/turns?after_seq=` | ordered turns; `after_seq` for incremental fetch |
| `POST /v1/threads/{id}/messages` `{ text, client_id, via_button? }` | append a user turn |
| `PATCH /v1/threads/{id}` `{ pinned?, read? }` | client-visible flags, if server-side; else pins/read are local-only (03) |
| `DELETE /v1/threads` / `GET /v1/threads/export` | 2d DATA actions: clear all / export |
| `GET /v1/events` (SSE) | global stream: thread created/updated/state-changed |
| `GET /v1/threads/{id}/stream` (SSE) | per-thread stream: turn deltas, trace steps, state |

### Wire models (DTOs)

```kotlin
@Serializable data class ThreadDto(
    val id: String, val title: String, val preview: String,
    val state: String,                    // running | needs_you | done | idle | scheduled
    val progress: ProgressDto?,           // { step, total } when running
    val unread: Boolean = false, val pinned: Boolean = false,
    val lastActiveAt: Instant, val seq: Long,          // monotonic per-thread version
    val agentProfile: AgentProfileDto?,   // { name, glyph, profileLabel } — nullable
)

@Serializable data class TurnDto(
    val id: String, val threadId: String, val seq: Long,
    val kind: String,                     // user | agent | trace
    val createdAt: Instant, val viaButton: Boolean = false,
    val markdown: String?,                // user/agent turns
    val blocks: List<BlockDto> = emptyList(),   // structured agent blocks (05)
    val trace: TraceDto?,                 // kind == trace
)

@Serializable data class TraceDto(
    val stepCount: Int, val durationMs: Long,
    val toolHistogram: Map<String, Int>,  // { "ci.logs": 3, "repo.search": 2 }
    val steps: List<TraceStepDto>,        // { kind: tool|reasoning, tool?, argsSummary, resultSummary }
)
```

Unknown JSON fields are ignored (`ignoreUnknownKeys = true`); unknown enum strings map to
explicit `Unknown` domain values rather than crashing — the gateway will evolve faster
than the app.

## ConnectionManager

Owns the app-wide connection state shown in the status row (`▪ hermes · connected`).

```kotlin
sealed interface ConnState {
    data object Disconnected : ConnState              // no config yet
    data object Connecting : ConnState
    data class Connected(val latencyMs: Int, val protocol: String) : ConnState
    data class Degraded(val error: ApiError) : ConnState   // REST ok, stream down
    data class Failed(val error: ApiError) : ConnState     // auth/server/network failure
}
class ConnectionManager { val state: StateFlow<ConnState>; fun reconfigure(); suspend fun probe(): Result<Health> }
```

- On app start / reconfigure: `probe()` health, then let `SyncEngine` open streams.
- Latency = health round-trip, refreshed opportunistically; drives `connected · 42ms` in 2d.
- State machine feeds every screen's status row via `UiState`.

## Streaming (SSE)

Both streams use `okhttp-sse`'s `EventSource`, wrapped as a cold `Flow<HermesEvent>`
(`callbackFlow`), parsed with kotlinx.serialization.

**Global stream** `GET /v1/events` — kept open while app is foregrounded:

```
event: thread.updated        data: ThreadDto        (upsert into cache)
event: thread.created        data: ThreadDto
event: thread.deleted        data: { id }
```

**Per-thread stream** `GET /v1/threads/{id}/stream` — open only for the thread on
screen. Carries turn-level granularity, OpenAI-chunk-style:

```
event: turn.started    data: { turnId, kind, seq, createdAt }
event: turn.delta      data: { turnId, deltaMarkdown }           // agent text streaming in
event: trace.step      data: { turnId, step: TraceStepDto }      // running trace grows
event: turn.completed  data: TurnDto                             // authoritative final turn
event: thread.state    data: { state, progress? }
: heartbeat                                                      // comment ping ~25s
```

Client rules:

- Deltas accumulate into a `StreamingTurn` held in memory by `ThreadViewModel`;
  `turn.completed` **replaces** the accumulated turn and is what gets persisted to Room.
  Deltas are never persisted — a resumed session refetches instead.
- Reconnect uses `Last-Event-ID` (server events carry `id: <seq>`) so a dropped stream
  resumes without gaps; if the server can't honor it, client falls back to
  `GET turns?after_seq=<last cached seq>` then re-opens the stream.
- Heartbeat watchdog: if nothing (including comments) arrives for 60s, treat the stream
  as dead and reconnect.
- Reconnect backoff: 1s, 2s, 4s… cap 30s, ±20% jitter; reset on success. While
  reconnecting, `ConnState.Degraded` — UI shows cached data and an amber status dot,
  no blocking spinner.

**Sending while streaming:** `POST messages` carries a client-generated `client_id`
(UUID). The optimistic local turn (03) is reconciled when the same `client_id` comes
back on `turn.completed`, so the echo isn't duplicated.

## Update strategy summary

| Data | Fresh via | Fallback |
|------|-----------|----------|
| Thread list | global SSE upserts | pull-to-refresh + refetch on `STARTED` |
| Open thread | per-thread SSE | `turns?after_seq` catch-up on stream (re)open |
| Connection health | probe on start/reconfigure + stream liveness | manual "test connection" (2d) |

If the gateway turns out not to provide `/v1/events`, the fallback *is* the strategy:
poll `GET /v1/threads?updated_after=<t>` every 20s while the list is visible, and rely
on the per-thread stream (which chat-style OpenAI gateways do have) for the open thread.
`SyncEngine` isolates this decision behind `startListWatch()`.
