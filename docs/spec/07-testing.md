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
- DTO decoding from fixture JSON: full thread, every turn kind, every block type,
  unknown fields ignored, unknown enums → `Unknown` (not crash).
- SSE `Flow` adapter: event parsing, heartbeat handling, `Last-Event-ID` sent on
  reconnect, watchdog fires after 60s silence (virtual time), backoff sequence
  1-2-4…30 with jitter bounds, backoff reset on success.
- Error mapping table: socket failures → `Network`, 401 → `Auth`, 500 → `Server`,
  garbage body → `Protocol`, each asserted through the public client API.

**Sync (`data/repo`)** — fake client + in-memory Room:
- Refresh reconciliation: upsert, prune deleted, `seq` conflict rule (stale upsert
  dropped; client-owned `sendState` preserved).
- Optimistic send lifecycle: provisional row → authoritative replacement by `client_id`;
  failure → `failed`; retry reuses `client_id`; offline queue flushes in order.
- Stream event application: `turn.completed` upserts; deltas never touch the DB.

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
- Diff line classification; trace rollup composer (`▸ trace · 9 steps · ci.logs ×3 …`)
  including histogram ordering and duration formatting.

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
  the in-process `FakeHermesServer` — script: cold start → list renders from seed →
  open thread → fake emits stream deltas → text appears incrementally → send message →
  optimistic row reconciles. This one test exercises client, sync, cache, and UI
  together and is the merge gate for streaming changes.

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
