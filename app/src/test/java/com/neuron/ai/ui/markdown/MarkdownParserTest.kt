package com.neuron.ai.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parses standard a2ui block as A2ui block`() {
        val markdown = """
            Here is a card:
            ```a2ui
            {"version":"1.0","createSurface":{"surfaceId":"s1"}}
            ```
            Done.
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertTrue(blocks.any { it is MdBlock.A2ui })
    }

    @Test
    fun `parses ui and card fences as A2ui block`() {
        val markdown = """
            ```card
            {"component":"Card","child":"t1"}
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.A2ui)
    }

    @Test
    fun `parses json fence containing a2ui surface as A2ui block`() {
        val markdown = """
            ```json
            {"createSurface":{"surfaceId":"s1"},"updateComponents":{"surfaceId":"s1","components":[]}}
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.A2ui)
    }

    @Test
    fun `parses image markdown correctly`() {
        val markdown = "![Alt text](https://example.com/photo.jpg)"
        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        val img = blocks.first() as MdBlock.Image
        assertEquals("https://example.com/photo.jpg", img.url)
        assertEquals("Alt text", img.alt)
    }

    @Test
    fun `parses rich_items fence as RichItemsBlock and not CodeBlock`() {
        val markdown = """
            ```rich_items
            {"title":"Top Movies","items":[{"title":"Dune"}]}
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.RichItemsBlock)
    }

    @Test
    fun `parses question fence as QuestionBlock and not CodeBlock`() {
        val markdown = """
            ```question
            {"title":"Next Step","questions":[{"question":"Choose option","options":["A","B"]}]}
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.QuestionBlock)
    }

    @Test
    fun `parses normal programming language as CodeBlock`() {
        val markdown = """
            ```kotlin
            fun main() { println("Hello") }
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.CodeBlock)
        val code = blocks.first() as MdBlock.CodeBlock
        assertEquals("kotlin", code.language)
    }

    @Test
    fun `parses geometry and tree fences as DiagramBlock`() {
        val geoMd = """
            ```geometry
            {"type":"triangle","title":"Right Triangle","points":[{"id":"A","label":"A","x":100,"y":50}]}
            ```
        """.trimIndent()
        val geoBlocks = MarkdownParser.parse(geoMd)
        assertEquals(1, geoBlocks.size)
        assertTrue(geoBlocks.first() is MdBlock.DiagramBlock)
        val geo = geoBlocks.first() as MdBlock.DiagramBlock
        assertEquals("geometry", geo.type)

        val treeMd = """
            ```tree
            {"type":"tree","title":"Family Tree","treeRoot":{"label":"Root"}}
            ```
        """.trimIndent()
        val treeBlocks = MarkdownParser.parse(treeMd)
        assertEquals(1, treeBlocks.size)
        assertTrue(treeBlocks.first() is MdBlock.DiagramBlock)
        val tree = treeBlocks.first() as MdBlock.DiagramBlock
        assertEquals("tree", tree.type)
    }
}
