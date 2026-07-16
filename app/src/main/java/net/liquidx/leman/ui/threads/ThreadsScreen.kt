package net.liquidx.leman.ui.threads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.components.DotStyle
import net.liquidx.leman.ui.components.EmptyLine
import net.liquidx.leman.ui.components.LemanTab
import net.liquidx.leman.ui.components.LemanTabBar
import net.liquidx.leman.ui.components.PromptField
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.SectionHeader
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.ThreadRow
import net.liquidx.leman.ui.components.TitleRow
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline

/** 2a — thread list. */
@Composable
fun ThreadsScreen(
    state: ThreadsUiState,
    clock: String,
    tabs: List<LemanTab>,
    onEvent: (ThreadsEvent) -> Unit,
    onOpenThread: (String) -> Unit,
    onNewThread: () -> Unit,
    onOpenConfig: () -> Unit,
    onSelectTab: (LemanTab) -> Unit = {},
) {
    ScreenFrame(
        statusRow = { StatusRow(clock, state.connState) },
        titleRow = {
            TitleRow(
                title = "threads",
                readout = buildAnnotatedString {
                    append("${state.totalCount}")
                    if (state.runningCount > 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = LemanColors.accent)) {
                            append("${state.runningCount} running")
                        }
                    }
                },
            )
        },
        bottom = {
            NewThreadField(onNewThread)
            LemanTabBar(tabs, activeId = "threads", onSelect = {
                when (it.id) {
                    "config" -> onOpenConfig()
                    "threads" -> Unit
                    else -> onSelectTab(it)
                }
            })
        },
    ) {
        val filterState = rememberTextFieldState(state.filter)
        LaunchedEffect(filterState) {
            snapshotFlow { filterState.text.toString() }
                .collect { onEvent(ThreadsEvent.SetFilter(it)) }
        }
        LazyColumn {
            // Filter row scrolls with the list — it is not pinned (design 2a).
            item(key = "filter") {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    PromptField(
                        state = filterState,
                        placeholder = "filter threads",
                        hint = "⏎ find",
                    )
                }
            }
            if (state.connState is ConnState.Unauthorized) {
                item(key = "authFailed") {
                    Text(
                        "auth failed · fix api key in config",
                        style = LemanType.meta,
                        color = LemanColors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenConfig)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            state.sections.forEach { section ->
                item(key = "header-${section.key}") {
                    SectionHeader(section.key, section.count, section.dateLabel)
                }
                items(section.items.size, key = { section.items[it].id }) { index ->
                    val item = section.items[index]
                    ThreadRow(
                        title = item.title,
                        preview = item.preview,
                        stateLabel = item.stateLabel,
                        stateColor = when (item.tone) {
                            StateTone.Accent -> LemanColors.accent
                            StateTone.Warn -> LemanColors.warn
                            StateTone.Danger -> LemanColors.danger
                            StateTone.Faint -> LemanColors.textFaint
                        },
                        timeLabel = item.timeLabel,
                        dot = when {
                            item.running -> DotStyle.Running
                            item.failed -> DotStyle.Failed
                            else -> DotStyle.Hollow
                        },
                        unread = item.unread,
                        pinned = item.pinned,
                        onOpen = { onOpenThread(item.id) },
                        onTogglePin = { onEvent(ThreadsEvent.TogglePin(item.id)) },
                        sourceLabel = item.sourceLabel,
                    )
                }
            }
            if (state.loaded && state.sections.isEmpty()) {
                item(key = "empty") {
                    EmptyLine(
                        if (state.filter.isBlank()) "no threads yet · start one below"
                        else "no threads match \"${state.filter}\"",
                    )
                }
            }
        }
    }
}

/** Pinned above the tab bar: the one accent-bordered input; tap opens 2c. */
@Composable
private fun NewThreadField(onNewThread: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hairline(HairlineSide.Top, LemanColors.hairline)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box {
            PromptField(
                state = rememberTextFieldState(),
                placeholder = "new thread",
                hint = "⏎ start",
                accentBorder = true,
            )
            // The field is an affordance for 2c, not an inline editor.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClick = onNewThread),
            )
        }
    }
}
