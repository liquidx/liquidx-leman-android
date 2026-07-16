package net.liquidx.leman.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.domain.model.ActionButton
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind
import net.liquidx.leman.domain.formatDuration
import net.liquidx.leman.ui.markdown.LemanMarkdown
import net.liquidx.leman.ui.markdown.MarkdownBody
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanMotion
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline
import net.liquidx.leman.ui.theme.hairlineBorder

/** Turn log layout (2b): 34dp right-aligned timestamp gutter + content. */
@Composable
fun TurnGutterRow(
    timestamp: String?,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (running) "running" else timestamp.orEmpty(),
            style = LemanType.micro,
            color = if (running) LemanColors.accent else LemanColors.gutter,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(34.dp).padding(top = 2.dp),
        )
        Box(Modifier.weight(1f).padding(start = 12.dp)) { content() }
    }
}

private fun Modifier.turnBorder(color: Color): Modifier = drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
}

/** User turn (2b): 2dp accent left border, YOU tag; failed variant per spec 04. */
@Composable
fun UserTurn(
    markdown: String,
    viaButton: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .turnBorder(if (failed) LemanColors.danger else LemanColors.accent)
            .padding(start = 12.dp),
    ) {
        Text(
            if (viaButton) "YOU · VIA BUTTON" else "YOU",
            style = LemanType.turnTag,
            color = LemanColors.textFaint,
        )
        MarkdownBody(
            markdown,
            style = LemanMarkdown.userTurn,
            modifier = Modifier.padding(top = 5.dp),
        )
        if (failed) {
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Text("▪ failed · ", style = LemanType.meta, color = LemanColors.danger)
                Text(
                    "retry",
                    style = LemanType.meta,
                    color = LemanColors.accent,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
                Text(" / ", style = LemanType.meta, color = LemanColors.textFaint)
                Text(
                    "discard",
                    style = LemanType.meta,
                    color = LemanColors.textFaint,
                    modifier = Modifier.clickable(onClick = onDiscard),
                )
            }
        }
    }
}

/**
 * Agent turn (2b): 2dp `rgba(255,255,255,.12)` left border, agent-name tag in
 * accent, body = ordered block list (spec 05). A streaming turn appends the
 * blinking cursor block.
 */
@Composable
fun AgentTurn(
    agentName: String,
    blocks: List<AgentBlock>,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    actionsEnabled: Boolean = true,
    onAction: (ActionButton) -> Unit = {},
    onLinkClick: (String) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .turnBorder(LemanColors.agentBorder)
            .padding(start = 12.dp),
    ) {
        Text(agentName.uppercase(), style = LemanType.turnTag, color = LemanColors.accent)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 5.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is AgentBlock.Prose -> MarkdownBody(block.markdown, LemanMarkdown.agentTurn, onLinkClick = onLinkClick)
                    is AgentBlock.Code -> CodeBlock(block)
                    is AgentBlock.TaskList -> TaskListBlock(block)
                    is AgentBlock.Actions -> ActionRow(block, actionsEnabled, onAction)
                    is AgentBlock.Collapsible -> CollapsibleBlock(block)
                    is AgentBlock.OptionTable -> OptionTableBlock(block)
                }
            }
            if (streaming) BlinkingCaret()
        }
    }
}

/**
 * Collapsed trace line (2b): `▸ trace · 9 steps · ci.logs ×3 · 3m 12s`, 10sp
 * faint; live variant pulses the accent arrow while events arrive.
 */
@Composable
fun TraceLine(
    rollup: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    val arrowColor = if (live) LemanColors.accent else LemanColors.gutter
    val arrowAlpha = if (live) {
        val transition = rememberInfiniteTransition(label = "liveTrace")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "traceArrow",
        ).value
    } else {
        1f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = if (expanded) "expanded" else "collapsed" }
            .padding(vertical = 4.dp),
    ) {
        Text(
            if (expanded) "▾ " else "▸ ",
            style = LemanType.meta,
            color = arrowColor,
            modifier = Modifier.alpha(arrowAlpha),
        )
        Text(rollup, style = LemanType.meta, color = LemanColors.textFaint)
    }
}

/**
 * Expanded trace table (2b): 46dp left margin, surface bg, hairline border;
 * 74dp fixed tool column; `show_tool_args` off hides the args summary.
 */
