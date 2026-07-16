# Handoff: LeMan — Mobile Interface to the Hermes Agent

## Overview

LeMan is a **mobile-only** app in the Wanderloop product family. It is a client for the **Hermes Agent**: users start threads (tasks/conversations) with an agent, watch it work (tool calls + reasoning traces), approve actions, and configure the server connection. The app is called **leman**; the agent has its own configurable identity (name + glyph avatar, e.g. "juno"), which may also be provided per-thread by the API as an "agent profile".

**Implement the screens from Turn 2** (ids `2a`–`2d` in the design file). Turn 1 (`1a`–`1f`) is earlier exploration kept for reference; where turn 1 and turn 2 disagree, turn 2 wins.

## About the Design Files

The files in this bundle are **design references created in HTML** — prototypes showing intended look and behavior, not production code to copy directly. The task is to **recreate these designs in the target codebase's environment** (React Native, SwiftUI, Flutter, web, etc.) using its established patterns — or, if no codebase exists yet, choose the most appropriate stack for a mobile app and implement there.

- `LeMan Screens.dc.html` — the screens. Open in a browser (needs `support.js` alongside). Each option is badged (2a, 2b, 2c, 2d) with an annotation caption below it.
- `Wanderloop Design System.dc.html` — the family design system (tokens, type, components, motion). Normative.
- `support.js` — runtime for the .dc.html files; irrelevant to implementation.

## Fidelity

