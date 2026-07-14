# 04 — Error handling

## Taxonomy

One sealed hierarchy at the `data/remote` boundary; everything thrown by OkHttp /
serialization is mapped into it. Nothing above the repository layer sees raw exceptions.

```kotlin
sealed interface ApiError {
    data class Network(val cause: IOException) : ApiError        // no route, DNS, reset
    data object Timeout : ApiError
    data class Auth(val code: Int) : ApiError                    // 401 / 403
    data class Server(val code: Int, val message: String?) : ApiError   // 5xx
    data class Client(val code: Int, val message: String?) : ApiError   // other 4xx
    data class Protocol(val detail: String) : ApiError           // bad JSON, wrong shape
    data object NotConfigured : ApiError                         // missing url/key
}
```

Repository methods return `Result<T>`-style (`sealed ApiResult<T> = Ok | Err(ApiError)`)
for one-shot calls; streams emit `ConnState` transitions instead of throwing.

## Retry policy

| Class | Auto-retry? | Policy |
|-------|------------|--------|
| `Network`, `Timeout` | yes | exponential backoff 1s → 30s cap, ±20% jitter |
| `Server` 5xx / 429 | yes | same backoff; honor `Retry-After` when present |
| `Auth` | **no** | stop everything; user must fix key/URL in 2d |
| `Client` 4xx | no | surface; usually a contract bug — log loudly in debug |
| `Protocol` | no | surface + log payload snippet in debug builds only |

Retries are owned by `SyncEngine`/`ConnectionManager` (background refresh, streams) and
are invisible except through the status dot. **User-initiated actions never auto-retry**
(a send or pin either succeeds or shows a retry affordance) — silent replays of
user intent are how duplicate bookings happen. Idempotent replays use `client_id` (03).

## Where errors surface (by design language)

The design has no toasts, snackbars, or dialogs. Errors are rendered in-system:

**1. Status row (every screen)** — the primary channel for connection-level trouble:

| State | Rendering (micro type, 9px) |
|-------|------------------------------|
| Connected | `▪ hermes · connected` — accent dot |
| Connecting/reconnecting | `▪ hermes · connecting…` — warn `#D0A24C` dot |
| Offline (network) | `▪ hermes · offline` — faint `#54545E` dot |
| Auth failed | `▪ hermes · auth failed` — danger `#D06A6A` dot |
| Not configured | `▪ hermes · set up in config` — faint dot |

**2. Inline list/log states** — centered faint 11px text in the content area, matching
the `no threads match "q"` empty-state pattern:

- Thread list, cache empty + fetch failing: `can't reach hermes · pull to retry` +
  a neutral `retry` button (square, hairline).
- Thread view, no cached turns + fetch failing: same pattern.
- Cache non-empty: show cached content; the status row alone signals staleness. Never
  blank a screen that has cache.

**3. Failed send (2b)** — the optimistic user turn stays in the log; its left border
switches to danger `#D06A6A`, meta line reads `▪ failed · retry / discard` (retry accent,
discard faint). Tapping retry re-sends with the same `client_id`.

**4. Mid-stream failure (2b)** — a streaming agent turn that dies keeps accumulated
text, appends a faint marker line `▪ stream interrupted · reconnecting…`, and the
watchdog/backoff reconnects. On catch-up fetch the authoritative turn replaces it.

**5. Config screen (2d)** — `test connection` reports below the button in the caption
slot: `protocol v2 · streaming ok` (ok, faint) / `▪ unreachable · timed out after 10s`
(warn) / `▪ auth failed · check api key` (danger). Saving a malformed URL validates
locally first (`https` scheme + host) with an 11px danger caption under the field.

**6. Auth failure global behavior** — streams closed, sync paused, every status row goes
danger. The thread list shows one inline line under the tick strip:
`auth failed · fix api key in config` linking to 2d. No modal.

## Crash & bug policy

- ViewModel `CoroutineExceptionHandler` logs and downgrades unexpected exceptions to
  `Protocol` errors — a rendering/render-data bug must not take down the process.
- Trace/markdown/block parsers are defensive: a malformed block renders as a plain
  code block of its raw JSON in debug and is dropped in release, never a crash.
- No crash-reporting SDK in v0.1 (personal tool); `debug/` tooling (08) captures logs.

## Watchdogs & edge timings

- SSE liveness: 60s silence → reconnect (02).
- Health probe on `STARTED` has a 5s timeout so the status row settles fast.
- Backoff resets on any successful request, not only stream establishment.
- Clock skew: all "TODAY / YESTERDAY" grouping uses device-local calendar on
  `lastActiveAt` and re-buckets at local midnight; no server-time comparison.
