package net.liquidx.leman.data.local

import net.liquidx.leman.domain.model.SendState
import net.liquidx.leman.domain.model.ThreadState
import net.liquidx.leman.domain.model.Trace
import net.liquidx.leman.domain.model.TraceStep
import net.liquidx.leman.domain.model.TraceStepKind
import net.liquidx.leman.domain.model.TurnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMappingTest {

    @Test
    fun traceJson_roundTrips() {
        val trace = Trace(
            steps = listOf(
                TraceStep(TraceStepKind.Reasoning, summary = "thinking about ci"),
                TraceStep(TraceStepKind.Tool, tool = "ci.logs", summary = "fetch last 20 runs", durationSeconds = 5.939),
                TraceStep(TraceStepKind.Tool, tool = "repo.search", summary = null, durationSeconds = 3.2, error = true),
            ),
        )
        assertEquals(trace, decodeTrace(encodeTrace(trace)))
    }

    @Test
    fun decodeTrace_malformedJson_returnsNullNotCrash() {
        assertNull(decodeTrace("{broken"))
    }

    @Test
    fun threadEntity_unknownStateString_mapsToIdle() {
        val entity = ThreadEntity(
            id = "a", title = "t", preview = "p", state = "someday_new_state",
            pinned = false, unread = false, createdAt = 1, lastActiveAt = 2,
            source = "cron", agentName = null, agentGlyph = null,
        )
        assertEquals(ThreadState.Idle, entity.toDomain().state)
    }

    @Test
    fun threadEntity_source_roundTripsThroughDomain() {
        val entity = ThreadEntity(
            id = "a", title = "t", preview = "p", state = "idle",
            pinned = false, unread = false, createdAt = 1, lastActiveAt = 2,
            source = "cron", agentName = null, agentGlyph = null,
        )
        val domain = entity.toDomain()
        assertEquals("cron", domain.source)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun turnEntity_roundTripsThroughDomain() {
        val entity = TurnEntity(
            id = "t1", threadId = "a", seq = 4, kind = "agent", createdAt = 9,
            markdown = "hello", blocksJson = null,
            traceJson = null, runId = "run_1", sendState = "synced", viaButton = false,
        )
        val domain = entity.toDomain()
        assertEquals(TurnKind.Agent, domain.kind)
        assertEquals(SendState.Synced, domain.sendState)
        assertEquals(entity, domain.toEntity())
    }
}
