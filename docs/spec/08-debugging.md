# 08 — Development & debugging features

Everything here lives in `app/src/debug/` (debug source set) or behind
`BuildConfig.DEBUG`; release builds compile none of it. No third-party inspection SDKs
(Chucker/Flipper) — the app's own terminal aesthetic makes a first-party debug surface
cheap and more useful, and the tooling doubles as the fake-data host for tests (07).

## Entry: the DEBUG panel

Debug builds add a danger-colored **developer › debug panel** row at the bottom of the
config screen (so it can't be mistaken for product UI). It opens the DEBUG screen as a
config sub-page — a pushed detail view with a back affordance, no tab bar — in the design
language with sections:

### GATEWAY
- Active target: real server URL (`https://api.gent.ino.ink`) vs **mock server** toggle
  (see below). Quick health readout from `GET /v1/health` (`platform · version`).
- Connection state machine readout: current `ConnState`, last transition times, backoff
  attempt counter, and the active run's `run_id`/status if one is in flight.
- Actions: `force reconnect` · `drop run stream` (kills the `/v1/runs/{id}/events` socket
  mid-run to exercise reconnect + replay) · `expire auth` (poisons the in-memory bearer
  token to exercise the `401 invalid_api_key` flow).

### NETWORK LOG
- Ring buffer (last 200) of REST calls: method, path, status, duration, byte sizes;
  tap → headers + pretty-printed bodies in a `CodeBlock`. Implemented as a
  debug-only OkHttp interceptor writing to an in-memory `DebugLogBus`.
- `copy as curl` per request.

### EVENT CONSOLE
- Live tail of run events (`message.delta` / `reasoning.available` / `tool.started` /
  `tool.completed` / `run.completed`): event name, timestamp, payload preview; pausable;
  filter field (same `PromptField` component). Invaluable for contract drift against a
  real gateway — this is where the non-standard `data:`-only framing (02) is eyeballed.

### CHAOS
Fault injection flags (interceptor + fake-server hooks), each a `LemanToggle`:
- extra latency 0/500/2000ms (segmented control)
- fail next N REST calls (500) · drop stream every 30s
- corrupt next payload (exercises `Protocol` error path)
- clock skew +25h (exercises TODAY/YESTERDAY bucketing)

### STATE
- Room browser: thread/turn counts, per-thread `maxSeq`, `sendState != synced` rows.
- Actions: `wipe cache` · `reseed demo data` · `mark all unread` · `inject failed send`.
- Settings dump + `reset prefs`.

### RENDER
- `expand all traces` override · `show layout hairlines` (draws bounds on catalog
  components) · `slow animations ×5` · markdown playground: a composer that renders
  arbitrary markdown through `MarkdownBody` live — the fastest way to debug renderer
  issues on device.

## Mock server: `FakeHermesServer`

An in-process fake implementing the `HermesClient` interface (swapped via
`AppContainer.Overrides`) — not a socket server, so it's trivially usable from
instrumented tests and has zero-latency determinism, while the chaos flags reintroduce
latency when wanted. It mirrors the real contract (02): `POST /v1/runs` returns a `run_id`,
and `GET /v1/runs/{id}/events` emits the real event vocabulary and **replays** for a
finished run.

- Because threads are local (03), the fake only needs to answer runs; the thread list is
  seeded straight into Room from the shared fixture corpus (07) so the app boots looking
  exactly like the design mocks.
- **Scripted run scenarios**, selectable in the GATEWAY section:
  - `demo` — completes instantly with canned `output` (+ a canned trace) so screens match
    the handoff.
  - `streaming` — emits `message.delta` + interleaved `tool.started`/`tool.completed` and
    `reasoning.available` on a timer, then `run.completed` (replays the "fix flaky ci"
    turn in real time).
  - `needs-you` — completes with an agent turn that ends in a question / renders action
    buttons (the `needs_you` look is now a client-side thread state, 03).
  - `hostile` — slow, flaky, `tool.completed{error:true}` steps, occasional malformed
    frame (exercises the defensive parser), long output, 50-step traces, emoji, RTL text,
    and a stream that drops before `run.completed` (exercises the poll backstop).
- Also the backend for the e2e instrumented test and for screenshot recording of
  streaming states.

## Logging

- Thin `Log`-based logger with feature tags (`leman.net`, `leman.sse`, `leman.sync`,
  `leman.db`, `leman.md`), compiled to no-ops in release. Debug builds tee into
  `DebugLogBus` so NETWORK LOG / EVENT CONSOLE render from the same source as logcat.
- Redaction: the API key never appears in any log path — the interceptor masks the
  `Authorization` header (`hm_…3kf2` style) even in debug.

## Build & platform hygiene

- `StrictMode` (thread + VM policies, penaltyLog) in debug `Application.onCreate` —
  catches accidental main-thread I/O early.
- LeakCanary, debug-only.
- Compose compiler metrics/reports wired to a `-Pleman.composeReports` flag for
  recomposition audits of the streaming thread view (the one perf-sensitive screen).
- Debug-only `network_security_config` permitting cleartext to `10.0.2.2` and RFC1918
  hosts for LAN gateway testing; release config stays TLS-only.
- `applicationIdSuffix = ".debug"` so debug and release installs coexist; debug launcher
  icon tinted warn amber.

## Developer workflows this enables

| Task | Path |
|------|------|
| Build UI with no gateway | mock server `demo` scenario |
| Verify streaming UX | `streaming` scenario + RENDER slow-animations |
| Reproduce flaky network bug | CHAOS latency + drop-stream while on 2b |
| Validate against real gateway | real target + EVENT CONSOLE tail |
| Debug markdown glitch from a real thread | copy payload from NETWORK LOG → markdown playground |
| Demo the app | `demo` scenario — screens match the design handoff exactly |
