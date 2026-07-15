# 05 — Markdown & rich block rendering

## Two layers of "rich"

The verified gateway (02) returns an agent turn as a **single markdown string** (`output`
/ `message.delta` text) — there is no structured block payload on the wire. So both layers
below are produced **client-side** from that one string:

1. **`markdown`** — prose CommonMark the agent writes. Rendered by our renderer with
   LeMan styling.
2. **`blocks[]`** — the richer design elements (fenced code/diff with filenames,
   task/status lists, option tables, collapsible sections, and — where the agent emits a
   recognized convention — action buttons). These are derived by a **markdown
   post-processor** that segments the parsed CommonMark AST into the `List<AgentBlock>`
   domain model. The renderer consumes only that model, so if a future gateway *does* start
   emitting typed JSON blocks, only the producer changes — the renderer is unaffected.
   Interactive action buttons depend on an agreed markdown/marker convention with the
   agent; until one exists, agent output renders as prose + code/list/table blocks only.

```kotlin
sealed interface AgentBlock {
    data class Prose(val markdown: String) : AgentBlock
    data class Code(val filename: String?, val language: String?, val text: String,
                    val isDiff: Boolean) : AgentBlock
    data class TaskList(val title: String?, val counter: String?,        // "BOOKING · 2/4"
                        val items: List<TaskItem>) : AgentBlock          // done|active|pending
    data class Actions(val buttons: List<ActionButton>) : AgentBlock     // primary|neutral|dismiss
    data class Collapsible(val summary: String, val body: String) : AgentBlock
    data class OptionTable(val rows: List<OptionRow>) : AgentBlock       // title+value+detail
}
```

A turn renders as an ordered `List<AgentBlock>`; plain-markdown turns are one `Prose`.

## Markdown pipeline (Prose blocks)

**Parser:** `org.commonmark:commonmark` (+ `commonmark-ext-gfm-tables`,
`commonmark-ext-gfm-strikethrough`). Battle-tested, pure JVM, no Android deps —
parsing is unit-testable off-device.

**Renderer:** hand-written `commonmark AST → Compose`, in `ui/markdown/`. No third-party
Compose-markdown lib: the design is too opinionated (mono everywhere, glyph bullets,
hairline tables, custom code header) for themeable off-the-shelf renderers to reach
pixel fidelity.

```kotlin
@Composable fun MarkdownBody(
    markdown: String,
    style: MarkdownStyle = LemanMarkdown.agentTurn,   // or .userTurn
    onLinkClick: (String) -> Unit,
)
```

Node mapping (all values from the design system):

| Node | Rendering |
|------|-----------|
| Paragraph | 12px / lh 1.7, `#C9C9D0` (agent) / `#E6E6EA` (user), inline content as `AnnotatedString` |
| Emphasis / Strong | color-emphasis to `#E6E6EA` (agent turns emphasize with color, not italics); strong also weight 600 |
| Inline code | `#E6E6EA` on `rgba(255,255,255,.06)`, no radius |
| Link | accent `#6BA0D8`, no underline; `LinkAnnotation` → `onLinkClick` (opens Custom Tab) |
| Heading | there is no heading scale inside turns — render as strong paragraph; H1–H3 add 4px top spacing |
| Bullet list | `▪` marker `#54545E`, 9px indent grid; ordered lists use faint `01 02` numerals |
| Task list item | maps to TaskList item styling (accent/warn/faint ▪) |
| Fenced code | promote to the `Code` block component below |
| Blockquote | 2px hairline left border `rgba(255,255,255,.12)`, 12px pad — same grammar as agent turns |
| Table (GFM) | hairline-boxed rows like the option table; header row = 10px tracked label |
| Thematic break | 1px hairline `.09` |

Inline content builds one `AnnotatedString` per paragraph (cheap, selectable via
`SelectionContainer` on the turn).

## Code / diff block component

Matches 2b exactly:

- Container: surface `#0C0C10`, `.08` hairline border, no radius.
- Header row (only when filename present): 7px 12px padding, hairline bottom; filename
  9px tracked faint; `copy` in accent right → `ClipboardManager`, flips to `copied` for 1.5s.
