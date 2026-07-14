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
| [02-server-api.md](02-server-api.md) | Hermes gateway contract, connection lifecycle, streaming (SSE), live updates |
| [03-sync-and-cache.md](03-sync-and-cache.md) | Room cache as source of truth, sync strategy, offline behavior, settings & key storage |
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

## Existing project facts

- `net.liquidx.leman`, minSdk 34, targetSdk/compileSdk 36, Kotlin 2.2, Compose BOM 2025.12, AGP 9.
- Gateway is OpenAI-compatible per the repo README; the thread/trace surface is a
  Hermes extension — see [02-server-api.md](02-server-api.md) for the assumed contract
  and how it is isolated so contract drift stays in one layer.
