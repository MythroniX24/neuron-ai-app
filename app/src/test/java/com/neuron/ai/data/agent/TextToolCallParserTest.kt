package com.neuron.ai.data.agent

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-text tool-call recovery: models that print markup instead of using
 * the native function-calling channel still get their intent executed.
 * The exact format from a real user report is covered.
 */
class TextToolCallParserTest {

    @Test
    fun `parses the xml-dash markup from a real model output`() {
        val text = "<tool_call><function-web.search>" +
            "<parameter-query>Elon Musk net worth 2025</parameter>" +
            "</function></tool_call>"

        val recovered = TextToolCallParser.parse(text)

        assertEquals(1, recovered.size)
        assertEquals("web.search", recovered[0].call.toolId)
        assertEquals(
            "{\"query\":\"Elon Musk net worth 2025\"}",
            recovered[0].call.argumentsJson
        )
    }

    @Test
    fun `parses json style with arguments object`() {
        val text = "<tool_call>{\"name\":\"web.search\",\"arguments\":{\"query\":\"kotlin\"}}</tool_call>"
        val recovered = TextToolCallParser.parse(text)
        assertEquals(1, recovered.size)
        assertEquals("web.search", recovered[0].call.toolId)
        assertTrue(recovered[0].call.argumentsJson.contains("kotlin"))
    }

    @Test
    fun `parses json style with parameters object`() {
        val text = "<tool_call>{\"name\":\"math.evaluate\",\"parameters\":{\"expression\":\"6*7\"}}</tool_call>"
        val recovered = TextToolCallParser.parse(text)
        assertEquals(1, recovered.size)
        assertEquals("math.evaluate", recovered[0].call.toolId)
        assertTrue(recovered[0].call.argumentsJson.contains("6*7"))
    }

    @Test
    fun `handles unclosed tool_call block`() {
        val text = "<tool_call><function-web.search><parameter-query>open tabs</parameter></function>"
        val recovered = TextToolCallParser.parse(text)
        assertEquals(1, recovered.size)
        assertEquals("web.search", recovered[0].call.toolId)
    }

    @Test
    fun `parses multiple blocks in order`() {
        val text = "<tool_call>{\"name\":\"a.b\"}</tool_call> middle " +
            "<tool_call>{\"name\":\"c.d\"}</tool_call>"
        val recovered = TextToolCallParser.parse(text)
        assertEquals(2, recovered.size)
        assertEquals("a.b", recovered[0].call.toolId)
        assertEquals("c.d", recovered[1].call.toolId)
    }

    @Test
    fun `ordinary text yields no recovered calls`() {
        assertTrue(TextToolCallParser.parse("plain text").isEmpty())
        // Mentioning the tag without a function block is not a tool call.
        assertTrue(
            TextToolCallParser.parse("Just a normal answer about <tool_call> as a concept.").isEmpty()
        )
    }

    @Test
    fun `strip removes blocks including unclosed ones`() {
        val text = "Let me check.\n<tool_call><function-web.search>" +
            "<parameter-query>x</parameter></function></tool_call>"
        val cleaned = TextToolCallParser.stripToolMarkup(text)
        assertFalse(cleaned.contains("<tool_call>"))
        assertFalse(cleaned.contains("function-web.search"))
        assertTrue(cleaned.contains("Let me check."))

        val unclosed = "Working on it <tool_call><function-web.search>"
        assertFalse(TextToolCallParser.stripToolMarkup(unclosed).contains("<tool_call>"))
    }

    @Test
    fun `parses the equals-separator variant with mixed closers`() {
        // Real-world sample: <function=web.read><parameter=url>… plus a
        // second block whose output was cut mid-stream.
        val text = "Let me pull actual headlines.\n" +
            "<tool_call><function=web.read><parameter=url>https://techcrunch.com/</parameter>" +
            "<parameter=maxChars>6000</parameter></function></tool_call>\n" +
            "<tool_call><function=web.read><parameter=url>https://www.reuters.com/technology/</parameter>"
        val recovered = TextToolCallParser.parse(text)
        assertEquals(2, recovered.size)
        assertEquals("web.read", recovered[0].call.toolId)
        assertTrue(recovered[0].call.argumentsJson.contains("techcrunch.com"))
        assertTrue(recovered[0].call.argumentsJson.contains("6000"))
        // Truncated second block still recovers its url parameter.
        assertEquals("web.read", recovered[1].call.toolId)
        assertTrue(recovered[1].call.argumentsJson.contains("reuters.com"))
        // The visible text keeps only the narration sentence.
        val cleaned = TextToolCallParser.stripToolMarkup(text)
        assertFalse(cleaned.contains("<tool_call>"))
        assertTrue(cleaned.contains("Let me pull actual headlines."))
    }

    @Test
    fun `escaped parameter values produce valid json`() {
        val text = "<tool_call><function-fs.write>" +
            "<parameter-path>notes.txt</parameter-path>" +
            "<parameter-content>line1\nline2 \"quoted\"</parameter-content>" +
            "</function></tool_call>"
        val recovered = TextToolCallParser.parse(text)
        if (recovered.size != 1) {
            throw AssertionError(
                "diag: size=${recovered.size} args=${recovered.map { it.call.argumentsJson }}"
            )
        }
        // The JSON must parse back without throwing.
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(
            recovered[0].call.argumentsJson
        ).jsonObject
        val content = obj["content"]?.toString()
        if (content == null || !content.contains("quoted")) {
            throw AssertionError("diag: argsJson=${recovered[0].call.argumentsJson} content=$content")
        }
    }
}
