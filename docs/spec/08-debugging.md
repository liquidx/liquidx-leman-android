# 08 — Development & debugging features

Everything here lives in `app/src/debug/` (debug source set) or behind
`BuildConfig.DEBUG`; release builds compile none of it. No third-party inspection SDKs
(Chucker/Flipper) — the app's own terminal aesthetic makes a first-party debug surface
cheap and more useful, and the tooling doubles as the fake-data host for tests (07).

## Entry: the DEBUG panel

Debug builds add a third tab `debug` to the tab bar (danger-colored icon so it can't be
mistaken for product UI). It opens a DEBUG screen in the design language with sections:

### GATEWAY
- Active target: real server URL vs **mock server** toggle (see below).
- Connection state machine readout: current `ConnState`, last transition times, backoff
  attempt counter.
- Actions: `force reconnect` · `drop stream` (kills the SSE socket to exercise
  reconnect) · `expire auth` (poisons the in-memory key to exercise 401 flow).

### NETWORK LOG
- Ring buffer (last 200) of REST calls: method, path, status, duration, byte sizes;
  tap → headers + pretty-printed bodies in a `CodeBlock`. Implemented as a
  debug-only OkHttp interceptor writing to an in-memory `DebugLogBus`.
- `copy as curl` per request.

### EVENT CONSOLE
- Live tail of SSE events (both streams): event name, seq, payload preview; pausable;
  filter field (same `PromptField` component). Invaluable for contract drift against a
  real gateway.

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
latency when wanted.

- Seeded from the shared fixture corpus (07) — boots the app looking exactly like the
  design mocks.
- **Scripted scenarios**, selectable in the GATEWAY section:
  - `demo` — static handoff data.
  - `streaming` — a running thread emits `turn.delta`/`trace.step` on a timer
    (replays the "fix flaky ci" conversation in real time).
  - `needs-you` — a thread flips to `needs_you` 10s after launch with action buttons.
  - `hostile` — slow, flaky, occasionally malformed; long titles, 4-page markdown,
    50-step traces, emoji, RTL text.
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
