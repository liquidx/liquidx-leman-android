package net.liquidx.leman.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun sessionList_decodes() {
        val json = """{"object":"list","data":[{"id":"run_abc","source":"cron","model":"m",
            "title":"Daily Digest","preview":"[IMPORTANT…","started_at":1784153421.0,
            "last_active":1784154105.2,"message_count":12,"extra_field":1}],
            "limit":3,"offset":0,"has_more":true}"""
        val dto = HermesJson.decodeFromString(SessionListDto.serializer(), json)
        assertEquals("run_abc", dto.data.single().id)
        assertEquals("cron", dto.data.single().source)
        assertEquals(12, dto.data.single().messageCount)
        assertTrue(dto.hasMore)
    }

    @Test
    fun sessionMessages_decodes_toolCallsAndReasoning() {
        val json = """{"object":"list","session_id":"s1","data":[
            {"id":1761,"session_id":"s1","role":"user","content":"hi","timestamp":1784209432.5},
            {"id":1762,"session_id":"s1","role":"assistant","content":"",
             "tool_calls":[{"id":"c1","type":"function","function":{"name":"memory","arguments":"{\"a\":1}"}}],
             "reasoning":"planning","timestamp":1784209435.8,"finish_reason":"tool_calls"},
            {"id":1763,"session_id":"s1","role":"tool","content":"ok","tool_name":"memory","tool_call_id":"c1","timestamp":1784209436.0}]}"""
        val dto = HermesJson.decodeFromString(SessionMessagesDto.serializer(), json)
        assertEquals(3, dto.data.size)
        assertEquals("memory", dto.data[1].toolCalls!!.single().function!!.name)
        assertEquals("planning", dto.data[1].reasoning)
    }

    @Test
    fun sessionEnvelope_decodes_createResponse() {
        val json = """{"object":"hermes.session","session":{"id":"api_1_a","source":"api_server","started_at":1.0}}"""
        assertEquals("api_1_a", HermesJson.decodeFromString(SessionEnvelopeDto.serializer(), json).session.id)
    }

    @Test
    fun capabilities_supportsSessions_requiresBothFlags() {
        val yes = """{"features":{"session_resources":true,"session_chat_streaming":true,"cors":false}}"""
        val no = """{"features":{"session_resources":true,"session_chat_streaming":false}}"""
        assertTrue(HermesJson.decodeFromString(CapabilitiesDto.serializer(), yes).supportsSessions)
        assertFalse(HermesJson.decodeFromString(CapabilitiesDto.serializer(), no).supportsSessions)
    }
}
