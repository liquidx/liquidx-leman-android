# 07 — Test suites

## Layout

```
app/src/test/            JVM unit tests (fast, no device) — bulk of coverage
app/src/test/…/screenshot  Roborazzi screenshot tests (JVM, Robolectric)
app/src/androidTest/     instrumented: Room, Compose interaction, e2e against fake gateway
app/src/test/resources/fixtures/   JSON + markdown corpora shared by suites
```

Deps: JUnit4, `kotlinx-coroutines-test`, Turbine (Flow assertions), MockWebServer,
`room-testing`, Robolectric + Roborazzi, `compose-ui-test-junit4`.

## Unit suites (JVM)

**Wire / client (`data/remote`)** — against MockWebServer:
- DTO decoding from fixture JSON: `HealthDto`, `RunAcceptedDto`, `RunDto` (running +
  completed), unknown fields ignored, unknown `status`/event strings → `Unknown` (not crash).
- Run event stream reader: parsing the gateway's **`data:`-only framing** (no `id:`/`event:`
  lines; type is inside the JSON), each `RunEvent` variant, the trailing `: stream closed`
  comment, and a malformed frame surviving as `Unknown` rather than crashing.
- Reconnect semantics (virtual time): on stream re-open the accumulated text is **reset**
  before replay (no double-append); backoff sequence 1-2-4…30 with jitter bounds, reset on
  success; poll backstop (`GET /v1/runs/{id}`) recovers a run that finished while the
  socket was gone.
- Error mapping table: socket failures → `Network`, `401 invalid_api_key` → `Auth`,
  `404 run_not_found` → `Client`, 5xx → `Server`, garbage body → `Protocol`, each asserted
  through the public client API.

**Repository / runs (`data/repo`)** — fake client + in-memory Room:
- `input` assembly: a thread's turns map to `[{role,content}]` in order; `trace` turns are
  excluded; the new user message is appended last.
- Run lifecycle: append `sending` user turn → `202` flips it to `synced` and stores
  `run_id`/`session_id` → fold events → on `run.completed` persist the agent turn +
  composed trace turn, bump `preview`/`lastActiveAt`, set `unread` when off-screen.
- Failure: POST error or a `failed`/never-completing run → user turn `failed`, thread
  `failed`; retry re-sends (a new run); no auto-retry of user actions.
- Event application: deltas never touch the DB; only the finalized turn is persisted.

**ViewModels** — Turbine over `state`:
- Thread list: filter semantics (case-insensitive substring on title+preview), grouping
  PINNED/TODAY/YESTERDAY/EARLIER with local-midnight boundaries (fixed clock injected),
  empty-section omission, running-count readout.
- Thread view: trace expansion default from prefs + per-trace override, streaming turn
  accumulation, `(via button)` action flow, auto-scroll flag only when at bottom.
- Config: URL validation, test-connection state transitions, glyph single-select.

**Markdown/blocks (`ui/markdown` parsing halves)**:
- CommonMark → render-model snapshots over the fixture corpus (lists, tables, inline
  code, links, partial/unterminated input).
- Diff line classification; trace composer that folds `reasoning.available` +
  `tool.started`/`tool.completed` pairs into steps and the rollup
  (`▸ trace · 6 steps · web_search ×4 …`), including histogram ordering, `error` steps,
  `show_tool_args` gating, and duration formatting.

## Screenshot suite (Roborazzi, records to `app/src/test/screenshots/`)

Pixel fidelity is a stated requirement, so screenshots are the regression net for it:

- Every component-catalog `@Preview` (06) auto-tested via Roborazzi preview scanning.
- Full screens at 380×788 with the handoff sample data: 2a (default, filtered, empty,
  offline), 2b (traces collapsed, expanded, streaming turn, failed send), 2c, 2d.
- Markdown torture fixtures rendered through `MarkdownBody`.
- Animations disabled (`ANIMATOR_DURATION_SCALE = 0`); pulse/caret frames frozen at t=0.
- CI compares against committed goldens; `./gradlew recordRoborazziDebug` re-records.

## Instrumented suite

- **Room**: DAO queries on-device, migration tests once schema v2 exists
  (schema JSONs exported to `app/schemas/`, asserted in CI).
- **Compose interaction** (semantics-driven): tap thread row → navigates with id; pin
  glyph toggles without opening row (hit-area regression); trace line expands/collapses;
  action button appends `(via button)` turn; composer send disabled when unconfigured.
- **E2E against the fake gateway** (08): app launched with `AppContainer` overridden to
  the in-process `FakeHermesServer` — script: cold start → list renders from the local seed
  → open thread → send message → fake accepts a run and emits `message.delta` + tool/trace
  events → text and trace appear incrementally → `run.completed` → agent turn + trace
  persist. This one test exercises client, repository, store, and UI together and is the
  merge gate for streaming changes.

## Fixtures

`fixtures/` is the single source of sample data, mirroring the handoff's sample content
(7 threads incl. "fix flaky ci pipeline" with its 9-step trace, code diff, task list;
"book train to geneva" with option table + action buttons). Used by unit tests,
screenshot tests, the fake gateway seed, and debug demo mode — one corpus, four consumers,
so design-sample drift is impossible.

## Conventions & CI

- Naming: `methodOrBehavior_condition_expectation`. One behavior per test.
- No sleeps; virtual time (`runTest`) and Turbine everywhere. SSE tests use
  MockWebServer's streamed responses, not real sockets with delays.
- CI (later, GitHub Actions): `testDebugUnitTest` + `verifyRoborazziDebug` on every PR;
  instrumented suite on an emulator job nightly/on-demand. A PR that changes
  `data/remote` DTOs must update fixtures in the same change.