- Body: 10.5px / lh 1.75, **horizontal scroll** (`horizontalScroll`), no wrap.
- Diff mode (`isDiff` or language `diff`): line-prefix coloring — `-` lines `#D06A6A`,
  `+` lines `#6BB08A`, `@@` faint. Implemented as per-line spans; no syntax
  highlighting for other languages in v0.1 (fenced code renders monochrome `#C9C9D0`;
  a highlighter is a later, isolated upgrade inside this one composable).

## Interactive blocks

- **Actions** (only if an action-button convention with the agent exists — see above):
  square buttons per design (primary accent tint / neutral hairline / dismissive faint).
  Tap → `ThreadEvent.ActionTapped(button)` → a normal `sendMessage` with the button's
  payload as the user turn (marked `viaButton` for the `(via button)` label), which starts
  a new run (02/03). Buttons disable after any sibling is tapped (single choice per action
  group; state derives from whether a later user turn references the group).
- **Collapsible**: same `▸/▾` muted-line pattern as traces; body is a surface-bg
  `MarkdownBody`. Expansion state is UI-local (`rememberSaveable`), animates `riseIn`.
- **TaskList / OptionTable**: pure display, parsed from the agent's markdown. During a live
  run they re-derive as `message.delta` text accumulates and the markdown is re-parsed.

## Thinking traces

Traces are their own turn kind, not markdown (see component spec in 06 for visuals). A
trace is composed **client-side from run events** (02), not delivered as a structured blob:

- Each `tool.started`/`tool.completed` pair is one **tool step** — `tool` name,
  `preview` (the args/query summary), `duration` seconds, and an `error` flag. Any
  `reasoning.available` text becomes a **reasoning step** (or the trace's summary line).
- Collapsed line text is composed from the rollup:
  `▸ trace · 6 steps · web_search ×4 · repo.read ×2 · 3m 12s` (tool histogram from the
  `tool` fields, sorted by count desc, top 2 tools; duration = sum of step `duration`s or
  run wall-clock, formatted `Xm Ys`).
- Expanded table renders the steps; the tool `preview` respects the `show_tool_args` pref —
  off means tool rows show the tool name + `· 5.9s` timing only, no query text. A step with
  `error:true` renders in danger.
- Default expanded/collapsed comes from `expand_traces_by_default`; per-trace toggles
  override it for the session (`expandedTraces: Set<turnId>` in ViewModel state,
  survives rotation via `SavedStateHandle`).
- A **live** trace (run in flight, `tool.started`/`reasoning.available` events arriving)
  shows the collapsed line with a growing step count and pulsing accent `▸`; steps append
  with `riseIn` when expanded.
- A persisted trace whose full step detail wasn't captured can be re-fetched lazily by
  replaying `GET /v1/runs/{runId}/events` on expand (02/03).

## Streaming render rules

- `message.delta` text accumulates in the ViewModel; `MarkdownBody` re-parses the
  accumulated string per frame-batch. CommonMark parses a few KB in <1ms — fine at the
  ~10 Hz deltas arrive. If profiling ever disagrees: parse only the tail paragraph
  (split on last `\n\n`), keep completed paragraphs' `AnnotatedString`s cached. Don't
  build this until measured.
- Unterminated markdown mid-stream (open fence, half a table) must render *something*
  sane: the renderer treats an unclosed fence as code-so-far, never crashes on partial AST.
- A streaming cursor — 7×14px accent block with the `caret` blink — is appended after the
  last streamed character of the active turn.
- Auto-scroll: follow the bottom while streaming only if the user is already at the
  bottom (`isScrolledToEnd`); otherwise show nothing and let them catch up — no
  "jump to latest" chrome exists in the design.

## Performance & testing hooks

- Parsing runs in the composable via `remember(markdown)`; heavy blocks (long code)
  additionally cache line-split results.
- Turn list is a `LazyColumn` keyed by turn id; only visible turns parse.
- Renderer is verified by screenshot tests over a corpus of markdown fixtures (07),
  including a torture doc (nested lists, mixed tables, 300-line diff, partial fences).
