package net.liquidx.leman.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import net.liquidx.leman.ui.theme.LemanColors
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.ext.gfm.strikethrough.Strikethrough

/** Paragraph/emphasis colors differ between agent and user turns (spec 05). */
data class MarkdownStyle(
    val paragraphColor: Color,
    val emphasisColor: Color = LemanColors.textPrimary,
)

object LemanMarkdown {
    val agentTurn = MarkdownStyle(paragraphColor = Color(0xFFC9C9D0))
    val userTurn = MarkdownStyle(paragraphColor = LemanColors.textPrimary)
}

private val inlineCodeStyle = SpanStyle(
    color = LemanColors.textPrimary,
    background = Color(0x0FFFFFFF), // rgba(255,255,255,.06)
)

/**
 * One AnnotatedString per paragraph (spec 05). Agent turns emphasize with
 * color, not italics; links are accent with no underline.
 */
fun buildInlineText(
    parent: Node,
    style: MarkdownStyle,
    onLinkClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    fun walk(node: Node?) {
        var child = node
        while (child != null) {
            when (child) {
                is Text -> append(child.literal)
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is Code -> withStyle(inlineCodeStyle) { append(child.literal) }
                is Emphasis -> withStyle(SpanStyle(color = style.emphasisColor)) { walk(child.firstChild) }
                is StrongEmphasis -> withStyle(
                    SpanStyle(color = style.emphasisColor, fontWeight = FontWeight.W600),
                ) { walk(child.firstChild) }
                is Strikethrough -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                ) { walk(child.firstChild) }
                is Link -> {
                    val destination = child.destination.orEmpty()
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = destination,
                            styles = TextLinkStyles(style = SpanStyle(color = LemanColors.accent)),
                            linkInteractionListener = { onLinkClick(destination) },
                        ),
                    ) { walk(child.firstChild) }
                }
                else -> walk(child.firstChild)
            }
            child = child.next
        }
    }
    walk(parent.firstChild)
}
