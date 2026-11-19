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
}
