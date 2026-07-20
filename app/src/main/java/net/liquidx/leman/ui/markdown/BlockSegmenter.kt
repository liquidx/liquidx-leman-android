package net.liquidx.leman.ui.markdown

import net.liquidx.leman.domain.model.AgentBlock
import net.liquidx.leman.domain.model.OptionRow
import net.liquidx.leman.domain.model.TaskItem
import net.liquidx.leman.domain.model.TaskItemState
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

private val extensions = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    AutolinkExtension.create(),
)

/** Shared parser: GFM tables + strikethrough + bare-URL autolink, source spans for prose slicing. */
val LemanMarkdownParser: Parser = Parser.builder()
    .extensions(extensions)
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()

private val checkboxPattern = Regex("""^\[([ xX~\-])]\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val detailsPattern = Regex(
    """<details>\s*<summary>(.*?)</summary>(.*?)(?:</details>|$)""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/**
 * The markdown post-processor (spec 05): segments the CommonMark AST into the
 * ordered [AgentBlock] list the renderer consumes. Only the producer changes
 * if a future gateway starts emitting typed blocks.
 */
fun segmentBlocks(markdown: String): List<AgentBlock> {
    if (markdown.isBlank()) return emptyList()
    val document = LemanMarkdownParser.parse(markdown) as Document
    val lines = markdown.lines()

    val blocks = mutableListOf<AgentBlock>()
    var proseStartLine = -1
    var proseEndLine = -1

    fun flushProse() {
        if (proseStartLine >= 0) {
            val text = lines.subList(proseStartLine, (proseEndLine + 1).coerceAtMost(lines.size))
                .joinToString("\n").trim()
            if (text.isNotEmpty()) blocks += AgentBlock.Prose(text)
            proseStartLine = -1
        }
    }

    var node: Node? = document.firstChild
    while (node != null) {
        val special = node.toSpecialBlock()
        if (special != null) {
            flushProse()
            blocks += special
        } else {
            val spans = node.sourceSpans
            if (spans.isNotEmpty()) {
                if (proseStartLine < 0) proseStartLine = spans.first().lineIndex
                proseEndLine = spans.last().lineIndex
            }
        }
        node = node.next
    }
    flushProse()
    return blocks
}

private fun Node.toSpecialBlock(): AgentBlock? = when (this) {
    is FencedCodeBlock -> {
        val info = info?.trim().orEmpty()
        val language = info.substringBefore(' ').ifBlank { null }
        val filename = info.substringAfter(' ', "").trim().ifBlank { null }
            ?.takeIf { it.contains('/') || it.contains('.') }
        val text = literal.orEmpty()
        AgentBlock.Code(
            filename = filename,
            language = language,
            text = text,
            isDiff = language == "diff" || (language == null && looksLikeDiff(text)),
        )
    }

    is IndentedCodeBlock -> AgentBlock.Code(
        filename = null,
        language = null,
        text = literal.orEmpty(),
        isDiff = looksLikeDiff(literal.orEmpty()),
    )

    is BulletList -> toTaskListOrNull()

    is TableBlock -> toOptionTableOrNull()

    is HtmlBlock -> detailsPattern.find(literal.orEmpty())?.let { match ->
        AgentBlock.Collapsible(
            summary = match.groupValues[1].trim(),
            body = match.groupValues[2].trim(),
        )
    }

    else -> null
}

/** A bullet list where every item starts with a checkbox is a task list (spec 05). */
private fun BulletList.toTaskListOrNull(): AgentBlock.TaskList? {
    val items = mutableListOf<TaskItem>()
    var item: Node? = firstChild
    while (item != null) {
        val listItem = item as? ListItem ?: return null
        val paragraph = listItem.firstChild as? Paragraph ?: return null
        val text = paragraph.collectText()
        val match = checkboxPattern.find(text) ?: return null
        val state = when (match.groupValues[1]) {
            "x", "X" -> TaskItemState.Done
            "~", "-" -> TaskItemState.Active
            else -> TaskItemState.Pending
        }
        items += TaskItem(label = match.groupValues[2].trim().lineSequence().first(), state = state)
        item = item.next
    }
    if (items.isEmpty()) return null
    val done = items.count { it.state == TaskItemState.Done }
    return AgentBlock.TaskList(title = null, counter = "$done/${items.size}", items = items)
}

private fun TableBlock.toOptionTableOrNull(): AgentBlock.OptionTable? {
    val rows = mutableListOf<OptionRow>()
    var section: Node? = firstChild
    while (section != null) {
        if (section is TableBody) {
            var row: Node? = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = mutableListOf<String>()
                    var cell: Node? = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) cells += cell.collectText().trim()
                        cell = cell.next
                    }
                    if (cells.isNotEmpty()) {
                        rows += OptionRow(
                            title = cells[0],
                            value = cells.getOrNull(1),
                            detail = if (cells.size > 2) cells.drop(2).joinToString(" · ").ifBlank { null } else null,
                        )
                    }
                }
                row = row.next
            }
        }
        section = section.next
    }
    return if (rows.isEmpty()) null else AgentBlock.OptionTable(rows)
}

internal fun Node.collectText(): String {
    val sb = StringBuilder()
    fun walk(n: Node?) {
        var child = n
        while (child != null) {
            when (child) {
                is Text -> sb.append(child.literal)
                is org.commonmark.node.Code -> sb.append(child.literal)
                is org.commonmark.node.SoftLineBreak, is org.commonmark.node.HardLineBreak -> sb.append('\n')
                else -> walk(child.firstChild)
            }
            child = child.next
        }
    }
    walk(firstChild)
    return sb.toString()
}
