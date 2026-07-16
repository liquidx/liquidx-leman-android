# Server-synced threads: Sessions API migration

**Date:** 2026-07-17
**Status:** Approved

## Problem

Threads started outside the app (dashboard, CLI, cron jobs, other API clients) never appear in
the app, and threads are never refreshed from the server. The app was built on the assumption
that the gateway has no conversation store, so Room is the system of record and each run carries
full history client-side.

That assumption is wrong. The gateway exposes a **Sessions API** under `/api/sessions/*`
(note: `/api` prefix, not `/v1`) that stores every session and its full message history,
regardless of which client created it. All endpoints below were verified live against
`https://api.gent.ino.ink` (gateway 0.18.0) on 2026-07-17.

## Decisions

| Question | Decision |
|---|---|
| Sync model | **Server is truth.** All threads rebuild from server sessions; Room becomes a cache. |
| Cadence | **Foreground-active.** Sync on app open, every 30s while foregrounded, and after each own run completes. No background work. |
| App-side mutations | **Propagate.** Rename → `PATCH`, delete → `DELETE`. Pin/unread stay local-only. |
| Session sources | **All shown**, with a faint source tag on non-app sessions (`cron`, `cli`, …). |
| Send path | **Full migration** to `POST /api/sessions/{id}/chat/stream`; drop `/v1/runs` for sending. |

## Verified API contract

### Session list and history

- `GET /api/sessions?limit&offset&source&include_children` →
  `{"object":"list","data":[…],"limit","offset","has_more"}`. Each session:
  `id`, `source` (`api_server` | `cron` | …), `model`, `title` (nullable), `preview`,
  `started_at`/`ended_at`/`last_active` (float epoch seconds), `message_count`,
  `tool_call_count`, token counts, `parent_session_id`.
- `GET /api/sessions/{id}/messages` → full history. Each message: integer `id`, `role`
  (`user` | `assistant` | `tool`), `content`, `tool_calls` (OpenAI-style function calls),
  `tool_call_id`, `tool_name`, `reasoning`/`reasoning_content`, `timestamp` (float epoch),
  `finish_reason`. **Includes original user prompts.**
- `POST /api/sessions` `{}` → `{"object":"hermes.session","session":{…}}` (note: nested under
  `session`). New ids look like `api_<epoch>_<hex>`; sessions created implicitly by
  `POST /v1/runs` reuse the run id (`run_<hex>`).
- `PATCH /api/sessions/{id}` — update `title` / `end_reason`.
- `DELETE /api/sessions/{id}` → `{"object":"hermes.session.deleted","id":…,"deleted":true}`.
- `GET /v1/capabilities` — feature flags (`session_resources`, `session_chat`,
  `session_chat_streaming`, …) plus an endpoint map. Check at connect time; a gateway without
  the Sessions API gets a clear "gateway too old" error state, not silent breakage.

### Chat

- `POST /api/sessions/{id}/chat` `{"message": "…"}` → synchronous
  `{"object":"hermes.session.chat.completion","session_id","message":{role,content},"usage"}`.
- `POST /api/sessions/{id}/chat/stream` — SSE with **named events** (`event:` line + `data:`
  line, monotonic `seq`, every frame carries `session_id`/`run_id`/`ts`):
  - `run.started {user_message, run_id}` — server accepted the message
  - `message.started {message:{id,role}}`
  - `tool.started {tool_name, preview, args}` / `tool.progress {tool_name, delta}`
    (`_thinking` pseudo-tool streams reasoning) / `tool.completed {tool_name}`
  - `assistant.delta {delta, message_id}`
  - `assistant.completed {content, completed, partial, interrupted}`
  - `run.completed {messages:[…full turn incl. tool_calls, reasoning…], usage}`
  - `done`
- **Server-side context continuity works on this path** (verified: fact from turn 1 recalled in
  turn 2). `POST /v1/runs` with `session_id` does *not* replay history into context — one more
  reason to migrate fully.
- Nonexistent session → 404 envelope `{"error":{…,"code":"session_not_found"}}`.

## Design

### 1. Data model (Room becomes a cache)

- `ThreadEntity.id` **is the server session id.** Client-generated UUIDs go away.
- New server-owned columns: `source`; `title` falls back to a `preview` snippet when the server
  title is null. `sessionId` column is dropped (the id *is* the session id).
- **Local-only sidecar columns preserved across sync:** `pinned`, `unread`, `agentName`,
  `agentGlyph`. Sync upserts must read-modify-write so these survive.
