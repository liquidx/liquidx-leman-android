# 06 — Theme, Compose components, icons

The design files are normative for every value here; this doc defines how they become
Compose code. Fidelity target: pixel-faithful at 380dp design width, fluid single column.

## LemanTheme

Custom design system carried by composition locals; Material3 is a substrate only
(ripple/indication, semantics) — **no Material components in the visual tree** (no
`Scaffold`+`TopAppBar`, no `NavigationBar`, no M3 `Button`/`Switch`).

```kotlin
object LemanColors {          // exactly the handoff tokens
    val base = Color(0xFF08080A);       val surface = Color(0xFF0C0C10)
    val accent = Color(0xFF6BA0D8);     val accentMuted = Color(0xFF5B7EA6)
    val textPrimary = Color(0xFFE6E6EA); val textSecondary = Color(0xFF9A9AA4)
    val textTertiary = Color(0xFF7A7A84); val textFaint = Color(0xFF54545E)
    val gutter = Color(0xFF3F3F48)
    val warn = Color(0xFFD0A24C);       val danger = Color(0xFFD06A6A)
    val diffGreen = Color(0xFF6BB08A)
    val hairlineFaint = Color(0x0DFFFFFF)   // .05
    val hairline = Color(0x17FFFFFF)        // .09
    val hairlineStrong = Color(0x24FFFFFF)  // .14
    val accentTint = Color(0x1A6BA0D8)      // .1 fill
    // glow: 0 0 8px rgba(107,160,216,.6) — see Glow modifier below
}
```

- **Font**: Spline Sans Mono 400/500/600, bundled as `res/font/` ttfs (offline app;
  no downloadable-fonts dependency on first run). One `FontFamily` used by every style.
- **Type scale** as `LemanType`: `display` 23sp/600/+0.05em (rendered uppercase),
  `value` 14sp, `body` 12–13sp lh 1.65–1.7, `label` 10sp +0.16em uppercase,
  `meta` 10sp, `micro` 9sp +0.1em. Letter-spacing in `em` units; `PlatformTextStyle
  (includeFontPadding = false)` everywhere so the mono grid matches the mocks.
- **Shapes**: everything `RectangleShape`. No elevation/shadows.
- **Glow**: box-shadow doesn't exist in Compose; implement `Modifier.glow(color,
  radius = 8.dp, alpha = .6f)` drawing a blurred circle/rect behind content
  (`drawBehind` + `Paint` with `BlurMaskFilter`). Used by status dots, toggle knobs,
  unread squares, accent bars.
- **Density**: the mocks are px at 380 design width; treat px ≈ dp 1:1. Hairlines use
  exact 1px: `Modifier.hairline(side, color)` drawing with `Stroke(width = 1f)` in px,
  not dp, so they never fatten on high-density screens.
- **Pressed state**: mobile has no hover; global `Indication` = flat
  `rgba(255,255,255,.04)` overlay (no ripple animation — mechanical, instant).
- **Focus**: 1px accent outline via `Modifier.focusOutline()` for keyboard/d-pad.

## Motion tokens

```kotlin
object LemanMotion {
    val riseIn = fadeIn(tween(500)) + slideInVertically(tween(500)) { 7 }  // stagger .06s/index
    val riseInFast = …(300)                                // trace/collapsible expansion
    val barDraw = tween<Float>(800, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))
    // pulse: infiniteTransition scale .5→2.6 / alpha .8→0, 2s ease-out — running dots
    // caret: 1s steps(1) on/off — composer & streaming cursor
}
```

Rows animate `riseIn` once per content-appearance (keyed by id), staggered ≤ 8 items.
All animation honors `Settings.Global.ANIMATOR_DURATION_SCALE = 0` (tests, a11y).

## Component catalog (`ui/components/`)

Shared chrome:

| Composable | Spec |
|------------|------|
| `StatusRow(clock, connState)` | 9sp micro; right side per 04's state table |
| `TitleRow(title, readout)` | display type left, 10sp meta right (readout can carry accent spans) |
| `TickStrip()` | 9dp tall, 1px ticks every 24dp on hairline baseline — `Canvas` |
| `LemanTabBar(active)` | 2 tabs threads/config; 16dp Tabler icon + 9sp label; active = accent + 2dp top rule; inactive `#5C5C66` |
| `ScreenFrame { … }` | column: statusRow/titleRow/tickStrip slots + content + pinned bottom slots; 18dp h-padding; edge-to-edge insets |

Inputs & controls:

