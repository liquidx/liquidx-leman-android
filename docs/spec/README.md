# LeMan — Technical Design Spec

LeMan is a mobile-only Android client for the **Hermes Agent gateway**. Users start
threads with an agent, read the conversation (user turns, agent turns, collapsible
thinking traces), watch it work live, and configure the server connection.

The **visual design is normative** and lives in [`docs/design/`](../design/README.md)
(screens `2a`–`2d` + the Wanderloop design system). This spec covers everything the
design handoff does not: the systems behind the screens.

## Documents

| Doc | Covers |
|-----|--------|
| [01-architecture.md](01-architecture.md) | Module/package layout, layers, DI, navigation, threading model |
| [02-server-api.md](02-server-api.md) | Hermes gateway contract (verified), async run lifecycle, run event stream, reconnect |
| [03-sync-and-cache.md](03-sync-and-cache.md) | Room as system of record (no server thread store), running a turn, offline behavior, settings & key storage |
| [04-error-handling.md](04-error-handling.md) | Error taxonomy, retry policy, connection status surface, per-action failure UX |
| [05-markdown-rendering.md](05-markdown-rendering.md) | CommonMark → Compose pipeline, code/diff blocks, rich agent blocks, streaming render |
| [06-compose-components.md](06-compose-components.md) | Theme & tokens in Compose, component catalog, motion, icons & glyphs |
| [07-testing.md](07-testing.md) | Unit / integration / UI / screenshot test suites, fixtures, fake gateway |
| [08-debugging.md](08-debugging.md) | Debug-build tooling: network console, mock server, chaos flags, trace inspector |

## Ground rules (from the design handoff)

- Mobile-only, single fluid column designed at 380px width. Pixel-faithful to the mocks.
- Font is Spline Sans Mono everywhere; no rounded corners; no shadows except accent glows.
- Accent is LeMan blue `#6BA0D8`; the app renders **its own design system, not Material defaults** —
  Material3 is used only as a substrate (theming, ripples, a11y semantics).
- The agent has a configurable identity (name + glyph, default "juno"); the API may
  provide a per-thread agent profile that overrides it.
- Thinking traces render muted and **collapsed by default** (user preference can flip this).

## Gateway (verified 2026-07-15)

- Base URL **`https://api.gent.ino.ink`** (the `api.` subdomain; the bare host is a
  separate cookie-SSO web UI — not our target). Auth is a shared static bearer token
  (`Authorization: Bearer <key>`, from `.env` `HERMES_API_KEY` in dev). Gateway version
  0.18.0, model `hermes-agent`.
- The gateway is an **OpenAI-compatible async agent-run runner**, not a thread store:
  `POST /v1/runs` starts a run, `GET /v1/runs/{id}/events` streams it. There is no thread
  list, no global feed, no server-side history — **the app is the system of record** for
  threads, and conversation history is sent by the client on each run. See
  [02-server-api.md](02-server-api.md) for the full verified contract and
  [03-sync-and-cache.md](03-sync-and-cache.md) for the storage model.

## Existing project facts

- `net.liquidx.leman`, minSdk 34, targetSdk/compileSdk 36, Kotlin 2.2, Compose BOM 2025.12, AGP 9.
