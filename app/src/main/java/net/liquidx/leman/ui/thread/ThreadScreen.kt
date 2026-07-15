package net.liquidx.leman.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.Turn
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.domain.rollupText
import net.liquidx.leman.ui.components.AgentTurn
import net.liquidx.leman.ui.components.Composer
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.ThreadHeader
import net.liquidx.leman.ui.components.TraceTurn
import net.liquidx.leman.ui.components.TurnGutterRow
import net.liquidx.leman.ui.components.UserTurn
import net.liquidx.leman.ui.markdown.segmentBlocks
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.lemanScreenBackground

/** 2b — thread view (session log). */
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    clock: String,
    timestampOf: (Turn) -> String,
    onEvent: (ThreadEvent) -> Unit,
    onBack: () -> Unit,
    onLinkClick: (String) -> Unit = {},
) {
    var composerText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the bottom while streaming only if already there (spec 05).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(state.turns.size, state.streaming?.text?.length) {
        if (atBottom && listState.layoutInfo.totalItemsCount > 0) {
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .lemanScreenBackground()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        StatusRow(clock, state.connState)
        ThreadHeader(
            title = state.thread?.title ?: "",
            statusLine = state.statusLine,
            agentGlyph = state.agentProfile.glyph,
            pinned = state.thread?.pinned == true,
            onBack = onBack,
            onTogglePin = { onEvent(ThreadEvent.TogglePin) },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 18.dp),
        ) {
            items(state.turns.size, key = { state.turns[it].id }) { index ->
                val turn = state.turns[index]
                Box(Modifier.padding(top = 16.dp)) {
                    when (turn.kind) {
                        TurnKind.User -> TurnGutterRow(timestampOf(turn)) {
                            UserTurn(
                                markdown = turn.markdown.orEmpty(),
                                viaButton = turn.viaButton,
                                failed = turn.sendState == SendState.Failed,
                                onRetry = { onEvent(ThreadEvent.Retry(turn.id)) },
                                onDiscard = { onEvent(ThreadEvent.Discard(turn.id)) },
                            )
                        }

                        TurnKind.Trace -> turn.trace?.let { trace ->
                            TurnGutterRow(timestampOf(turn)) {
                                TraceTurn(
                                    trace = trace,
                                    rollup = trace.rollupText(),
                                    expanded = state.expandedTraces.contains(turn.id),
                                    showArgs = state.showToolArgs,
                                    onToggle = { onEvent(ThreadEvent.ToggleTrace(turn.id)) },
                                )
                            }
                        }

                        TurnKind.Agent -> TurnGutterRow(timestampOf(turn)) {
                            val blocks = remember(turn.id, turn.markdown) {
                                segmentBlocks(turn.markdown.orEmpty())
                            }
                            AgentTurn(
                                agentName = state.agentProfile.name,
                                blocks = blocks,
                                onAction = { onEvent(ThreadEvent.ActionTapped(it)) },
                                onLinkClick = onLinkClick,
                            )
                        }
                    }
                }
            }

            state.streaming?.let { run ->
                run.trace?.let { liveTrace ->
                    item(key = "live-trace") {
                        Box(Modifier.padding(top = 16.dp)) {
                            TurnGutterRow(null, running = true) {
                                TraceTurn(
                                    trace = liveTrace,
                                    rollup = liveTrace.rollupText(),
                                    expanded = state.expandedTraces.contains("live-trace"),
                                    showArgs = state.showToolArgs,
                                    onToggle = { onEvent(ThreadEvent.ToggleTrace("live-trace")) },
                                    live = true,
                                )
                            }
                        }
                    }
                }
                item(key = "live-agent") {
                    Box(Modifier.padding(top = 16.dp)) {
                        TurnGutterRow(null, running = true) {
                            val blocks = remember(run.text) { segmentBlocks(run.text) }
                            AgentTurn(
                                agentName = state.agentProfile.name,
                                blocks = blocks,
                                streaming = true,
                                onLinkClick = onLinkClick,
                            )
                        }
                    }
                }
                if (run.interrupted) {
                    item(key = "interrupted") {
                        Text(
                            "▪ stream interrupted · reconnecting…",
                            style = LemanType.meta,
                            color = LemanColors.textFaint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 46.dp, top = 8.dp),
                        )
                    }
                }
            }
        }
        Composer(
            agentName = state.agentProfile.name,
            value = composerText,
            onValueChange = { composerText = it },
            onSend = {
                if (composerText.isNotBlank()) {
                    onEvent(ThreadEvent.Send(composerText))
                    composerText = ""
                }
            },
            enabled = state.composerEnabled,
        )
    }
}