| Composable | Spec |
|------------|------|
| `PromptField` | the one reusable input: accent `>`, `BasicTextField`, blinking caret (custom cursor brush + when empty an explicit caret block), right `⏎ hint`; variants: hairline `.14` border (search/composer) / accent border (new-thread, 2c composer) |
| `LemanButton(kind)` | primary (accent text/border/tint) · neutral (`#C9C9D0` + `.18` hairline) · danger (`#D06A6A` + red hairline) · dismissive (faint); square, 11–12sp mono |
| `LemanToggle(checked)` | 40×20 square; on = accent border+tint, 16dp accent knob right + glow; off = `.18` hairline, `#54545E` knob left; animates knob translation 120ms linear |
| `GlyphTile(glyph, selected)` | 38×38 avatar picker tile (2d) |
| `SectionHeader(label, count, right)` | `LABEL · count ——— right` with hairline filling the gap |

Thread list (2a):

| Composable | Spec |
|------------|------|
| `StatusDot(state, running)` | 9dp circle: filled accent+glow+pulse halo (running) / filled warn (needs_you) / hollow 1px `#54545E` (done/idle/scheduled) |
| `ThreadRow(thread, onOpen, onTogglePin)` | 13dp v-padding, hairline bottom `.05`; title 14sp (unread `#E6E6EA` / read `#9A9AA4`) + 5×5 accent unread square; preview 11sp `#7A7A84` 1-line ellipsis; meta `▪ state · time`; pin glyph `◆` accent / `◇` `#2A2A32` with its own ≥44dp hit area (`minimumInteractiveComponentSize`) — row tap opens, glyph tap pins |
| `EmptyLine(text)` | centered faint 11sp — filter empty state & error states (04) |

Thread view (2b):

| Composable | Spec |
|------------|------|
| `TurnGutterRow(timestamp) { content }` | 34dp right-aligned 9sp `#3F3F48` gutter + content; `running` label in accent replaces timestamp on live turn |
| `UserTurn` | 2dp accent left border, 12dp pad; `YOU` tag 9sp +.14em `#54545E`; body 12sp `#E6E6EA`; failed-send variant per 04 |
| `AgentTurn` | 2dp `rgba(255,255,255,.12)` border; agent-name tag in accent; body = block list (05) |
| `TraceLine(rollup, expanded)` | collapsed `▸ trace · …` 10sp `#54545E`, arrow `#3F3F48`; pressed lightens `#7A7A84`; live variant pulses |
| `TraceTable(steps, showArgs)` | 46dp left margin, surface bg, `.07` border, riseIn; rows: 74dp tool column (`#5B7EA6` tool / `#7A7A84` reasoning) + summary with `→ result` in `#54545E` |
| `ThreadHeader` | back `‹`, 22×22 `AgentAvatar` (accent-bordered square + glyph), title + 9sp status line, pin |
| `Composer(agentName)` | PromptField `message {agent}` + `⏎ send`; disabled-but-visible when `NotConfigured` |

Rich blocks (05 owns behavior): `CodeBlock`, `TaskListBlock`, `ActionRow`,
`CollapsibleBlock`, `OptionTableBlock`.

Every catalog component gets a `@Preview` (component gallery file per group) — these
previews are also the screenshot-test subjects (07).

## Screens = composition only

Screen composables assemble catalog components and bind `UiState`; they contain no
styling constants. List = `LazyColumn` (threads keyed by id; turns keyed by turn id,
`reverseLayout = false`, anchored-to-bottom behavior per 05).

## Icons & glyphs

- **Tabler Icons (MIT), exactly two**, tab bar only: `message` (threads), `settings`
  (config). Import as `ImageVector` vector drawables from the SVG paths in the mocks:
  24dp viewport, stroke 1.5, `StrokeCap.Butt`/`StrokeJoin.Miter`, drawn at 16dp.
  License note in `NOTICE`.
- Existing template drawables (`ic_home`, `ic_favorite`, `ic_account_box`) are deleted.
- **Everything else is text**: `▪ ◆ ◇ ▸ ▾ > ‹ ✳ ▲ ● ⌬ ◉ ⏎` rendered in Spline Sans
  Mono — never replaced with icon fonts or vectors. Add a glyph-coverage check to tests:
  render each glyph and assert non-zero measured width (font fallback detection);
  Spline Sans Mono lacks some of these, and silent fallback to a serif glyph would be
  invisible in code review.
- Launcher icon: adaptive icon, base `#08080A` background, accent `✳`-style foreground —
  replace template asset before first install to a device.

## Accessibility

- Touch targets ≥44dp (pins, trace lines, action buttons) even where visuals are small.
- Semantics: status dots get `contentDescription = state`; trace lines expose
  expand/collapse state; timestamps get full-date descriptions.
- Font scaling: sp everywhere; layout must survive 1.3× (single-column log degrades
  gracefully — truncation only on one-line titles/previews).
- Contrast: several tokens (`#54545E` on `#08080A`) are below AA by design; body text
  and interactive labels stay ≥ `#9A9AA4`. Don't "fix" faint gutter text — it's the
  design language — but never encode *sole* meaning in faint text or color (state tokens
  always pair dot color with the state word).
