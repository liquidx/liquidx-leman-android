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

Auto-retries apply only to connection-level probes (`ConnectionManager`) and a run's event
stream reconnect (02) — invisible except through the status dot. **User-initiated actions
never auto-retry** (a send either succeeds or shows a retry affordance) — silent replays of
user intent are how duplicate work happens. Runs are not idempotent server-side, so the app
guarantees no duplicates by only ever re-running on an explicit user retry (03).

## Where errors surface (by design language)

The design has no toasts, snackbars, or dialogs. Errors are rendered in-system:

**1. Status row (every screen)** — the primary channel for connection-level trouble:

| State | Rendering (micro type, 9px) |
|-------|------------------------------|
| Connected | `▪ hermes · 0.18.0` (health `version`) or `· connected` — accent dot |
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
discard faint). Tapping retry starts a fresh run for the same message (03); discard removes
the pending user turn.

**4. Mid-stream failure (2b)** — a run's event stream that dies keeps accumulated text,
appends a faint marker line `▪ stream interrupted · reconnecting…`, and the backoff
reconnects. Recovery polls `GET /v1/runs/{id}`: if the run finished, its `output` replaces
the accumulated text; if still running, the stream re-opens and **replays from the start**,
so the client resets accumulated text and rebuilds (02). If the run itself failed
(`status:"failed"` or the poll keeps erroring), the turn settles into the failed-send
treatment above.

**5. Config screen (2d)** — `test connection` does `GET /v1/health` and reports below the
button in the caption slot: `hermes-agent · v0.18.0` (ok, faint — from the health
`platform`/`version`) / `▪ unreachable · timed out after 10s` (warn) / `▪ auth failed ·
check api key` (danger). Saving a malformed URL validates locally first (`https` scheme +
host) with an 11px danger caption under the field; the default is `https://api.gent.ino.ink`.

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

- Run-stream liveness: the server sends no keepalive, so there is no fixed silence
  watchdog; a run producing no events for a long time is checked via `GET /v1/runs/{id}`
  (02) rather than assumed dead.
- Health probe on `STARTED` has a 5s timeout so the status row settles fast.
- Backoff resets on any successful request, not only stream establishment.
- Clock skew: all "TODAY / YESTERDAY" grouping uses device-local calendar on
  `lastActiveAt` and re-buckets at local midnight; no server-time comparison.
