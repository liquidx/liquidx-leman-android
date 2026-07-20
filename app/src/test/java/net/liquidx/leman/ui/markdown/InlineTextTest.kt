package net.liquidx.leman.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import net.liquidx.leman.ui.theme.LemanColors
import org.commonmark.node.Document
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineTextTest {

    private fun inline(markdown: String, onLinkClick: (String) -> Unit = {}): AnnotatedString {
        val document = LemanMarkdownParser.parse(markdown) as Document
        val paragraph = document.firstChild as Paragraph
        return buildInlineText(paragraph, LemanMarkdown.agentTurn, onLinkClick)
    }

    private fun AnnotatedString.links(): List<AnnotatedString.Range<LinkAnnotation>> =
        getLinkAnnotations(0, length)

    @Test
    fun bareUrl_becomesTappableLink() {
        var clicked: String? = null
        val text = inline("see https://example.com/ci for details") { clicked = it }

        val link = text.links().single()
        assertEquals("https://example.com/ci", text.substring(link.start, link.end))

        val clickable = link.item as LinkAnnotation.Clickable
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals("https://example.com/ci", clicked)
    }

    @Test
    fun bareUrl_highlightedWithAccent() {
        val text = inline("open https://example.com now")
        val link = text.links().single()
        val clickable = link.item as LinkAnnotation.Clickable
        assertEquals(LemanColors.accent, clickable.styles?.style?.color)
    }

    @Test
    fun markdownLink_stillTappable() {
        var clicked: String? = null
        val text = inline("see [the pipeline](https://example.com/ci)") { clicked = it }

        val link = text.links().single()
        assertEquals("the pipeline", text.substring(link.start, link.end))

        val clickable = link.item as LinkAnnotation.Clickable
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals("https://example.com/ci", clicked)
    }

    @Test
    fun plainProse_hasNoLinks() {
        val text = inline("nothing to tap here, just words")
        assertTrue(text.links().isEmpty())
    }
}
