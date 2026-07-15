package net.liquidx.leman.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtoTest {

    @Test
    fun healthDto_decodesFixture_ignoringUnknownFields() {
        val dto = HermesJson.decodeFromString(HealthDto.serializer(), Fixtures.load("wire/health.json"))
        assertEquals("ok", dto.status)
        assertEquals("hermes-agent", dto.platform)
        assertEquals("0.18.0", dto.version)
    }

    @Test
    fun runAcceptedDto_decodesFixture() {
        val dto = HermesJson.decodeFromString(RunAcceptedDto.serializer(), Fixtures.load("wire/run-accepted.json"))
        assertEquals("run_7f3a", dto.runId)
        assertEquals("started", dto.status)
    }

    @Test
    fun runDto_decodesRunningRun_withoutOutput() {
        val dto = HermesJson.decodeFromString(RunDto.serializer(), Fixtures.load("wire/run-running.json"))
        assertEquals("run_7f3a", dto.runId)
        assertEquals(RunStatus.Running, dto.runStatus)
        assertEquals("sess_91", dto.sessionId)
        assertNull(dto.output)
        assertNull(dto.usage)
    }

    @Test
    fun runDto_decodesCompletedRun_withOutputAndUsage() {
        val dto = HermesJson.decodeFromString(RunDto.serializer(), Fixtures.load("wire/run-completed.json"))
        assertEquals(RunStatus.Completed, dto.runStatus)
        assertEquals("the pipeline is fixed.", dto.output)
        assertEquals(1156, dto.usage?.totalTokens)
    }

    @Test
    fun runDto_unknownStatusString_mapsToUnknownNotCrash() {
        val dto = HermesJson.decodeFromString(RunDto.serializer(), Fixtures.load("wire/run-unknown-status.json"))
        assertEquals(RunStatus.Unknown, dto.runStatus)
    }
}
