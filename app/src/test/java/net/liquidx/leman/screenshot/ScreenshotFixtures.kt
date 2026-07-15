package net.liquidx.leman.screenshot

import net.liquidx.leman.data.local.toDomain
import net.liquidx.leman.debug.SampleCorpus
import net.liquidx.leman.domain.model.AgentProfile
import net.liquidx.leman.domain.model.ConnState
import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.Thread
import net.liquidx.leman.domain.model.Turn
import net.liquidx.leman.domain.model.TurnKind
import net.liquidx.leman.testutil.VmHarness
import net.liquidx.leman.ui.thread.ThreadUiState
import net.liquidx.leman.ui.threads.StateTone
import net.liquidx.leman.ui.threads.ThreadListItem
import net.liquidx.leman.ui.threads.ThreadSection
import net.liquidx.leman.ui.threads.ThreadsUiState

/** Screen states derived from the shared sample corpus (spec 07 fixtures). */
object ScreenshotFixtures {

    val now: Long = VmHarness.FIXED_NOW

    private fun item(
        id: String,
        title: String,
        preview: String,
        stateLabel: String,
        tone: StateTone,
        timeLabel: String,
        unread: Boolean = false,
        pinned: Boolean = false,
        running: Boolean = false,
        failed: Boolean = false,
    ) = ThreadListItem(id, title, preview, unread, pinned, running, failed, stateLabel, tone, timeLabel)

    /** The design 2a sample list. */
    fun threadsState(connState: ConnState = ConnState.Online("0.18.0")): ThreadsUiState {
        val pinned = listOf(
            item(
                "th-geneva", "book train to geneva", "found 3 options under 80 chf — need your pick",
                "needs you", StateTone.Warn, "09:12", unread = true, pinned = true,
            ),
            item(
                "th-insurance", "renew car insurance", "comparing quotes from 4 providers",
                "running · 3/5", StateTone.Accent, "now", pinned = true, running = true,
            ),
        )
        val today = listOf(
            item("th-digest", "morning digest", "6 items · 2 need action · calendar clear until 11", "done", StateTone.Faint, "07:00", unread = true),
        )
        val yesterday = listOf(
            item("th-ci", "fix flaky ci pipeline", "monitor #m-31 active · 40 green runs", "done", StateTone.Faint, "21:44"),
            item("th-lyon", "plan lyon trip", "waiting on your dates", "idle", StateTone.Faint, "18:03"),
            item("th-board", "summarize q2 board notes", "summary sent to your email", "done", StateTone.Faint, "12:20"),
        )
        val earlier = listOf(
            item("th-hn", "monitor hn for llm articles", "recurring · daily at 08:00 · 4 matches yesterday", "done", StateTone.Faint, "jul 11"),
        )
        return ThreadsUiState(
            sections = listOf(
                ThreadSection("PINNED", 2, null, pinned),
                ThreadSection("TODAY", 1, "jul 15", today),
                ThreadSection("YESTERDAY", 3, "jul 14", yesterday),
                ThreadSection("EARLIER", 1, null, earlier),
            ),
            totalCount = 7,
            runningCount = 1,
            connState = connState,
            loaded = true,
        )
    }

    fun ciThread(): Thread = SampleCorpus.threads(now).first { it.id == "th-ci" }.toDomain()

    fun ciTurns(): List<Turn> = SampleCorpus.ciTurns(now).map { it.toDomain() }

    fun threadState(
        expanded: Set<String> = emptySet(),
        turns: List<Turn> = ciTurns(),
        streaming: net.liquidx.leman.data.repo.StreamingRun? = null,
    ) = ThreadUiState(
        thread = ciThread(),
        turns = turns,
        streaming = streaming,
        agentProfile = AgentProfile("juno", "✳"),
        expandedTraces = expanded,
        showToolArgs = true,
        connState = ConnState.Online("0.18.0"),
        loaded = true,
    )

    fun failedSendTurns(): List<Turn> = ciTurns() + Turn(
        id = "failed-turn",
        threadId = "th-ci",
        seq = 99,
        kind = TurnKind.User,
        createdAt = now,
        markdown = "also check the deploy pipeline while you're at it",
        trace = null,
        runId = null,
        sendState = SendState.Failed,
        viaButton = false,
    )
}
