package net.liquidx.leman.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.ui.components.CodeBlock
import net.liquidx.leman.ui.theme.HairlineSide
import net.liquidx.leman.ui.theme.LemanColors
import net.liquidx.leman.ui.theme.LemanType
import net.liquidx.leman.ui.theme.hairline
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak

/**
 * CommonMark → Compose for Prose blocks (spec 05 node table). Re-parses the
 * accumulated string per frame-batch while streaming — CommonMark parses a few
 * KB in <1ms; don't optimize until measured.
 */
@Composable
fun MarkdownBody(
    markdown: String,
    style: MarkdownStyle = LemanMarkdown.agentTurn,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {},
) {
    val document = remember(markdown) { LemanMarkdownParser.parse(markdown) as Document }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var node: Node? = document.firstChild
        while (node != null) {
            RenderBlockNode(node, style, onLinkClick)
            node = node.next
        }
    }
}

@Composable
private fun RenderBlockNode(node: Node, style: MarkdownStyle, onLinkClick: (String) -> Unit) {
    when (node) {
        is Paragraph -> Text(
            remember(node) { buildInlineText(node, style, onLinkClick) },
            style = LemanType.body,
            color = style.paragraphColor,
        )

        // No heading scale inside turns: render as strong paragraph (spec 05).
        is Heading -> Text(
            remember(node) { buildInlineText(node, style, onLinkClick) },
            style = LemanType.body.copy(fontWeight = FontWeight.W600),
            color = LemanColors.textPrimary,
            modifier = Modifier.padding(top = if (node.level <= 3) 4.dp else 0.dp),
        )

        is BulletList -> ListColumn(node, style, onLinkClick, ordered = false)
        is OrderedList -> ListColumn(node, style, onLinkClick, ordered = true)

        is BlockQuote -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .hairline(HairlineSide.Start, Color(0x1FFFFFFF))
                .padding(start = 12.dp),
        ) {
            var child: Node? = node.firstChild
            while (child != null) {
                RenderBlockNode(child, style, onLinkClick)
                child = child.next
            }
        }

        is FencedCodeBlock -> CodeBlock(
            AgentBlock.Code(
                filename = null,
                language = node.info?.substringBefore(' ')?.ifBlank { null },
                text = node.literal.orEmpty(),
                isDiff = node.info?.startsWith("diff") == true || looksLikeDiff(node.literal.orEmpty()),
            ),
        )

        is IndentedCodeBlock -> CodeBlock(
            AgentBlock.Code(filename = null, language = null, text = node.literal.orEmpty(), isDiff = false),
        )

        is ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .hairline(HairlineSide.Top, LemanColors.hairline),
        )

        is HtmlBlock -> Text(
            node.literal.orEmpty().trim(),
            style = LemanType.meta,
            color = LemanColors.textFaint,
        )

        else -> {
            // Tables land here when nested (top-level ones become OptionTable
            // blocks); render their text content plainly rather than crash.
            val text = node.collectText().trim()
            if (text.isNotEmpty()) {
                Text(text, style = LemanType.body, color = style.paragraphColor)
            }
        }
    }
}

@Composable
private fun ListColumn(
    list: Node,
    style: MarkdownStyle,
    onLinkClick: (String) -> Unit,
    ordered: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        var item: Node? = list.firstChild
        var index = 1
        while (item != null) {
            if (item is ListItem) {
                Row {
                    if (ordered) {
                        Text(
                            "%02d".format(index),
                            style = LemanType.body,
                            color = LemanColors.textFaint,
                            modifier = Modifier.padding(end = 9.dp),
                        )
                    } else {
                        Text(
                            "▪",
                            style = LemanType.body,
                            color = LemanColors.textFaint,
                            modifier = Modifier.width(18.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        var child: Node? = item.firstChild
                        while (child != null) {
                            RenderBlockNode(child, style, onLinkClick)
                            child = child.next
                        }
                    }
                }
                index++
            }
            item = item.next
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080A, widthDp = 380)
@Composable
private fun MarkdownBodyPreview() {
    MarkdownBody(
        markdown = """
            found it. the flake is in **test_retry_backoff** — it asserts on a `0.5s` timer.

            - deterministic clock injected
            - retries pinned to 2

            1. reproduce locally
            2. bisect the helper

            > verified across 40 consecutive green runs

            see [the pipeline](https://example.com/ci) for details.
        """.trimIndent(),
        modifier = Modifier.padding(12.dp),
    )
}
