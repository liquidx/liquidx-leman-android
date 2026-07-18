package net.liquidx.leman.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.ui.components.CodeBlock
import net.liquidx.leman.ui.components.LemanButton
import net.liquidx.leman.ui.components.LemanButtonKind
import net.liquidx.leman.ui.components.LemanToggle
import net.liquidx.leman.ui.components.PromptField
import net.liquidx.leman.ui.components.ScreenFrame
import net.liquidx.leman.ui.components.SectionHeader
import net.liquidx.leman.ui.components.StatusRow
import net.liquidx.leman.ui.components.TitleRow
import net.liquidx.leman.ui.markdown.MarkdownBody
import net.liquidx.leman.ui.nav.rememberWallClock
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType

/** The DEBUG panel (spec 08) — a config sub-page, debug builds only. */
@Composable
fun DebugScreen(hooks: DebugHooksImpl, onBack: () -> Unit) {
    val container = hooks.container
    val scope = rememberCoroutineScope()
    val connState by container.connectionManager.state.collectAsState()
    val netLog by hooks.bus.netLog.collectAsState()
    val events by hooks.bus.events.collectAsState()
    val chaosFlags by hooks.chaos.flags.collectAsState()
    val useFake = hooks.switchable?.useFake?.collectAsState()
    val scenario = hooks.switchable?.fake?.scenario?.collectAsState()

    var eventsPaused by remember { mutableStateOf(false) }
    val eventFilterState = rememberTextFieldState()
    var expandedNetId by remember { mutableStateOf<Long?>(null) }
    val playgroundState = rememberTextFieldState()

    val eventFilter = eventFilterState.text.toString()
    val frozenEvents = remember(eventsPaused) { if (eventsPaused) events else null }
    val shownEvents = (frozenEvents ?: events)
        .filter { eventFilter.isBlank() || it.name.contains(eventFilter, true) || it.payload.contains(eventFilter, true) }
        .takeLast(40)

    ScreenFrame(
        statusRow = { StatusRow(rememberWallClock(), connState) },
        titleRow = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .clickable(onClick = onBack)
                        .semantics { contentDescription = "back" },
                ) {
                    Text("‹", style = LemanType.value, color = LemanColors.textSecondary)
                }
                Box(Modifier.weight(1f)) {
                    TitleRow("debug", AnnotatedString("not in release"))
                }
            }
        },
    ) {
        LazyColumn {
            // ---- GATEWAY -------------------------------------------------
            item {
                SectionHeader("gateway", right = if (useFake?.value == true) "mock" else "real")
                DebugRow("use mock server") {
                    LemanToggle(useFake?.value == true, { hooks.switchable?.useFake?.value = it })
                }
                if (useFake?.value == true) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    ) {
                        FakeScenario.entries.forEach { s ->
                            LemanButton(
                                s.name.lowercase(),
                                { hooks.switchable?.fake?.scenario?.value = s },
                                kind = if (scenario?.value == s) LemanButtonKind.Primary else LemanButtonKind.Dismiss,
                            )
                        }
                    }
                }
                DebugCaption("conn: $connState")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    LemanButton("force reconnect", { container.connectionManager.reconfigure() })
                    LemanButton(
                        "expire auth",
                        { container.connectionManager.onAuthFailure(401) },
                        kind = LemanButtonKind.Danger,
                    )
                }
            }

            // ---- CHAOS ---------------------------------------------------
            item {
                SectionHeader("chaos")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    listOf(0L, 500L, 2000L).forEach { ms ->
                        LemanButton(
                            "${ms}ms",
                            { hooks.chaos.update { it.copy(extraLatencyMs = ms) } },
                            kind = if (chaosFlags.extraLatencyMs == ms) LemanButtonKind.Primary else LemanButtonKind.Dismiss,
                        )
                    }
                }
                DebugRow("fail next 3 rest calls") {
                    LemanButton("arm", { hooks.chaos.update { it.copy(failNextRestCalls = 3) } })
                }
                DebugRow("corrupt next payload (${if (chaosFlags.corruptNextPayload) "armed" else "off"})") {
                    LemanToggle(chaosFlags.corruptNextPayload, { v -> hooks.chaos.update { it.copy(corruptNextPayload = v) } })
                }
                DebugRow("drop run stream after 5 events") {
                    LemanToggle(chaosFlags.dropStream, { v -> hooks.chaos.update { it.copy(dropStream = v) } })
                }
            }

            // ---- STATE ---------------------------------------------------
            item {
                SectionHeader("state")
                val counts by produceState("…", netLog.size) {
                    value = "threads ${container.db.threadDao().threadCount()} · " +
                        "turns ${container.db.turnDao().turnCount()} · " +
                        "unsynced ${container.db.turnDao().unsyncedCount()}"
                }
                DebugCaption(counts)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    LemanButton("reseed demo data", {
                        scope.launch { SampleCorpus.seed(container.db) }
                    })
                    LemanButton("wipe cache", {
                        scope.launch { container.threadRepository.clearAll() }
                    }, kind = LemanButtonKind.Danger)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    LemanButton("reset prefs", { scope.launch { container.settings.reset() } })
                }
            }

            // ---- NETWORK LOG ----------------------------------------------
            item { SectionHeader("network log", count = netLog.size) }
            items(netLog.takeLast(30).reversed().size) { index ->
                val entry = netLog.takeLast(30).reversed()[index]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expandedNetId = if (expandedNetId == entry.id) null else entry.id }
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Text(
                        "${entry.method} ${entry.path} → ${entry.status} · ${entry.durationMs}ms",
                        style = LemanType.meta,
                        color = if (entry.status in 200..299) LemanColors.textSecondary else LemanColors.danger,
                    )
                    if (expandedNetId == entry.id) {
                        CodeBlock(
                            AgentBlock.Code(
                                filename = "curl",
                                language = null,
                                text = entry.curl + "\n\n" + (entry.responseBody?.take(4000) ?: ""),
                                isDiff = false,
                            ),
                        )
                    }
                }
            }

            // ---- EVENT CONSOLE --------------------------------------------
            item {
                SectionHeader("event console", count = events.size, right = if (eventsPaused) "paused" else "live")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        PromptField(eventFilterState, placeholder = "filter events")
                    }
                    LemanButton(if (eventsPaused) "resume" else "pause", { eventsPaused = !eventsPaused })
                }
            }
            items(shownEvents.reversed().size) { index ->
                val entry = shownEvents.reversed()[index]
                Row(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
                    Text(entry.name, style = LemanType.micro, color = LemanColors.accentMuted, modifier = Modifier.padding(end = 8.dp))
                    Text(entry.payload, style = LemanType.micro, color = LemanColors.textTertiary, maxLines = 1)
                }
            }

            // ---- RENDER ----------------------------------------------------
            item {
                SectionHeader("render")
                DebugCaption("markdown playground — renders through MarkdownBody live")
                Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    PromptField(playgroundState, placeholder = "type markdown…")
                }
                if (playgroundState.text.isNotBlank()) {
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                        MarkdownBody(playgroundState.text.toString())
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DebugRow(label: String, control: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = LemanType.meta.copy(fontSize = 11.sp),
            color = LemanColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        control()
    }
}

@Composable
private fun DebugCaption(text: String) {
    Text(
        text,
        style = LemanType.meta,
        color = LemanColors.textFaint,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
    )
}
