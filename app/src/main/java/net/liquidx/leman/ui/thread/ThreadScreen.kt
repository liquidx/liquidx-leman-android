package net.liquidx.leman.ui.thread

import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.Turn
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.domain.rollupText
import net.liquidx.leman.ui.components.AgentTurn
import net.liquidx.leman.ui.components.Composer
import net.liquidx.leman.ui.components.JumpToLatestButton
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.SystemTurn
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
    val composerState = rememberTextFieldState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Follow the bottom while streaming only if already there (spec 05); also
    // drives the jump-to-latest affordance's visibility (ux-fixes spec).
    // Pixel-aware, not index-based: a very tall last item (e.g. a cron digest
    // body) counts as "visible" by index even when only its first lines are on
    // screen — at bottom means its bottom edge is inside the viewport too.
    val bottomSlackPx = with(LocalDensity.current) { 24.dp.toPx() }
    val atBottom by remember(bottomSlackPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (
                last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + bottomSlackPx
                )
        }
    }
    // First open lands on the first unread turn (if any); every subsequent
    // change follows the existing "stick to bottom if already there" rule.
    // Fires once per thread open — never refights the user's own scrolling
    // (ux-fixes spec). Saveable so a config change (rotation) doesn't re-run
    // the anchor scroll and clobber the restored list position.
    var didInitialScroll by rememberSaveable(state.thread?.id) { mutableStateOf(false) }
    LaunchedEffect(state.thread?.id, state.turns.size, state.streaming?.text?.length) {
        if (!didInitialScroll) {
            if (!state.loaded || state.turns.isEmpty()) return@LaunchedEffect
            // layoutInfo lags composition by a frame: when the loaded state lands
            // in one emission, totalItemsCount still reads 0 here. Suspend until
            // the list has actually laid its items out, then anchor.
            val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            listState.scrollToItem(state.initialScrollIndex?.coerceIn(0, total - 1) ?: (total - 1))
            didInitialScroll = true
            return@LaunchedEffect
        }
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
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
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

                            TurnKind.System -> TurnGutterRow(timestampOf(turn)) {
                                SystemTurn(
                                    markdown = turn.markdown.orEmpty(),
                                    expanded = state.expandedTraces.contains(turn.id),
                                    onToggle = { onEvent(ThreadEvent.ToggleTrace(turn.id)) },
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
            // Gated on didInitialScroll so the button never flashes on the frame
            // before the initial anchor/bottom scroll lands (atBottom reads
            // pre-scroll layoutInfo on first composition, which can be stale).
            if (didInitialScroll && !atBottom) {
                JumpToLatestButton(
                    onClick = {
                        val total = listState.layoutInfo.totalItemsCount
                        if (total > 0) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(total - 1)
                                // animateScrollToItem aligns the item's TOP with the
                                // viewport — a tall last item still overflows below.
                                // Finish the job: scroll the remaining overshoot so
                                // the true bottom lands in view.
                                val info = listState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull() ?: return@launch
                                val overshoot = last.offset + last.size - info.viewportEndOffset
                                if (overshoot > 0) listState.animateScrollBy(overshoot.toFloat())
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 12.dp),
                )
            }
        }
        Composer(
            agentName = state.agentProfile.name,
            state = composerState,
            onSend = {
                val text = composerState.text.toString()
                if (text.isNotBlank()) {
                    onEvent(ThreadEvent.Send(text))
                    composerState.clearText()
                }
            },
            enabled = state.composerEnabled,
        )
    }
}
