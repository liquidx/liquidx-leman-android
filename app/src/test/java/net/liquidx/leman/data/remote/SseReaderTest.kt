package net.liquidx.leman.data.remote

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class SseReaderTest {

    private fun framesOf(text: String): List<String> {
        val buffer = Buffer().writeUtf8(text)
        return readSseDataFrames(buffer).toList()
    }

    @Test
    fun read_dataOnlyFraming_yieldsEachPayload() {
        val frames = framesOf("data: {\"a\":1}\n\ndata: {\"b\":2}\n\n")
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), frames)
    }

    @Test
    fun read_commentLines_areSkipped() {
        val frames = framesOf(": hello\ndata: x\n\n: stream closed\n")
        assertEquals(listOf("x"), frames)
    }

    @Test
    fun read_blankLinesAndEof_terminateCleanly() {
        val frames = framesOf("\n\ndata: only\n")
        assertEquals(listOf("only"), frames)
    }

    @Test
    fun read_noSpaceAfterColon_stillParses() {
        val frames = framesOf("data:tight\n\n")
        assertEquals(listOf("tight"), frames)
    }

    @Test
    fun read_crlfLineEndings_areTolerated() {
        val frames = framesOf("data: x\r\n\r\ndata: y\r\n")
        assertEquals(listOf("x", "y"), frames)
    }

    @Test
    fun read_fullFixtureStream_yieldsTenFrames() {
        val frames = framesOf(Fixtures.load("wire/events-stream.txt"))
        assertEquals(10, frames.size)
        assertEquals("not-even-json", frames[8])
    }
}
