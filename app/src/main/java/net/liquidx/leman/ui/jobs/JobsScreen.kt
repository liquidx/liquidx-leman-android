package net.liquidx.leman.ui.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.ui.components.EmptyLine
import net.liquidx.leman.ui.components.LemanTab
import net.liquidx.leman.ui.components.LemanTabBar
import net.liquidx.leman.ui.components.PromptField
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.TitleRow
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline

/** 2e — scheduled jobs list (jobs-tab design). */
@Composable
fun JobsScreen(
    state: JobsUiState,
    clock: String,
    tabs: List<LemanTab>,
    onEvent: (JobsEvent) -> Unit,
    onOpenJob: (String) -> Unit,
    onNewJob: () -> Unit,
    onOpenThreads: () -> Unit,
    onOpenConfig: () -> Unit,
    onSelectTab: (LemanTab) -> Unit = {},
) {
    ScreenFrame(
        statusRow = { StatusRow(clock, state.connState) },
        titleRow = {
            TitleRow(
                title = "jobs",
                readout = buildAnnotatedString {
                    append("${state.totalCount}")
                    if (state.pausedCount > 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = LemanColors.textFaint)) {
                            append("${state.pausedCount} paused")
                        }
                    }
                },
            )
        },
        bottom = {
            NewJobField(onNewJob)
            LemanTabBar(tabs, activeId = "jobs", onSelect = {
                when (it.id) {
                    "threads" -> onOpenThreads()
                    "config" -> onOpenConfig()
                    "jobs" -> Unit
                    else -> onSelectTab(it)
                }
            })
        },
    ) {
        LazyColumn {
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
            if (state.refreshFailed) {
                item(key = "refreshFailed") {
                    Text(
                        "▪ couldn't reach gateway · tap to retry",
                        style = LemanType.meta,
                        color = LemanColors.warn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEvent(JobsEvent.Refresh) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.items.size, key = { state.items[it].id }) { index ->
                JobRow(state.items[index], onOpen = { onOpenJob(state.items[index].id) })
            }
            if (state.loaded && state.items.isEmpty() && !state.refreshFailed) {
                item(key = "empty") {
                    EmptyLine("no jobs scheduled · add one below")
                }
            }
        }
    }
}

/** Name + schedule left; state and next fire time right. */
@Composable
private fun JobRow(item: JobListItem, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .hairline(HairlineSide.Bottom, LemanColors.hairline)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = LemanType.body,
                color = LemanColors.textPrimary,
                maxLines = 1,
            )
            Text(
                item.scheduleDisplay,
                style = LemanType.meta,
                color = LemanColors.textFaint,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                item.stateLabel,
                style = LemanType.meta,
                color = when (item.tone) {
                    JobTone.Accent -> LemanColors.accent
                    JobTone.Danger -> LemanColors.danger
                    JobTone.Faint -> LemanColors.textFaint
                },
            )
            item.nextRunLabel?.let {
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

/** Pinned above the tab bar: accent-bordered affordance for the add screen. */
@Composable
private fun NewJobField(onNewJob: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hairline(HairlineSide.Top, LemanColors.hairline)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box {
            PromptField(
                state = rememberTextFieldState(),
                placeholder = "new job",
                hint = "⏎ create",
                accentBorder = true,
            )
            // The field is an affordance for the edit screen, not an inline editor.
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(onClick = onNewJob),
            )
        }
    }
}
