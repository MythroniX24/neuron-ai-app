package com.neuron.ai.ui.markdown

/**
 * Block-level Markdown parser tuned for AI chat output: headings, paragraphs,
 * fenced code, lists, quotes, tables, rules and display math. Tolerates
 * partial/streaming input — unfinished constructs degrade to paragraphs.
 */
sealed class MdBlock {

    data class Heading(val level: Int, val text: String) : MdBlock()

    data class Paragraph(val text: String) : MdBlock()

    data class CodeBlock(val language: String?, val code: String) : MdBlock()

    data class LatexBlock(val latex: String) : MdBlock()

    data class Quote(val text: String) : MdBlock()

    data object HorizontalRule : MdBlock()

    data class ListBlock(
        val items: List<String>,
        val ordered: Boolean
    ) : MdBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : MdBlock()
}

object MarkdownParser {

    private val bulletRegex = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
    private val orderedRegex = Regex("""^\s{0,3}\d{1,9}[.)]\s+(.*)$""")
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val hrRegex = Regex("""^\s{0,3}([-*_])\s*(?:\1\s*){2,}$""")
    private val quoteRegex = Regex("""^\s{0,3}>\s?(.*)$""")
    private val tableDividerRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block.
            val fence = Regex("""^\s{0,3}```\s*(\S*)\s*$""").find(line)
            if (fence != null) {
                val language = fence.groupValues[1].takeIf { it.isNotBlank() }
                val code = StringBuilder()
                i++
                while (i < lines.size && !Regex("""^\s{0,3}```\s*$""").matches(lines[i])) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += MdBlock.CodeBlock(language, code.toString().trimEnd('\n'))
                i++
                continue
            }

            // Display math: $$ ... $$ (fence may span lines).
            if (line.trim().startsWith("$$")) {
                val inner = line.trim().removePrefix("$$").trim()
                if (inner.endsWith("$$") && inner.length >= 2) {
                    blocks += MdBlock.LatexBlock(inner.dropLast(2).trim())
                    i++
                    continue
                }
                val latex = StringBuilder(inner)
                i++
                while (i < lines.size && !lines[i].trim().endsWith("$$")) {
                    latex.append('\n').append(lines[i])
                    i++
                }
                if (i < lines.size) {
                    latex.append('\n').append(lines[i].trim().removeSuffix("$$"))
                    i++
                }
                val content = latex.toString().trim()
                if (content.isNotEmpty()) blocks += MdBlock.LatexBlock(content)
                continue
            }

            // Heading.
            val heading = headingRegex.find(line)
            if (heading != null) {
                blocks += MdBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim()
                )
                i++
                continue
            }

            // Horizontal rule.
            if (hrRegex.matches(line)) {
                blocks += MdBlock.HorizontalRule
                i++
                continue
            }

            // Quote.
            val quoteStart = quoteRegex.find(line)
            if (quoteStart != null) {
                val quote = StringBuilder(quoteStart.groupValues[1])
                i++
                while (i < lines.size) {
                    val continuation = quoteRegex.find(lines[i]) ?: break
                    quote.append('\n').append(continuation.groupValues[1])
                    i++
                }
                blocks += MdBlock.Quote(quote.toString())
                continue
            }

            // Table: header row with | and a divider row beneath.
            if (line.contains('|') && i + 1 < lines.size && tableDividerRegex.matches(lines[i + 1])) {
                val headers = splitTableRow(line)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitTableRow(lines[i])
                    i++
                }
                blocks += MdBlock.Table(headers, rows)
                continue
            }

            // Lists.
            val bullet = bulletRegex.find(line)
            val ordered = orderedRegex.find(line)
            val listStart = ordered ?: bullet
            if (listStart != null) {
                val isOrdered = ordered != null
                val items = mutableListOf(listStart.groupValues[1])
                i++
                while (i < lines.size) {
                    val next = bulletRegex.find(lines[i]) ?: orderedRegex.find(lines[i]) ?: break
                    // A different list kind breaks the group.
                    if (orderedRegex.matches(lines[i]) != isOrdered) break
                    items += next.groupValues[1]
                    i++
                }
                blocks += MdBlock.ListBlock(items, isOrdered)
                continue
            }

            // Blank line.
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph: gather until blank line or a block starter.
            val paragraph = StringBuilder(line.trim())
            i++
            while (i < lines.size) {
                val next = lines[i]
                if (next.isBlank() ||
                    headingRegex.matches(next) ||
                    bulletRegex.matches(next) ||
                    orderedRegex.matches(next) ||
                    next.trimStart().startsWith("```") ||
                    next.trimStart().startsWith("$$") ||
                    hrRegex.matches(next)
                ) break
                paragraph.append('\n').append(next.trim())
                i++
            }
            blocks += MdBlock.Paragraph(paragraph.toString())
        }

        return blocks
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split('|')
            .map { it.trim() }
}
