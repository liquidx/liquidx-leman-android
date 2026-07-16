# 03 — Local store, sync & settings

## Principle: the server is the system of record

The gateway persists every session and its full message history (02) — the app didn't
create that data and isn't the only client that can. **Room is a cache, not primary
storage.** A thread's row exists because the last sync pulled it from the server, not
because the app minted it locally; the server can gain threads (dashboard, cron, CLI) or
lose them (deleted elsewhere) at any time, and the local store must converge to match.

Compose still never renders network responses directly — the UI only ever observes Room
`Flow`s — but the write path now has two sources instead of one: the app's own sends, and
`SessionSyncer` pulling from the server.

```
                    ┌── SessionSyncer.syncOnce() ── GET /api/sessions[,/messages] (periodic + post-send)
                    ▼
   HermesClient ──▶ SessionSyncer ──▶ Room ──Flow──▶ ViewModel ──▶ Compose
                    ▲                                   │
   ThreadRepository.sendMessage() ─ POST chat/stream ───┘ (finalizes locally immediately, syncs after)
```

## What a "thread" is

A thread **is** a server session. `ThreadEntity.id` is the server session id — there are no
more client-generated UUIDs for thread identity. The row's `title`/`preview`/`state` are
derived (from the session and its messages), and a handful of columns are **local-only
sidecar state** that has no server equivalent and must survive every sync rebuild:
`pinned`, `unread`, `agentName`, `agentGlyph`.

## Room schema

```kotlin
@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,          // server session id
    val title: String,                   // session.title, falling back to a preview snippet when null
    val preview: String,                 // last turn snippet, maintained on write
    val state: String,                   // idle | running | failed  (client-owned)
    val pinned: Boolean,                 // local-only sidecar
    val unread: Boolean,                 // local-only sidecar
    val createdAt: Long,
    val lastActiveAt: Long,              // from session.last_active; orders the list
    val source: String,                  // api_server | cron | cli | … — server-owned
    val agentName: String?, val agentGlyph: String?,   // local-only sidecar, per-thread identity override
)

@Entity(tableName = "turns",
        indices = [Index("threadId", "seq")],
        foreignKeys = [ForeignKey(ThreadEntity::class, parentColumns = ["id"],
                                   childColumns = ["threadId"], onDelete = CASCADE)])
data class TurnEntity(
    @PrimaryKey val id: String, val threadId: String,
    val seq: Long,                       // client-assigned, monotonic within thread — ordering only
    val kind: String,                    // user | agent | trace
    val createdAt: Long,
    val markdown: String?,               // user text / agent output
    val blocksJson: String?,             // structured agent blocks if any (05)
    val traceJson: String?,              // composed trace (reasoning + tool steps), one column
    val runId: String?,                  // the server run that produced/acked this turn — retry dedup key
    val sendState: String,               // synced | sending | failed   (user turns)
    val viaButton: Boolean,
)
```

Notes:

- `seq` is still client-assigned and orders turns only, same as before.
- Trace steps and rich blocks stay as **one JSON column each** — read-only, always loaded
  with their turn, never queried individually.
- DB version 2. The move from client-generated thread ids to server session ids, and the
  drop of the old `sessionId` correlation column in favor of `source`, is a **destructive**
  migration (`fallbackToDestructiveMigration(dropAllTables = true)`) followed by an
  immediate resync — acceptable at this stage of the app's life; the accepted losses are
  pin/agent-override state on existing threads and any local thread whose first send never
  reached the server. Real migrations + schema-export CI check are still future work (07).

### DAO essentials

```kotlin
@Query("SELECT * FROM threads ORDER BY pinned DESC, lastActiveAt DESC")
fun observeThreads(): Flow<List<ThreadEntity>>
@Query("SELECT * FROM threads") suspend fun getThreads(): List<ThreadEntity>   // full sweep, for sync's delete pass

@Query("SELECT * FROM turns WHERE threadId = :id ORDER BY seq")
fun observeTurns(id: String): Flow<List<TurnEntity>>

@Upsert suspend fun upsertThread(t: ThreadEntity)
@Query("DELETE FROM threads WHERE id = :id") suspend fun deleteThread(id: String)
@Query("DELETE FROM turns WHERE threadId = :id") suspend fun deleteTurnsFor(id: String)   // wholesale rebuild
```

Filtering (2a) and day-grouping (`PINNED / TODAY / YESTERDAY / EARLIER`) still happen in the
ViewModel over the observed list.

## The sync engine — `SessionSyncer`

Owned by `ThreadRepository`, injected via `AppContainer`. `syncOnce()`:

