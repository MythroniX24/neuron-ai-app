package com.neuron.ai.ui.markdown

/**
 * Block-level Markdown parser tuned for AI chat output: headings, paragraphs,
 * fenced code, lists, quotes, tables, rules, images and display math. Tolerates
 * partial/streaming input — unfinished constructs degrade to paragraphs.
 */
sealed class MdBlock {

    data class Heading(val level: Int, val text: String) : MdBlock()

    data class Paragraph(val text: String) : MdBlock()

    data class CodeBlock(val language: String?, val code: String) : MdBlock()

    data class LatexBlock(val latex: String) : MdBlock()

    data class Quote(val text: String) : MdBlock()

    data object HorizontalRule : MdBlock()

    /** A standalone picture — either `![alt](url)` or an inline image split out. */
    data class Image(val url: String, val alt: String = "") : MdBlock()

    /**
     * A fenced `a2ui` block: agent-authored UI (cards, file lists, forms)
     * rendered with the A2UI protocol instead of being shown as JSON text.
     */
    data class A2ui(val json: String) : MdBlock()

    /**
     * One list row. [number] carries the number the model actually wrote
     * (`3.` → 3) so a list that the parser splits into several blocks — nested
     * bullets in between do that — keeps counting 1, 2, 3 instead of
     * restarting at 1 every time.
     */
    data class ListItem(val text: String, val number: Int? = null)

    data class ListBlock(
        val items: List<ListItem>,
        val ordered: Boolean
    ) : MdBlock()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : MdBlock()
}

object MarkdownParser {

    private val bulletRegex = Regex("""^\s{0,3}[-*+]\s+(.*)$""")
    private val orderedRegex = Regex("""^\s{0,3}(\d{1,9})[.)]\s+(.*)$""")
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val hrRegex = Regex("""^\s{0,3}([-*_])\s*(?:\1\s*){2,}$""")
    private val quoteRegex = Regex("""^\s{0,3}>\s?(.*)$""")
    private val tableDividerRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    private val imageRegex = Regex("""!\[([^\]]*)]\(([^)\s]+)\)""")

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
                // A2UI blocks are UI, not code: they are handed to the
                // renderer, and an unfinished block while streaming becomes a
                // placeholder instead of a wall of raw JSON.
                if (language == "a2ui" || language == "a2ui-json") {
                    val body = StringBuilder()
                    i++
                    while (i < lines.size &&
                        !Regex("""^\s{0,3}```\s*$""").matches(lines[i])
                    ) {
                        body.appendLine(lines[i])
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += MdBlock.A2ui(body.toString().trim())
                    continue
                }
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
            if (bullet != null || ordered != null) {
                val isOrdered = ordered != null
                val items = mutableListOf(
                    if (ordered != null) {
                        MdBlock.ListItem(
                            text = ordered.groupValues[2],
                            number = ordered.groupValues[1].toIntOrNull()
                        )
                    } else {
                        MdBlock.ListItem(bullet!!.groupValues[1])
                    }
                )
                i++
                while (i < lines.size) {
                    val nextBullet = bulletRegex.find(lines[i])
                    val nextOrdered = orderedRegex.find(lines[i])
                    if (nextBullet == null && nextOrdered == null) break
                    // A different list kind breaks the group.
                    if ((nextOrdered != null) != isOrdered) break
                    items += if (nextOrdered != null) {
                        MdBlock.ListItem(
                            text = nextOrdered.groupValues[2],
                            number = nextOrdered.groupValues[1].toIntOrNull()
                        )
                    } else {
                        MdBlock.ListItem(nextBullet!!.groupValues[1])
                    }
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
            // Images inside a paragraph become their own block so they render
            // as pictures instead of raw `![alt](url)` text.
            appendParagraphWithImages(blocks, paragraph.toString())
        }

        return blocks
    }

    private fun appendParagraphWithImages(blocks: MutableList<MdBlock>, text: String) {
        var cursor = 0
        for (match in imageRegex.findAll(text)) {
            val before = text.substring(cursor, match.range.first).trim()
            if (before.isNotBlank()) blocks += MdBlock.Paragraph(before)
            blocks += MdBlock.Image(
                url = match.groupValues[2],
                alt = match.groupValues[1].trim()
            )
            cursor = match.range.last + 1
        }
        val rest = text.substring(cursor).trim()
        if (rest.isNotBlank()) blocks += MdBlock.Paragraph(rest)
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split('|')
            .map { it.trim() }
}
