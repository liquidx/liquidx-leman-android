package net.liquidx.leman.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SseReaderTest {

    @Test
    fun readSseFrames_pairsEventWithData() {
        val src = okio.Buffer().writeUtf8(
            "event: run.started\ndata: {\"run_id\":\"r1\"}\n\n" +
            "event: assistant.delta\ndata: {\"delta\":\"hi\"}\n\n",
        )
        val frames = readSseFrames(src).toList()
        assertEquals(listOf("run.started", "assistant.delta"), frames.map { it.event })
        assertEquals("{\"run_id\":\"r1\"}", frames[0].data)
    }

    @Test
    fun readSseFrames_dataOnlyFrame_hasNullEvent_andBlankLineResetsEvent() {
        val src = okio.Buffer().writeUtf8(
            "event: done\ndata: {}\n\n" + "data: {\"orphan\":true}\n\n" + ": comment\n",
        )
        val frames = readSseFrames(src).toList()
        assertEquals(listOf("done", null), frames.map { it.event })
    }
}