- `TurnEntity` rows for a synced thread are **rebuilt wholesale** from `/messages`:
  - `role:user` → `user` turn (sendState `synced`)
  - `role:assistant` with non-empty `content` → `agent` turn
  - `role:tool` messages + assistant `tool_calls` + `reasoning` fields → composed into the
    existing `Trace` model → `trace` turn preceding the agent turn
  - Turn ids derive from server message ids (`msg-<sessionId>-<messageId>`) so rebuilds are
    idempotent.
- **Migration:** destructive Room migration + immediate resync. Accepted losses at this stage of
  the app's life: pin/agent-override state on existing threads, and local threads whose first
  send never reached the server.

### 2. Sync engine

`SessionSyncer` (owned by `ThreadRepository`, injected via `AppContainer`):

- `syncOnce()`:
  1. Page through `GET /api/sessions` (limit 50) until `has_more` is false.
  2. Upsert every session into `threads`, preserving sidecar columns.
  3. For sessions whose `last_active` or `message_count` differs from the local row (or that are
     new), fetch `/messages` and rebuild turns.
  4. Delete local threads absent from the full server id set.
  5. Skip any thread with an in-flight local run; it reconciles after the run completes.
- Triggers: app foreground (ProcessLifecycleOwner), a 30-second ticker while foregrounded,
  and after each own-run completion. Failures back off via the existing `Backoff` and surface
  through `ConnState`; sync never toasts or interrupts.
- Unread: a synced thread whose `last_active` advanced while it wasn't the visible thread gets
  `unread = true`, matching existing semantics.

### 3. Send path

- `createThread(firstMessage)` → `POST /api/sessions` → use returned id as the thread id →
  proceed as `sendMessage`.
- `sendMessage` → optimistic local user turn (`sending`) → `POST /api/sessions/{id}/chat/stream`:
  - `run.started` → user turn `synced`, keep `run_id` on the turn for correlation
  - `assistant.delta` / `tool.*` → accumulate in the existing `StreamingRun` state (text +
    live trace); `tool.progress` on `_thinking` feeds reasoning
  - `run.completed` → finalize trace + agent turns locally immediately (no wait for next sync),
    using server message content. These locally finalized turns carry temporary ids; the next
    sync's idempotent rebuild replaces them with message-id-derived rows.
- `RunEventParser`/`SseReader` grow support for `event:`-named frames; the domain `RunEvent`
  vocabulary extends accordingly (`ToolProgress`, `AssistantCompleted`, run-started carrying
  run id).
- **Recovery** (process death / dropped socket, thread stuck `running`): a POST stream cannot be
  re-attached, so poll `GET /api/sessions/{id}/messages` with backoff until an assistant message
  newer than our user turn appears (or the poll budget is exhausted → `failed`).
- **Retry** a failed turn: first check `/messages`; only resend if the user message never
  reached the server (prevents duplicates).
- `HermesClient` drops `startRun`/`getRun`/`runEvents` once nothing references them.

### 4. Mutations & UI

- `renameThread` → `PATCH`; `deleteThread` → `DELETE`; both update Room on success and surface
  errors via the existing error mapping. Pin and read-state remain purely local.
- Config's "clear all threads" is redefined as **"clear local cache"** (threads repopulate on
  next sync). No mass server deletion from the app.
- `ThreadRow` and the thread header show a faint source tag for non-`api_server` sources
  (e.g. `cron`). Everything else is visually unchanged.
- "export threads" keeps working — it exports the local cache.

### 5. Error handling

- Existing `ApiError` mapping is reused for all new endpoints; `session_not_found` on chat or
  messages means the session was deleted remotely → delete the local thread.
- Missing Sessions API (old gateway, per `/v1/capabilities`) → explicit `ConnState` error
  ("gateway does not support sessions"), app otherwise inert rather than broken.

### 6. Testing

- Fake gateway (debug + tests) grows sessions state: list/messages/create/patch/delete and a
  scripted `chat/stream`. Sample corpus becomes fake *server* sessions so DEBUG panel exercises
  sync end-to-end.
- Repository tests: sync (new/changed/deleted sessions, sidecar preservation, in-flight skip),
  send lifecycle over chat/stream, recovery-by-polling, retry dedup, rename/delete propagation.
- ViewModel + screenshot tests updated for source tags; `ConfigViewModel` test for the
  clear-cache rename.
- Spec docs `01-architecture`, `02-server-api`, `03-sync-and-cache` rewritten for the inverted
  model (Room: primary → cache).

## Out of scope

- Background sync (WorkManager) and push — revisit if foreground-active feels stale.
- `session_fork`, approvals (`run_approval`), `/v1/skills`, `/v1/toolsets`, multimodal/images.
- Two-way pin/read sync (server has no such concepts).
- Mass server deletion from the app.