@Composable
fun TraceTable(
    trace: Trace,
    showArgs: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 46.dp)
            .background(LemanColors.surface)
            .hairlineBorder(Color(0x12FFFFFF)),
    ) {
        trace.steps.forEachIndexed { index, step ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (index < trace.steps.lastIndex) {
                            Modifier.hairline(HairlineSide.Bottom, LemanColors.hairlineFaint)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    text = if (step.kind == TraceStepKind.Tool) step.tool.orEmpty() else "reasoning",
                    style = LemanType.micro,
                    color = when {
                        step.error -> LemanColors.danger
                        step.kind == TraceStepKind.Tool -> LemanColors.accentMuted
                        else -> LemanColors.textTertiary
                    },
                    modifier = Modifier.width(74.dp),
                )
                Column(Modifier.weight(1f)) {
                    val summary = when {
                        step.kind == TraceStepKind.Reasoning -> step.summary.orEmpty()
                        showArgs -> step.summary.orEmpty()
                        else -> ""
                    }
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            style = LemanType.meta,
                            color = if (step.error) LemanColors.danger else LemanColors.textTertiary,
                        )
                    }
                    if (step.kind == TraceStepKind.Tool && step.durationSeconds > 0) {
                        Text(
                            "→ ${if (step.error) "error" else "done"} · ${formatDuration(step.durationSeconds)}",
                            style = LemanType.meta,
                            color = if (step.error) LemanColors.danger else LemanColors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

/** Trace turn = line + expandable table with riseIn (spec 05/06). */
@Composable
fun TraceTurn(
    trace: Trace,
    rollup: String,
    expanded: Boolean,
    showArgs: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TraceLine(rollup = rollup, expanded = expanded, onToggle = onToggle, live = live)
        AnimatedVisibility(visible = expanded, enter = LemanMotion.riseInFast) {
            TraceTable(trace = trace, showArgs = showArgs, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 22×22 accent-bordered square avatar with the agent glyph (2b header). */
@Composable
fun AgentAvatar(glyph: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(22.dp)
            .background(LemanColors.accentFillSoft)
            .hairlineBorder(LemanColors.accentBorderSoft),
    ) {
        Text(glyph, style = LemanType.meta, color = LemanColors.accent)
    }
}

/** Thread view header (2b): ‹ back · avatar · title/status · pin. */
@Composable
fun ThreadHeader(
    title: String,
    statusLine: String,
    agentGlyph: String,
    pinned: Boolean,
    onBack: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .hairline(HairlineSide.Bottom, LemanColors.hairline)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "back" },
        ) {
            Text("‹", style = LemanType.value, color = LemanColors.textSecondary)
        }
        AgentAvatar(agentGlyph)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                title,
                style = LemanType.value.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600),
                color = LemanColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine,
                style = LemanType.micro,
                color = LemanColors.agentNameDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onTogglePin)
                .semantics { contentDescription = if (pinned) "unpin" else "pin" },
        ) {
            Text(
                if (pinned) "◆" else "◇",
                style = LemanType.value,
                color = if (pinned) LemanColors.accent else LemanColors.pinIdle,
            )
        }
    }
}

/** Composer (2b): `message {agent}` + ⏎ send; disabled-but-visible when unconfigured. */
@Composable
fun Composer(
    agentName: String,
    state: TextFieldState,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        PromptField(
            state = state,
            placeholder = "message $agentName",
            hint = "⏎ send",
            enabled = enabled,
            onSubmit = onSend,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A, widthDp = 380)
@Composable
private fun ThreadViewPreview() {
    val trace = Trace(
        steps = listOf(
            TraceStep(TraceStepKind.Reasoning, summary = "user wants the flaky ci pipeline diagnosed"),
            TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "fetch last 20 runs", durationSeconds = 5.9),
            TraceStep(TraceStepKind.Tool, tool = "repo.search", summary = "grep test_retry_backoff", durationSeconds = 3.2, error = true),
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 12.dp)) {
        ThreadHeader("fix flaky ci pipeline", "▪ DONE · juno · ops profile", "✳", pinned = true, onBack = {}, onTogglePin = {})
        TurnGutterRow("21:40") {
            UserTurn("the ci pipeline keeps flaking on the retry test — find it and fix it", viaButton = false, failed = false)
        }
        TurnGutterRow("21:41") {
            TraceTurn(trace, "trace · 3 steps · ci.logs · repo.search · 9.1s", expanded = true, showArgs = true, onToggle = {})
        }
        TurnGutterRow("21:44") {
            AgentTurn(
                "juno",
                listOf(AgentBlock.Prose("found it. the flake is in **test_retry_backoff** — it asserts on a real `0.5s` timer.")),
            )
        }
        TurnGutterRow(null, running = true) {
            AgentTurn("juno", listOf(AgentBlock.Prose("checking the last forty runs")), streaming = true)
        }
        TurnGutterRow("22:02") {
            UserTurn("confirm booking", viaButton = true, failed = false)
        }
        TurnGutterRow("22:03") {
            UserTurn("this send failed", viaButton = false, failed = true)
        }
        Composer("juno", rememberTextFieldState(), {})
    }
}
