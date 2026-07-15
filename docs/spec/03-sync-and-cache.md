# 03 — Local store, runs & settings

## Principle: the app is the system of record

The gateway has **no thread store** (02). It runs agent turns and forgets them; the only
server-side artifacts are short-lived `run` objects. Therefore **Room is primary storage,
not a cache** — a thread exists because the app created a row for it, not because the
server has one. This changes the earlier "cache mirrors server" model: there is nothing to
reconcile, no server-wins-by-`seq`, no prune-deleted pass, no global event upserts.

Compose still never renders network responses directly. Runs write into Room via the
repository; the UI observes Room `Flow`s. This gives instant cold start, offline reading,
and one consistency point.

```
                    ┌─────────── POST /v1/runs + /events (per active run)
                    ▼
RunController ──▶ ThreadRepository ──▶ Room ──Flow──▶ ViewModel ──▶ Compose
                    ▲                                   │
                    └──────────── user actions ◀────────┘
```

## What a "thread" is

A thread is a **local conversation**: an ordered list of user + agent turns (agent turns
may carry a trace), plus a `sessionId` for correlating server runs. To advance it, the
repository maps the thread's turns to `input = [{role,content}, …]`, appends the new user
message, and starts a run (02). Nothing about the thread lives on the server between runs.

## Room schema

```kotlin
@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,               // client-generated UUID
    val title: String,                        // derived from first user message (editable later)
    val preview: String,                      // last turn snippet, maintained on write
    val state: String,                        // idle | running | failed  (client-owned)
    val pinned: Boolean,
    val unread: Boolean,                      // set when a run completes off-screen
    val createdAt: Long,
    val lastActiveAt: Long,                   // orders the list; bumped on every turn
    val sessionId: String?,                   // last run's session_id, for correlation
    val agentName: String?, val agentGlyph: String?,   // per-thread identity override
)

@Entity(tableName = "turns",
        primaryKeys = ["id"],
        indices = [Index("threadId", "seq")])
data class TurnEntity(
    val id: String, val threadId: String,
    val seq: Long,                            // client-assigned, monotonic within thread — ordering only
    val kind: String,                         // user | agent | trace
    val createdAt: Long,
    val markdown: String?,                    // user text / agent output
    val blocksJson: String?,                  // structured agent blocks if any (05)
    val traceJson: String?,                   // finalized trace (reasoning + tool steps), one column
    val runId: String?,                       // the server run that produced an agent/trace turn
    val sendState: String,                    // synced | sending | failed   (user turns)
    val viaButton: Boolean,
)
```

Notes:

- `seq` is **client-assigned** (there is no server sequence to honor) — a per-thread
  counter, `maxSeq + 1` on append. It orders turns and nothing else.
- Trace steps and rich blocks stay as **one JSON column each**, not side tables — read-only,
  always loaded with their turn, never queried individually.
- A `trace` turn's `traceJson` is composed from the run's `reasoning.available` text plus
  the `tool.started`/`tool.completed` pairs (02, 05). If a live run's trace wasn't fully
  captured (e.g. app killed mid-run), `runId` lets the app re-fetch it later by replaying
  `/v1/runs/{runId}/events` (02).
- DB version 1; `fallbackToDestructiveMigration` acceptable **only until first release**,
  then real migrations + schema-export CI check (07).

### DAO essentials

```kotlin
@Query("SELECT * FROM threads ORDER BY pinned DESC, lastActiveAt DESC")
fun observeThreads(): Flow<List<ThreadEntity>>

@Query("SELECT * FROM turns WHERE threadId = :id ORDER BY seq")
fun observeTurns(id: String): Flow<List<TurnEntity>>

@Query("SELECT MAX(seq) FROM turns WHERE threadId = :id") suspend fun maxSeq(id: String): Long?
@Upsert suspend fun upsertThread(t: ThreadEntity)
@Upsert suspend fun upsertTurns(items: List<TurnEntity>)
@Query("DELETE FROM threads WHERE id = :id") suspend fun deleteThread(id: String)
```

Filtering (2a) and day-grouping (`PINNED / TODAY / YESTERDAY / EARLIER`) happen in the
ViewModel over the observed list — the dataset is small and local, and substring-match
semantics stay in Kotlin where they're unit-testable.

## Running a turn (replaces "sync")

`ThreadRepository.sendMessage(threadId, text, viaButton = false)`:

1. Append a user `TurnEntity` immediately: `seq = maxSeq + 1`, `sendState = sending`. UI
   shows it at once. Bump the thread to `state = running`, `lastActiveAt = now`.
2. Build `input` from all prior turns of the thread (user/agent → `{role,content}`; trace
   turns are **not** sent) + this user message.
3. `POST /v1/runs` (02). On `202`, store the `run_id`/`session_id` on the thread; flip the
   user turn to `sendState = synced`.
4. Open `/v1/runs/{id}/events`; fold into an in-memory streaming agent turn + live trace
   (05). The DB is **not** written per delta.
5. On `run.completed`: persist the finalized agent turn (`markdown = output`, `runId`) and,
   if any reasoning/tool events occurred, a `trace` turn (`traceJson`). Set thread
   `state = idle`, update `preview`, set `unread` if the thread isn't on screen.
6. On failure (POST error, stream dies with no completion and the poll backstop shows
   `failed`): user turn → `sendState = failed`; thread → `state = failed`; surface per 04.
   **User actions never auto-retry** — the failed turn shows a `retry` affordance (04).
   Retry re-sends the same message (a new run; runs aren't idempotent server-side, but the
   app controls whether a retry happens, so there are no silent duplicates).

Creating a thread (2c) is the first `sendMessage` against a freshly inserted
`ThreadEntity`; the title is derived from that first message. It is a local insert followed
by a normal run — no server "create thread" call exists.

## Pins, read, delete — all local

- `pinned`, `unread`, `title` edits, and thread deletion are **purely local** columns/ops;
  there is no server state to PATCH. Toggling is instant, no network, no rollback path.
- "clear all threads" (2d) wipes local tables only — there is nothing server-side to
  delete. It is still a confirm-guarded destructive action.
- `export threads` serializes the local store to a JSON share-sheet payload (works
  offline) — the app is the only place the data exists, so export matters more here.

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
(02). A per-thread override (stored on `ThreadEntity`) beats the global default.

## API key — Keystore-backed

The design copy promises "key stored in secure enclave · never synced". The key is the
shared Hermes bearer token (02):

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

- Everything is local, so the entire app is readable offline; the status row shows
  `▪ hermes · offline` (faint dot) once a health probe fails.
- Composers stay enabled; a send while offline lands as a `sending` user turn and the run
  is attempted — it fails fast to `failed` with a `retry` affordance (no background queue;
  the user decides when to retry, consistent with "user actions never auto-retry", 04).
- Because there is no server copy, local data loss is unrecoverable — hence export, the
  backup-exclusion caveat notwithstanding (the *key* is excluded from backup; thread data
  is ordinary app storage the user can export).
