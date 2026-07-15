package net.liquidx.leman.ui.markdown

import net.liquidx.leman.data.remote.Fixtures
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.domain.model.TaskItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockSegmenterTest {

    @Test
    fun segment_plainProse_isSingleProseBlock() {
        val blocks = segmentBlocks("just a paragraph.\n\nand another.")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is AgentBlock.Prose)
    }

    @Test
    fun segment_fencedCode_promotedWithLanguageAndFilename() {
        val blocks = segmentBlocks(
            """
            before text

            ```kotlin ci/pipeline.kt
            fun x() = 1
            ```

            after text
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        val code = blocks[1] as AgentBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("ci/pipeline.kt", code.filename)
        assertEquals("fun x() = 1\n", code.text)
        assertEquals(false, code.isDiff)
        assertTrue((blocks[0] as AgentBlock.Prose).markdown.contains("before text"))
        assertTrue((blocks[2] as AgentBlock.Prose).markdown.contains("after text"))
    }

    @Test
    fun segment_diffFence_flaggedAsDiff() {
        val blocks = segmentBlocks("```diff\n-old\n+new\n```")
        val code = blocks.single() as AgentBlock.Code
        assertTrue(code.isDiff)
        assertNull(code.filename)
    }

    @Test
    fun segment_codeFenceWithoutInfo_hasNullLanguage() {
        val code = segmentBlocks("```\nplain\n```").single() as AgentBlock.Code
        assertNull(code.language)
        assertNull(code.filename)
    }

    @Test
    fun segment_unterminatedFence_rendersAsCodeSoFar() {
        val blocks = segmentBlocks("streaming…\n\n```python\ndef half_done(")
        assertEquals(2, blocks.size)
        val code = blocks[1] as AgentBlock.Code
        assertEquals("python", code.language)
        assertTrue(code.text.contains("def half_done("))
    }

    @Test
    fun segment_checkboxList_becomesTaskListWithStatesAndCounter() {
        val blocks = segmentBlocks(
            """
            - [x] reproduce the flake
            - [~] patch backoff
            - [ ] re-run suite
            """.trimIndent(),
        )
        val tasks = blocks.single() as AgentBlock.TaskList
        assertEquals(
            listOf(TaskItemState.Done, TaskItemState.Active, TaskItemState.Pending),
            tasks.items.map { it.state },
        )
        assertEquals("reproduce the flake", tasks.items[0].label)
        assertEquals("1/3", tasks.counter)
    }

    @Test
    fun segment_plainBulletList_staysProse() {
        val blocks = segmentBlocks("- one\n- two")
        assertTrue(blocks.single() is AgentBlock.Prose)
    }

    @Test
    fun segment_gfmTable_becomesOptionTable() {
        val blocks = segmentBlocks(
            """
            | option | price | notes |
            |--------|-------|-------|
            | tgv 6:12 | 74 chf | direct |
            | tgv 8:47 | 49 chf | 1 change |
            """.trimIndent(),
        )
        val table = blocks.single() as AgentBlock.OptionTable
        assertEquals(2, table.rows.size)
        assertEquals("tgv 6:12", table.rows[0].title)
        assertEquals("74 chf", table.rows[0].value)
        assertEquals("direct", table.rows[0].detail)
    }

    @Test
    fun segment_detailsElement_becomesCollapsible() {
        val blocks = segmentBlocks(
            "<details><summary>fare rules · 3 conditions</summary>\nnon-refundable after 24h.\n</details>",
        )
        val collapsible = blocks.single() as AgentBlock.Collapsible
        assertEquals("fare rules · 3 conditions", collapsible.summary)
        assertTrue(collapsible.body.contains("non-refundable"))
    }

    @Test
    fun segment_tortureFixture_parsesWithoutCrash_inOrder() {
        val blocks = segmentBlocks(Fixtures.load("markdown/torture.md"))
        assertTrue(blocks.count { it is AgentBlock.Code } >= 3)
        assertTrue(blocks.any { it is AgentBlock.TaskList })
        assertTrue(blocks.any { it is AgentBlock.OptionTable })
        assertTrue(blocks.any { it is AgentBlock.Collapsible })
        assertTrue(blocks.first() is AgentBlock.Prose)
    }

    @Test
    fun segment_emptyString_yieldsNoBlocks() {
        assertTrue(segmentBlocks("").isEmpty())
    }
}
