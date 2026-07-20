package net.liquidx.leman.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.liquidx.leman.domain.model.ActionButton
import net.liquidx.leman.domain.model.ActionKind
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.domain.model.OptionRow
import net.liquidx.leman.domain.model.TaskItem
import net.liquidx.leman.domain.model.TaskItemState
import net.liquidx.leman.ui.markdown.LemanMarkdown
import net.liquidx.leman.ui.markdown.MarkdownBody
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanMotion
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline
import net.liquidx.leman.ui.theme.hairlineBorder

/** Task/status list rows: `▪ label` (spec 05/06). */
@Composable
fun TaskListBlock(block: AgentBlock.TaskList, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (block.title != null) {
            Row(modifier = Modifier.padding(bottom = 2.dp)) {
                Text(block.title.orEmpty().uppercase(), style = LemanType.label)
                block.counter?.let {
                    Text(" · $it", style = LemanType.label, color = LemanColors.textSecondary)
                }
            }
        }
        block.items.forEach { item -> TaskRow(item) }
    }
}

@Composable
private fun TaskRow(item: TaskItem) {
    val (marker, text) = when (item.state) {
        TaskItemState.Done -> LemanColors.accent to Color(0xFF8A8A94)
        TaskItemState.Active -> LemanColors.warn to LemanColors.warn
        TaskItemState.Pending -> LemanColors.textFaint to LemanColors.textFaint
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("▪ ", style = LemanType.meta, color = marker)
        Text(item.label, style = LemanType.meta.copy(fontSize = 11.sp), color = text)
    }
}

/**
 * Action buttons (spec 05): tapping one becomes the user's next turn, marked
 * `(via button)`. All siblings disable after any is tapped.
 */
@Composable
fun ActionRow(
    block: AgentBlock.Actions,
    enabled: Boolean,
    onAction: (ActionButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        block.buttons.forEach { button ->
            LemanButton(
                label = button.label,
                onClick = { onAction(button) },
                enabled = enabled,
                kind = when (button.kind) {
                    ActionKind.Primary -> LemanButtonKind.Primary
                    ActionKind.Neutral -> LemanButtonKind.Neutral
                    ActionKind.Dismiss -> LemanButtonKind.Dismiss
                },
            )
        }
    }
}

/** Collapsible section — same ▸/▾ muted-line grammar as traces (spec 05). */
@Composable
fun CollapsibleBlock(
    block: AgentBlock.Collapsible,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
) {
    var expanded by rememberSaveable(block.summary) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
                .semantics { stateDescription = if (expanded) "expanded" else "collapsed" },
        ) {
            Text(
                if (expanded) "▾ " else "▸ ",
                style = LemanType.meta,
                color = LemanColors.gutter,
            )
            Text(block.summary, style = LemanType.meta, color = LemanColors.textFaint)
        }
        AnimatedVisibility(visible = expanded, enter = LemanMotion.riseInFast) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LemanColors.surface)
                    .hairlineBorder(Color(0x12FFFFFF))
                    .padding(12.dp),
            ) {
                MarkdownBody(block.body, style = LemanMarkdown.agentTurn, onLinkClick = onLinkClick)
            }
        }
    }
}

/** Option/data table: hairline-boxed rows, title + right value + faint detail (spec 05). */
@Composable
fun OptionTableBlock(block: AgentBlock.OptionTable, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().hairlineBorder(LemanColors.hairline)) {
        block.rows.forEachIndexed { index, row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (index < block.rows.lastIndex) {
                            Modifier.hairline(HairlineSide.Bottom, LemanColors.hairlineFaint)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        row.title,
                        style = LemanType.meta.copy(fontSize = 11.sp),
                        color = LemanColors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    row.value?.let {
                        Text(it, style = LemanType.meta.copy(fontSize = 11.sp), color = LemanColors.accent)
                    }
                }
                row.detail?.let {
                    Text(
                        it,
                        style = LemanType.meta,
                        color = LemanColors.textFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A)
@Composable
private fun RichBlocksPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(12.dp)) {
        TaskListBlock(
            AgentBlock.TaskList(
                title = "booking",
                counter = "2/4",
                items = listOf(
                    TaskItem("find route", TaskItemState.Done),
                    TaskItem("hold seat", TaskItemState.Done),
                    TaskItem("confirm payment", TaskItemState.Active),
                    TaskItem("send calendar invite", TaskItemState.Pending),
                ),
            ),
        )
        ActionRow(
            AgentBlock.Actions(
                listOf(
                    ActionButton("confirm booking", "confirm", ActionKind.Primary),
                    ActionButton("see options", "options", ActionKind.Neutral),
                    ActionButton("cancel", "cancel", ActionKind.Dismiss),
                ),
            ),
            enabled = true,
            onAction = {},
        )
        CollapsibleBlock(AgentBlock.Collapsible("fare rules · 3 conditions", "non-refundable after 24h."))
        OptionTableBlock(
            AgentBlock.OptionTable(
                listOf(
                    OptionRow("tgv 6:12", "74 chf", "direct · 3h 41m"),
                    OptionRow("tgv 8:47", "49 chf", "1 change · 4h 10m"),
                ),
            ),
        )
    }
}
