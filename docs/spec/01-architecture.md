# 01 — Architecture

## Shape

Single-module app (`:app`) with package-by-layer inside `net.liquidx.leman`. The app is
small (4 screens); a multi-module build would cost more than it buys. Layers are enforced
by convention and by tests, not by Gradle modules.

```
net.liquidx.leman
├── LemanApp.kt                  Application; owns AppContainer
├── MainActivity.kt              single activity, edge-to-edge, Compose only
├── di/
│   └── AppContainer.kt          manual DI graph (interfaces + lazy singletons)
├── domain/                      pure Kotlin, no Android imports
│   ├── model/                   Thread, Turn, TraceStep, AgentProfile, ThreadState…
│   └── …                        small use-case helpers where logic outgrows repos
├── data/
│   ├── remote/                  Hermes client: DTOs, run event stream, mapping → domain (02)
│   ├── local/                   Room db: entities, DAOs, mapping → domain (03)
│   ├── settings/                DataStore prefs + Keystore-encrypted API key (03)
│   └── repo/                    ThreadRepository, ConnectionManager, RunController
├── ui/
│   ├── theme/                   LemanTheme: colors, type, motion tokens (06)
│   ├── components/              design-system composables (06)
│   ├── markdown/                markdown → Compose renderer (05)
│   ├── threads/                 2a thread list: ThreadsScreen + ThreadsViewModel
│   ├── thread/                  2b thread view: ThreadScreen + ThreadViewModel
│   ├── newthread/               2c: NewThreadScreen + NewThreadViewModel
│   ├── config/                  2d: ConfigScreen + ConfigViewModel
│   └── nav/                     NavHost, routes, tab scaffold
└── debug/                       debug-only tooling, stripped from release (08)
```

## Layering rules

- `domain` imports nothing from `data` or `ui`. Models are immutable `data class`es.
- `ui` talks only to `data/repo` (repositories + `ConnectionManager`), never to
  `remote`/`local` directly.
- `data/remote` is the **only** place that knows the Hermes wire format (the run-runner
  API and its `data:`-only event framing, 02). DTOs never leak past the repository boundary.
- All I/O is `suspend` or `Flow`; nothing blocks the main thread. Repositories are
  main-safe (they internally dispatch to `Dispatchers.IO`).

## Unidirectional data flow

Each screen: `ViewModel` exposes a single `StateFlow<UiState>` + accepts events.

```kotlin
class ThreadViewModel(...) : ViewModel() {
    val state: StateFlow<ThreadUiState>          // collected with collectAsStateWithLifecycle
    fun onEvent(event: ThreadEvent)              // SendMessage, ToggleTrace, TogglePin, Retry…
}
```

`UiState` is a plain data class — always renderable (loading/empty/error are states,
not exceptions). ViewModels combine flows from Room (the system of record, 03) with
connection status and any in-flight run's streaming state; they never hold data the store
doesn't, except the transient streaming turn/trace of an active run (persisted on
completion).

```
   Room (Flow) ─────┐
   ConnState  ──────┼── combine ──▶ UiState ──▶ Compose
   prefs      ──────┤                    ▲
   streaming run ───┘                    │ events
   RunController ◀── repository ◀────────┘
```

## Dependency injection

Manual DI via `AppContainer` — no Hilt/Koin. The graph is ~10 objects; a container class
keeps the build simple (no KSP for DI, no annotation processing on AGP 9) and makes test
substitution explicit.

```kotlin
class AppContainer(context: Context, overrides: Overrides = Overrides()) {
    val settings: SettingsStore by lazy { … }
    val apiKeyStore: ApiKeyStore by lazy { … }
    val db: LemanDatabase by lazy { … }
    val hermesClient: HermesClient by lazy { … }        // interface; fake in debug/tests
    val connectionManager: ConnectionManager by lazy { … }
    val threadRepository: ThreadRepository by lazy { … }  // owns per-run RunControllers
}
```

ViewModels are created with a shared `viewModelFactory` that pulls from the container.
`Overrides` lets the debug build swap `hermesClient` for the in-process fake (08) and
tests swap anything.

## Navigation

`androidx.navigation:navigation-compose`, one `NavHost`, four routes:

| Route | Screen | Notes |
|-------|--------|-------|
| `threads` | 2a Thread list | start destination; tab bar visible |
| `thread/{threadId}` | 2b Thread view | no tab bar; back chevron `‹` |
| `newThread` | 2c New thread | modal-style; `esc · cancel` = back |
| `config` | 2d Config | tab bar visible |

The tab bar (threads / config) is part of a shared scaffold shown only on `threads` and
`config`. Deep link `leman://thread/{id}` supported from day one — notifications and the
debug panel use it. Transitions are cuts or fades ≤150ms (motion is "sharp and
mechanical"; no slide-in-from-right).

## Process & lifecycle model

- **Foreground-first.** LeMan is a viewer/remote-control; the *agent* runs server-side.
  No WorkManager jobs, no foreground services in v0.1.
- The thread list is local (03), so cold start renders instantly from Room with no network.
  There is no global stream to open — the only live socket is a run's event stream (02),
  opened when the user sends a message and held while that run is in flight on the visible
  thread. It is torn down on `STOP`; an unfinished run is recovered on return via
  `GET /v1/runs/{id}` and a stream re-open (which replays), see 02.
- A run started while the app is foregrounded but then backgrounded keeps running
  server-side; on return the app polls the run and replays its events to catch up. Truly
  detached background completion + push notification is a later milestone (needs gateway
  push support); the design accounts for it only via the `leman://thread/{id}` deep link.

## New dependencies this spec introduces

All stable, added to `libs.versions.toml`:

| Dep | For |
|-----|-----|
| `com.squareup.okhttp3:okhttp` | HTTP + run event streaming (02). The event stream uses non-standard `data:`-only framing, so it's read with a hand-rolled line reader over the response body rather than `okhttp-sse` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | wire JSON (02) |
| `androidx.room:room-runtime/-ktx` + KSP compiler | local cache (03) |
| `androidx.datastore:datastore-preferences` | settings (03) |
| `androidx.biometric:biometric` | biometric unlock for API key (03) |
| `androidx.navigation:navigation-compose` | navigation |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | ViewModels |
| `org.commonmark:commonmark` (+ tables ext) | markdown parsing (05) |
| test/debug only: `mockwebserver`, `turbine`, `room-testing`, `kotlinx-coroutines-test`, Roborazzi + Robolectric | (07, 08) |

Deliberately **not** used: Retrofit (a handful of endpoints + a custom event stream —
OkHttp directly is less machinery), `okhttp-sse` (the gateway's `data:`-only framing isn't
standard SSE; a small custom reader is clearer and testable), Hilt (see above), Coil (no
images anywhere in the design), Markwon (View-based; we render markdown natively in
Compose, see 05).