**High-fidelity.** Colors, typography, spacing, and copy in the mocks are intentional. Recreate pixel-perfectly at 380px design width (scale to device width; it's a fluid single column). All data shown is sample data.

## Design Tokens (Wanderloop core + LeMan accent)

Font: **Spline Sans Mono** (Google Fonts), weights 400/500/600. Everything is monospace. No rounded corners anywhere. No shadows except accent glows. Icons (Tabler, 1.5 stroke, square linecap/miter join) appear **only** in the tab bar.

Colors:
- base / app background: `#08080A`
- surface (inputs, panels, trace boxes): `#0C0C10`
- **accent (LeMan blue): `#6BA0D8`** — glow: `0 0 8px rgba(107,160,216,.6)` · tint fill: `rgba(107,160,216,.1)`
- accent-muted (tool-call markers): `#5B7EA6`
- text/primary `#E6E6EA` · text/secondary `#9A9AA4` · text/tertiary `#7A7A84` · text/faint `#54545E` · gutter/faintest `#3F3F48`
- warn (needs-you / pending): `#D0A24C` · danger: `#D06A6A` · diff-green: `#6BB08A`
- hairlines: `rgba(255,255,255,.05)` faint · `.09` default · `.14` strong (inputs)

Type scale:
- display 23px/600/+.05em UPPERCASE — screen titles (THREADS, CONFIG, NEW THREAD)
- value 14px/400 — thread titles
- body 12–13px/400, line-height 1.65 — messages
- label 10px/+.16em UPPERCASE `#6A6A74` — section headers
- meta 10px — previews, state tokens, traces
- micro 9px/+.1em — status bar, tab bar, timestamps, speaker tags

Casing rule: UPPERCASE + tracking for labels/titles/states; lowercase for all values and content.

Motion: `riseIn` (fade up 7px, .5s ease, stagger .06s) for rows; `barDraw` (scaleX from left, .8s cubic-bezier(.16,1,.3,1)) for progress; `pulse` (2s ease-out loop) on the running dot; `caret` blink (1s steps) in inputs. Sharp and mechanical, no bounce.

## Shared Chrome (all screens)

- **Status row**: 9px, `09:41` left, `▪ hermes · connected` right (▪ accent when connected).
- **Title row**: display type left, 10px meta readout right.
- **Tick strip**: 9px tall, 1px ticks every 24px on a hairline baseline, under the title. Mobile signature element.
- **Tab bar**: 2 tabs — `threads`, `config` — 16px Tabler icon + 9px label. Active: accent color + 2px accent top rule. Inactive: `#5C5C66`.
- Horizontal padding 18px. Touch targets ≥44px.

## Screens

### 2a — THREADS (thread list)

Purpose: see all threads, their status, filter, pin, start new.

Layout, top→bottom: status row → title `THREADS` + readout (`7 · 1 running`, running count in accent) → tick strip → **scrollable list** → new-thread field (pinned) → tab bar.

**Filter row** — the FIRST row inside the scroll area (scrolls off-screen, not pinned): accent `>` prompt, text input placeholder `filter threads`, `⏎ find` hint right. Filters the list live against title + preview (case-insensitive substring). Empty state: centered `no threads match "query"` in faint.

**Section headers** (in-list): `LABEL · count ———— right-text` — 10px tracked label, faint `·`, count in `#9A9AA4`, hairline fills the gap, right text (date like `jul 14`) faint. Order: `PINNED` (if any pins) → `TODAY` → `YESTERDAY` → `EARLIER`. Sections with 0 items are omitted.

**Thread row** (padding 13px 0, hairline bottom `.05`):
- Left: 9px status dot — filled accent + glow + pulsing halo = running; filled `#D0A24C` = needs you; hollow (1px `#54545E` border) = done/idle/scheduled.
- Middle: title 14px (unread → `#E6E6EA`, read → `#9A9AA4`), truncated; unread adds a 5×5 accent square after the title. Preview line 11px `#7A7A84`, truncated. Meta line 10px: `▪ state · time` (state token colored to match the dot; time faint).
- Right: pin glyph — `◆` accent if pinned, `◇` near-invisible `#2A2A32` if not. Tapping toggles pin (in the prototype the whole row toggles; in production the row opens the thread and pin is a long-press/swipe or the glyph's own hit area — see Interactions).

**New-thread field** (pinned above tab bar, 12px 18px padding, hairline top): surface bg, **1px accent border** (this is the one accent-bordered input — it's the primary action), accent `>`, `new thread` placeholder, blinking accent caret, `⏎ start` hint. Tapping opens screen 2c.

Sample data (7 threads): pinned: "book train to geneva" (needs you, unread) and "renew car insurance" (running · 3/5, now); today: "morning digest" (done, unread, 07:00); yesterday: "fix flaky ci pipeline" (done), "plan lyon trip" (idle), "summarize q2 board notes" (done); earlier: "monitor hn for llm articles" (scheduled/recurring, jul 11).

### 2b — Thread view (session log)

Purpose: read a full agent conversation: user turns, agent turns with rich markdown, collapsible thinking traces.

**Header** (13px 18px, hairline bottom): back chevron `‹` · **agent avatar** 22×22 square, 1px `rgba(107,160,216,.5)` border, `rgba(107,160,216,.08)` fill, accent glyph (`✳`) · title 14px/600 truncated + status line 9px tracked: `▪ DONE · juno · ops profile` (agent name in `#8A8A94`) · pin `◆` accent right.

**Turn layout** — a log with a timestamp gutter:
- Gutter: 34px wide, right-aligned 9px `#3F3F48` timestamps (`21:40`).
- **User turn**: content block with 2px **accent left border**, 12px left padding; 9px tracked `YOU` tag in `#54545E`; body 12px/1.65 `#E6E6EA`.
- **Agent turn**: same but 2px `rgba(255,255,255,.12)` left border; 9px tracked agent-name tag (`JUNO`) in **accent**; body 12px/1.7 `#C9C9D0` with emphasized spans in `#E6E6EA`.
- 16px between turns.

**Thinking trace** (its own log entry, before the agent turn it produced):
- **Collapsed (default)**: one muted line — `▸ trace · 9 steps · ci.logs ×3 · repo.search ×2 · 3m 12s` — 10px `#54545E`, arrow `#3F3F48`, hover/press lightens to `#7A7A84`. Summarizes step count, tool-call histogram, duration.
- **Expanded (tap)**: arrow flips to `▾`; below it a boxed table — margin-left 46px (aligns with turn content), surface bg, `.07` hairline border, `riseIn` animation. One row per step, `.05` hairline between rows, 9px 12px padding: left column 74px fixed 9px — **tool name in `#5B7EA6`** (e.g. `ci.logs`, `repo.search`, `monitor.add`) or **`reasoning` in `#7A7A84`**; right: 10px `#7A7A84` args/summary, with `→ result` portion in `#54545E`.
- Tap again to collapse. Default expanded/collapsed and whether args show are governed by the DISPLAY prefs in 2d.

**Rich markdown blocks inside agent turns** (the agent's markdown gets a richer interpretation):
- **Code block**: surface bg, `.08` hairline border; header row (7px 12px, hairline bottom): filename 9px tracked faint left, `copy` action in accent right; body 10.5px/1.75, horizontally scrollable; diff coloring: removed `#D06A6A`, added `#6BB08A`.
- **Task/status list**: rows of `▪ label` — done: accent ▪, 11px `#8A8A94` text; in-progress: `#D0A24C` ▪ + text; pending: all `#54545E`. (See 1c in the file for the boxed "BOOKING · 2/4" progress variant with header + counter.)
- **Action buttons** (see 1c): square buttons, 11px mono — primary: accent text, accent 1px border, accent tint fill; neutral: `#C9C9D0` text, `.18` hairline; dismissive: faint. Tapping one becomes the user's next turn (rendered as a user turn annotated `(via button)`).
- **Collapsible section** (see 1c "fare rules · 3 conditions"): same ▸/▾ muted-line pattern as traces, expanding to a surface-bg text panel.
- **Option/data table** (see 1c train options): bordered list, rows separated by hairlines, title + right-aligned value per row, 10px detail rows in faint.

**Composer** (pinned): surface bg, `.14` hairline border, accent `>`, placeholder `message juno` (uses the agent name), blinking caret, `⏎ send`.

Sample conversation: the "fix flaky ci pipeline" thread — user ask (21:40) → 9-step trace (21:41) → agent diagnosis with code diff + 4-item done list (21:44) → user follow-up (22:02) → 2-step trace → agent confirmation with `monitor #m-31 active` in faint.

### 2c — NEW THREAD

Minimal: status row → `NEW THREAD` title + `esc · cancel` right → tick strip → composer box: surface bg, **accent border**, 14px padding, min-height 140px, 13px placeholder `> what should juno do?` (accent prompt, agent name) with blinking caret → **full-width** start button directly below (12px top margin): `start thread ⏎`, 12px mono, accent text/border/tint, 12px vertical padding, must never wrap. Nothing else on the screen — no suggestions, no captions, no tab bar.

### 2d — CONFIG (settings)

Status row → `CONFIG` title + `leman v0.1` → tick strip → scrollable sections → tab bar (config active).

Sections (section-label pattern; 30px between sections, controls at 16px rhythm):

1. **SERVER** — connection status folded into the header rule right: `▪ connected · 42ms` in accent. Field label `hermes agent url` (11px `#9A9AA4`), text input (surface, `.14` border, accent `>`): `https://hermes.liquidx.net`. Below: `test connection` neutral button + `protocol v2 · streaming ok` faint caption.
2. **AUTH** — `api key` field, value masked `hm_••••••••••••3kf2` in `#7A7A84` +.14em, `reveal` action in accent right. Toggle row `unlock with biometrics` (on). Caption: `key stored in secure enclave · never synced`. Toggle spec: 40×20 square, on = accent border + tint + 16px accent knob right with glow; off = `.18` hairline border + `#54545E` knob left.
3. **AGENT IDENTITY** — header right: `default profile` faint. `agent name` text input (`juno`, blinking caret). `avatar` picker: five 38×38 square tiles with glyphs `✳ ◆ ▲ ● ⌬`; selected = accent border + `rgba(107,160,216,.12)` fill + accent glyph; unselected = `.12` hairline + `#54545E` glyph. Single-select. Caption: `shown in thread headers & turn labels · threads with their own profile override this`. **Note: no autonomy/spend/notify controls — those are per-thread, not global (screen not yet designed).**
4. **DISPLAY** — toggles: `expand traces by default` (off), `show tool args in traces` (on). These drive 2b's trace rendering.
5. **DATA** — `export threads` neutral button + `clear local cache` danger button (`#D06A6A` text, `rgba(208,106,106,.4)` border). Wipes the local cache only — the server is the system of record, so the list repopulates on the next sync (spec 03).

## Interactions & Behavior

- Trace lines, collapsible sections: tap toggles expand/collapse; expansion animates with `riseIn` (.3s). Arrow `▸`→`▾`.
- Thread rows: tap opens thread (2b). Pin/unpin via the pin glyph (give it a ≥44px hit area) — pinned threads move to the PINNED fold immediately.
- Filter: live as-you-type, substring on title + preview.
- Action buttons in agent turns: tapping appends a user turn `(via button)` and the agent's next turn (see 1c's booking-progress example for the running state).
- Running thread: pulsing dot; running turn label in accent (`running` instead of a timestamp).
- Hover states are desktop-only in the family; on mobile use pressed-state background `rgba(255,255,255,.04)`.
- Buttons/inputs: keyboard focus (if applicable) = 1px accent outline.

## State Management

- `threads[]`: id, title, preview, state (`running | needs_you | done | idle | scheduled`), progress (step m/n, optional), unread, pinned, lastActive, agentProfile { name, glyph }.
- `filterQuery` (thread list), `expandedTraces: Set<traceId>` (per thread view), per-thread turn list from the Hermes API: turns are `user | agent | trace`; trace = ordered steps of `{ kind: tool | reasoning, tool?, argsSummary, resultSummary }` + rollup (step count, tool histogram, duration).
- Settings: `serverUrl`, `apiKey` (secure storage), `biometrics`, `agentName`, `agentGlyph`, `expandTracesByDefault`, `showToolArgs`.
- Assume the API returns an agent profile per thread; fall back to the configured default identity when absent.

## Assets

No images. Tabler Icons (MIT): `message` (threads tab), `settings` (config tab) — 1.5 stroke, square linecap, miter join. All markers/avatars are text glyphs (`▪ ◆ ◇ ▸ ▾ > ✳ ◆ ▲ ● ⌬`) — do not replace with icon fonts. Font: Spline Sans Mono via Google Fonts.

## Files

- `LeMan Screens.dc.html` — screens; turn 2 (top section) is normative, turn 1 is reference.
- `Wanderloop Design System.dc.html` — family design system.
- `support.js` — prototype runtime only.