1. Page through `GET /api/sessions` (limit 50) until `has_more` is false.
2. **Delete pass:** any local thread whose id isn't in the full server id set gets deleted
   — unless it has an in-flight local run, which is skipped and reconciles once that run
   completes.
3. **Upsert pass:** for each server session whose `last_active`/`message_count` differs
   from the local row (or that's new), fetch `GET /api/sessions/{id}/messages` and rebuild
   that thread's turns **wholesale**: `turnDao.deleteTurnsFor(id)` then re-insert everything
   `sessionTurns()` derives from the message list. A session with no local-state change is
   skipped entirely — sync is cheap on a quiet gateway.
4. **Sidecar preservation:** the upsert is read-modify-write — `pinned`, `agentName`,
   `agentGlyph` copy forward from the existing local row (default when the row is new).
   `unread` gets special handling below.
5. A partial failure (one session's `/messages` call errors) skips just that session; the
   next tick retries. A total failure (the list call itself errors) aborts the whole sync
   and returns the error — the caller doesn't toast or interrupt, it just tries again later.

### Turn rebuild semantics (`sessionTurns()`)

Each server message maps to zero or more turns, in order:

- `role: user` with non-empty `content` → a `user` turn, `sendState = synced`.
- `role: assistant` — its `reasoning` field and any `tool_calls` accumulate into a pending
  trace; when the message also has non-empty `content`, the accumulated trace flushes as a
  preceding `trace` turn, then the content becomes an `agent` turn. An assistant message
  with tool calls but no content yet (mid-turn) leaves its trace steps pending for the next
  message that does carry content.
- `role: tool` messages are folded into the same pending-trace accumulation.

Turn ids are derived from server message ids — `msg-<sessionId>-<messageId>` for
user/agent turns, `trace-<sessionId>-<anchor>` for trace turns — so a rebuild is
**idempotent**: re-running it against unchanged messages produces the same rows, not
duplicates.

### Sync triggers

- **App foreground.** `SyncScheduler.onForeground()` (driven by `ProcessLifecycleOwner` in
  `LemanApp`) runs an immediate `syncNow()`, then loops every 30 seconds while foregrounded,
  gated on `ConnState.Online`. Backgrounding cancels the loop outright — no sync while the
  app isn't visible, no WorkManager, no push (01).
- **Post-send.** `ThreadRepository.chatTurn()` calls `syncNow()` right after its own
  `run.completed` finalize — reconciling the thread's own id/siblings promptly rather than
  waiting up to 30s, and picking up anything else that changed server-side in the meantime.
- There is deliberately no sync on every screen open — the 30s ticker plus post-send is
  enough for a foreground-first app; a manual pull-to-refresh is future work if it turns out
  to feel stale.

### Unread semantics

A synced thread whose `last_active` advanced while it wasn't the visible thread
(`visibleThreadId() != session.id`) gets `unread = true` — matching the original
"unread when a run completes off-screen" rule, just re-derived from the server's
`last_active` instead of a local completion event. A brand-new row (first time this session
id is seen) starts **read**, so the first full sync after install doesn't flood the list
with unread markers.

## Sending — the app's own write path

`ThreadRepository.sendMessage(threadId, text, viaButton = false)`:

1. Append a user `TurnEntity` immediately: `seq = maxSeq + 1`, `sendState = sending`. UI
   shows it at once. Bump the thread to `state = running`, `lastActiveAt = now`.
2. `POST /api/sessions/{id}/chat/stream` (02). On `run.started`, flip the user turn to
   `sendState = synced` and stamp its `runId` from the event — this is the **retry dedup
   key** (below).
3. Fold `assistant.delta` / `tool.*` into an in-memory `StreamingRun` (text + live trace,
   05). The DB is **not** written per delta.
4. On `run.completed`: persist the finalized agent turn (`markdown = output`, `runId`) and,
   if any reasoning/tool events occurred, a `trace` turn — immediately, without waiting for
   the next sync. Set thread `state = idle`, update `preview`, set `unread` if the thread
   isn't on screen. Then call `syncNow()` (above) so the next tick's rebuild reconciles
   these locally-finalized (temporary-id) turns against the message-id-derived ones the
   server now has.
5. On failure — the stream drops before `run.completed` — **recover by polling**
   `GET /api/sessions/{id}/messages` with backoff until an assistant message newer than the
   pending user turn appears (a `POST` stream can't be re-attached the way a GET-based SSE
   connection can). Budget exhausted → the user turn's `sendState = failed`, thread
   `state = failed`, surfaced per 04 with a `retry` affordance. A `session_not_found` (404)
   during recovery means the session was deleted remotely — the local thread is deleted too.

Creating a thread (2c) is `createThread(firstMessage)`: `POST /api/sessions` first (empty
body) to get a real server session id, insert the `ThreadEntity` under that id, then run
the normal `sendMessage` flow above against it. There's no more "local insert then hope the
server catches up" — the id is real before the first send happens.

### Retry — `runId`-ack dedup

`retryTurn(turnId)`'s dedup key is **`TurnEntity.runId`, not message content.** `runId` is
only ever set from the `run.started` event, which only fires once the server has
unambiguously accepted *this exact* turn:

- `runId != null` → the server has it; recovery only needs to **poll** for the reply
  (`recoverByPolling`), never resend.
- `runId == null` → it never reached the server; retry **resends** the same message
  (`launchChat` again).

Content equality against `/messages` was the earlier design's dedup key and produces false
positives whenever a retried message's text matches an *earlier* message the user already
sent (e.g. resending "ok" after a prior, already-answered "ok") — the retry would match the
old server message, skip the resend, and the new message would be silently dropped. The
`runId`-ack key doesn't have that failure mode.

## Mutations — rename & delete propagate; pin/read stay local

- `renameThread` → `PATCH /api/sessions/{id}`; `deleteThread` → `DELETE /api/sessions/{id}`.
  Both update Room only on a successful server response — a rename/delete that fails
  network-wise leaves the local row untouched and surfaces the error via the existing
  `ApiError` mapping (04). A 404 on delete is treated as success (already gone).
- `pinned` and `unread` have no server concept — toggling is instant, local-only, no
  network, no rollback path, and survives every sync rebuild via the sidecar
  read-modify-write above.
- **"clear local cache"** (2d, formerly "clear all threads") wipes the local `threads`/
  `turns` tables only. It is not a server delete — the next sync tick repopulates the list
  from the gateway. Still a confirm-guarded destructive action (tap-again-to-confirm).
- `export threads` serializes the local cache to a JSON share-sheet payload (works offline)
  — since the cache mirrors the server, this is a snapshot, not the only copy of the data.

## Settings — DataStore (Preferences)

| Key | Default | Screen |
|-----|---------|--------|
| `server_url` | `https://api.gent.ino.ink` | 2d SERVER |
| `agent_name` | `juno` | 2d AGENT IDENTITY |
| `agent_glyph` | `✳` (one of `✳ ◆ ▲ ● ⌬`) | 2d |
| `biometric_unlock` | `false` | 2d AUTH |
| `expand_traces_by_default` | `false` | 2d DISPLAY → drives 2b trace default |
| `show_tool_args` | `true` | 2d DISPLAY → drives `tool.preview` visibility in trace rows |

Exposed as `Flow<Settings>`; ViewModels combine it into `UiState`. `agent_name`/`glyph`
are display identity only — the model id sent to the gateway is always `hermes-agent`
(02). A per-thread override (the `agentName`/`agentGlyph` sidecar columns) beats the
global default.

## API key — Keystore-backed

Unchanged by the sessions migration. The design copy promises "key stored in secure
enclave · never synced". The key is the shared Hermes bearer token (02):

- AES-256-GCM key in **AndroidKeystore** (`setUserAuthenticationRequired(true)` when the
  biometrics toggle is on, 30s validity window so one prompt covers a burst of requests).
- The bearer token is encrypted with that key; ciphertext + IV in a DataStore file
  **excluded from Auto Backup / device transfer** (`backup_rules.xml`,
  `data_extraction_rules.xml`).
- `ApiKeyStore`: `suspend fun get(): String?`, `set(value)`, `clear()`. Decrypted value is
  held in memory by `ConnectionManager` only, and masked in all logs (08).
- `reveal` in 2d re-triggers `BiometricPrompt` when biometric unlock is on.
- Keystore key invalidated (biometrics re-enrolled) → treat as missing key: clear, surface
  `Unauthorized`, direct the user to 2d.

## Offline behavior

- Whatever the last sync captured stays readable offline; the status row shows
  `▪ hermes · offline` (faint dot) once a health probe fails, and `SyncScheduler`'s loop is
  gated on `ConnState.Online` so it simply doesn't tick while offline.
- Composers stay enabled; a send while offline lands as a `sending` user turn, the chat
  stream attempt fails fast, and recovery-by-polling exhausts its budget quickly →
  `failed` with a `retry` affordance (no background queue; the user decides when to retry,
  consistent with "user actions never auto-retry", 04).
- Because the server is now the system of record, local data loss (uninstall, "clear local
  cache") is **recoverable** — the next successful sync repopulates everything the server
  still has. `export threads` remains useful as an offline-readable snapshot, not as the
  only copy of the data.
