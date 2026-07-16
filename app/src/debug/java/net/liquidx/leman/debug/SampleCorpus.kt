package net.liquidx.leman.debug

import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import net.liquidx.leman.data.local.encodeTrace
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind

/**
 * The handoff's sample content (design 2a/2b), one corpus consumed by unit
 * tests, screenshots, the fake gateway seed, and demo mode (spec 07) — so
 * design-sample drift is impossible.
 */
object SampleCorpus {

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** The "fix flaky ci pipeline" 9-step trace (design 2b). */
    fun ciTrace(): Trace = Trace(
        steps = listOf(
            TraceStep(TraceStepKind.Reasoning, summary = "user reports intermittent failures on main · start from recent runs"),
            TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "fetch last 20 runs of pipeline main → 7 red, all in test stage", durationSeconds = 24.0),
            TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "diff failing vs passing run logs → test_retry_backoff only delta", durationSeconds = 31.0),
            TraceStep(TraceStepKind.Tool, tool = "repo.search", summary = "grep test_retry_backoff → tests/util/test_retry.py", durationSeconds = 12.0),
            TraceStep(TraceStepKind.Reasoning, summary = "test asserts on wall-clock 0.5s timer · classic timing flake"),
            TraceStep(TraceStepKind.Tool, tool = "repo.search", summary = "find clock injection points in retry helper → none, uses time.sleep", durationSeconds = 18.0),
            TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "check runner load at failure times → shared runner, high load", durationSeconds = 27.0),
            TraceStep(TraceStepKind.Reasoning, summary = "fix: inject fake clock + pin retries · verify over 40 runs"),
            TraceStep(TraceStepKind.Tool, tool = "monitor.add", summary = "watch pipeline main for 48h → alert on flake recurrence", durationSeconds = 8.0),
        ),
    )

    fun ciFollowUpTrace(): Trace = Trace(
        steps = listOf(
            TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "re-run full suite ×40 → 40 green", durationSeconds = 96.0),
            TraceStep(TraceStepKind.Tool, tool = "monitor.add", summary = "create monitor #m-31 · flake recurrence on main", durationSeconds = 6.0),
        ),
    )

    val ciDiagnosisMarkdown = """
        found it. the flake is in **test_retry_backoff** — it asserts on a real `0.5s` wall-clock timer, and on a loaded shared runner the sleep overshoots.

        ```diff tests/util/test_retry.py
        @@ -14,9 +14,10 @@
         def test_retry_backoff():
        -    start = time.time()
        -    retry(op, backoff=0.5)
        -    assert time.time() - start < 0.6
        +    clock = FakeClock()
        +    retry(op, backoff=0.5, clock=clock)
        +    assert clock.elapsed == 0.5
        ```

        - [x] reproduce the flake locally under load
        - [x] bisect to the retry helper
        - [x] inject deterministic clock
        - [x] re-run full suite ×40 · all green
    """.trimIndent()

    val ciConfirmationMarkdown = """
        re-ran the full suite forty more times overnight — zero flakes. the fix is merged and
        a 48h watch is in place.

        monitor #m-31 active
    """.trimIndent()

    val genevaOptionsMarkdown = """
        found 3 workable trains under 80 chf. the 6:12 is direct; the others need one change at lausanne.

        | option | price | notes |
        |--------|-------|-------|
        | tgv 6:12 | 74 chf | direct · 3h 41m |
        | tgv 8:47 | 49 chf | 1 change · 4h 10m |
        | ic 11:02 | 61 chf | 1 change · 4h 02m |

        <details><summary>fare rules · 3 conditions</summary>
        non-refundable after 24h. seat reservation included. bikes need a separate pass.
        </details>

        which one should i book?
    """.trimIndent()

    /** Seeds the design-mock thread list; `now` fixed by callers for determinism. */
    fun threads(now: Long): List<ThreadEntity> = listOf(
        ThreadEntity(
            id = "th-geneva", title = "book train to geneva",
            preview = "found 3 options under 80 chf — need your pick",
            state = "idle", pinned = true, unread = true,
            createdAt = now - 5 * HOUR, lastActiveAt = now - 3 * HOUR,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-insurance", title = "renew car insurance",
            preview = "comparing quotes from 4 providers · 3/5",
            state = "running", pinned = true, unread = false,
            createdAt = now - 2 * HOUR, lastActiveAt = now - 30_000,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-digest", title = "morning digest",
            preview = "6 items · 2 need action · calendar clear until 11",
            state = "idle", pinned = false, unread = true,
            createdAt = now - 6 * HOUR, lastActiveAt = todayAt(now, 7),
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-ci", title = "fix flaky ci pipeline",
            preview = "monitor #m-31 active · 40 green runs",
            state = "idle", pinned = false, unread = false,
            createdAt = now - DAY - 4 * HOUR, lastActiveAt = now - DAY + 2 * HOUR,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-lyon", title = "plan lyon trip",
            preview = "waiting on your dates",
            state = "idle", pinned = false, unread = false,
            createdAt = now - DAY - 8 * HOUR, lastActiveAt = now - DAY + 1 * HOUR,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-board", title = "summarize q2 board notes",
            preview = "summary sent to your email",
            state = "idle", pinned = false, unread = false,
            createdAt = now - DAY - 10 * HOUR, lastActiveAt = now - DAY,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
        ThreadEntity(
            id = "th-hn", title = "monitor hn for llm articles",
            preview = "recurring · daily at 08:00 · 4 matches yesterday",
            state = "idle", pinned = false, unread = false,
            createdAt = now - 4 * DAY, lastActiveAt = now - 4 * DAY,
            source = "api_server", agentName = null, agentGlyph = null,
        ),
    )

    /** The full "fix flaky ci pipeline" conversation (design 2b). */
    fun ciTurns(now: Long): List<TurnEntity> {
        val base = now - DAY
        var seq = 0L
        fun turn(kind: String, markdown: String?, trace: Trace? = null, at: Long, viaButton: Boolean = false) =
            TurnEntity(
                id = "th-ci-turn-${++seq}", threadId = "th-ci", seq = seq, kind = kind,
                createdAt = at, markdown = markdown, blocksJson = null,
                traceJson = trace?.let(::encodeTrace), runId = if (kind != "user") "run-ci-1" else "run-ci-1",
                sendState = "synced", viaButton = viaButton,
            )
        return listOf(
            turn("user", "the ci pipeline keeps flaking on main — roughly every third run fails in the test stage. find it and fix it.", at = base),
            turn("trace", null, trace = ciTrace(), at = base + 60_000),
            turn("agent", ciDiagnosisMarkdown, at = base + 4 * 60_000),
            turn("user", "nice. keep an eye on it for a couple of days and confirm it stays green", at = base + 22 * 60_000),
            turn("trace", null, trace = ciFollowUpTrace(), at = base + 23 * 60_000),
            turn("agent", ciConfirmationMarkdown, at = base + 25 * 60_000),
        )
    }

    fun genevaTurns(now: Long): List<TurnEntity> {
        val base = now - 4 * HOUR
        return listOf(
            TurnEntity(
                id = "th-geneva-turn-1", threadId = "th-geneva", seq = 1, kind = "user",
                createdAt = base, markdown = "book me a train to geneva next friday morning, under 80 chf if possible",
                blocksJson = null, traceJson = null, runId = "run-geneva-1", sendState = "synced", viaButton = false,
            ),
            TurnEntity(
                id = "th-geneva-turn-2", threadId = "th-geneva", seq = 2, kind = "agent",
                createdAt = base + 3 * 60_000, markdown = genevaOptionsMarkdown,
                blocksJson = null, traceJson = null, runId = "run-geneva-1", sendState = "synced", viaButton = false,
            ),
        )
    }

    suspend fun seed(db: LemanDatabase, now: Long = System.currentTimeMillis()) {
        db.threadDao().clearAllThreads()
        threads(now).forEach { db.threadDao().upsertThread(it) }
        db.turnDao().upsertTurns(ciTurns(now))
        db.turnDao().upsertTurns(genevaTurns(now))
    }

    private fun todayAt(now: Long, hourOfDay: Int): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.Instant.ofEpochMilli(now).atZone(zone)
            .withHour(hourOfDay).withMinute(0).withSecond(0).withNano(0)
            .toInstant().toEpochMilli()
    }
}
