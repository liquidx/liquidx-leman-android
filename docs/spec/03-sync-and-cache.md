# 03 — Sync & local cache

## Principle: cache is the source of truth for the UI

Compose never renders network responses directly. The network writes into Room; the UI
observes Room `Flow`s. This gives instant cold-start rendering, offline reading, one
consistency point, and makes streaming/optimistic updates ordinary cache writes.

```
HermesClient ──▶ SyncEngine ──▶ Room ──Flow──▶ ViewModel ──▶ Compose
                    ▲                             │
                    └──────── user actions ◀──────┘
```

## Room schema

```kotlin
@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,
    val title: String, val preview: String,
    val state: String, val progressStep: Int?, val progressTotal: Int?,
    val unread: Boolean, val pinned: Boolean,
    val lastActiveAt: Long, val seq: Long,
    val agentName: String?, val agentGlyph: String?, val agentProfileLabel: String?,
)

@Entity(tableName = "turns",
        primaryKeys = ["id"],
        indices = [Index("threadId", "seq")])
data class TurnEntity(
    val id: String, val threadId: String, val seq: Long,
    val kind: String,                       // user | agent | trace
    val createdAt: Long, val viaButton: Boolean,
    val markdown: String?,
    val blocksJson: String?,                // structured blocks, serialized as-is
    val traceJson: String?,                 // TraceDto serialized; parsed lazily on expand
    val sendState: String,                  // synced | sending | failed  (user turns only)
    val clientId: String?,                  // reconciliation key for optimistic sends
)
```

Notes:

- Trace steps stay as one JSON column, not a `trace_steps` table. They are read-only,
  always loaded with their turn, and never queried individually — a table adds joins for
  nothing. Same for rich blocks.
- `seq` ordering (not timestamps) orders turns and drives incremental fetch
  (`turns?after_seq=`).
- DB version 1; `fallbackToDestructiveMigration` is acceptable **only until first
  release**, then real migrations + a schema-export CI check (07).

### DAO essentials

```kotlin
@Query("SELECT * FROM threads ORDER BY pinned DESC, lastActiveAt DESC")
fun observeThreads(): Flow<List<ThreadEntity>>

@Query("SELECT * FROM turns WHERE threadId = :id ORDER BY seq")
fun observeTurns(id: String): Flow<List<TurnEntity>>

@Upsert suspend fun upsertThreads(items: List<ThreadEntity>)
@Upsert suspend fun upsertTurns(items: List<TurnEntity>)
@Query("SELECT MAX(seq) FROM turns WHERE threadId = :id") suspend fun maxSeq(id: String): Long?
```

Filtering (2a) and day-grouping (`PINNED / TODAY / YESTERDAY / EARLIER`) happen in the
ViewModel over the observed list — dataset is small, and substring-match semantics stay
in Kotlin where they're unit-testable.

## SyncEngine

Lifecycle-scoped orchestrator (runs while app is `STARTED`):

1. On start: `refreshThreads()` — fetch first page, upsert, prune threads deleted
   server-side (full-list reconcile; dataset is small).
2. Open global event stream → upsert per event (02).
3. `watchThread(id)` while thread 2b is visible: catch-up fetch `after_seq = maxSeq(id)`,
   then per-thread stream; `turn.completed` events upsert turns.
4. Marks thread read (`unread = false`) when its screen is opened; PATCHes server if the
   contract supports it, else local-only.

All writes funnel through `SyncEngine`/repository so conflict rules live in one place:
**server wins by `seq`** — an upsert with `seq <= existing.seq` is dropped, except
`sendState`/`clientId` which are client-owned.

## Optimistic sends

`sendMessage(threadId, text)`:

1. Insert user `TurnEntity` immediately: `seq = maxSeq + 1` (provisional),
   `sendState = sending`, fresh `clientId`. UI shows it instantly.
2. `POST /messages`. On success the authoritative turn arrives (response or stream) with
   the same `clientId` → replace provisional row (delete + upsert authoritative).
3. On failure → `sendState = failed`; row renders with a danger marker + `retry` action
   (04). Retry re-POSTs with the **same** `clientId` (server-side idempotency key).

New-thread creation (2c) is not optimistic — it's a navigation event. Show the composer's
send-in-progress state; navigate to `thread/{id}` when `POST /v1/threads` returns.

## Pins & read state

Assume server-side flags via `PATCH` (02). If the gateway lacks them, they become
local-only columns and survive refreshes because refresh upserts never overwrite
client-owned fields. Either way pin toggling is optimistic with rollback on failure.

## Settings — DataStore (Preferences)

| Key | Default | Screen |
|-----|---------|--------|
| `server_url` | `""` | 2d SERVER |
| `agent_name` | `juno` | 2d AGENT IDENTITY |
| `agent_glyph` | `✳` (one of `✳ ◆ ▲ ● ⌬`) | 2d |
| `biometric_unlock` | `false` | 2d AUTH |
| `expand_traces_by_default` | `false` | 2d DISPLAY → drives 2b trace default |
| `show_tool_args` | `true` | 2d DISPLAY → drives trace row rendering |

Exposed as `Flow<Settings>`; ViewModels combine it into `UiState`. Per-thread agent
profile from the API overrides the configured default identity when present.

## API key — Keystore-backed

The design copy promises "key stored in secure enclave · never synced":

- AES-256-GCM key in **AndroidKeystore** (`setUserAuthenticationRequired(true)` when the
  biometrics toggle is on, with a 30s validity window so one prompt covers a burst of
  requests).
- The API key string is encrypted with that key; ciphertext + IV stored in a separate
  DataStore file **excluded from Android Auto Backup and device transfer** (update
  `backup_rules.xml` / `data_extraction_rules.xml`).
- `ApiKeyStore` API: `suspend fun get(): String?`, `suspend fun set(value: String)`,
  `suspend fun clear()`. Decrypted value is held in memory by `ConnectionManager` only.
- `reveal` in 2d re-triggers `BiometricPrompt` when biometric unlock is on.
- Keystore key invalidated (e.g. biometrics re-enrolled) → treat as missing key: clear,
  surface `Failed(Auth)` state, direct user to 2d.

## Offline behavior

- Everything cached is readable offline; status row shows `▪ hermes · offline` (faint dot).
- Composers stay enabled; sends queue as `sending` rows and are retried on reconnect
  (single in-order flush per thread by provisional `seq`).
- "clear all threads" (2d) requires connectivity — it is destructive and server-first:
  `DELETE` server, then wipe local tables on success. `export threads` serializes from
  the **local cache** to a JSON share-sheet payload, so it works offline.
