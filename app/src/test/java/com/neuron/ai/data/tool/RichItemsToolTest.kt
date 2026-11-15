package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class RichItemsToolTest {

    private val tool = RichItemsTool()

    @Test
    fun `valid payload succeeds and tells the model not to repeat the list`() = runTest {
        val result = tool.execute(
            """{"title":"Picks","items":[{"title":"A"},{"title":"B"}]}"""
        )
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("2 recommendation card"))
        assertTrue(output.contains("Do not repeat"))
    }

    @Test
    fun `malformed payload fails with an actionable message`() = runTest {
        val result = tool.execute("""{"title":"Picks","items":[]}""")
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("at least one item"))
    }

    @Test
    fun `schema exposes the universal card fields`() {
        val schema = tool.parametersSchemaJson
        assertTrue(schema.contains("\"imageUrl\""))
        assertTrue(schema.contains("\"details\""))
        assertTrue(schema.contains("\"status\""))
        assertTrue(schema.contains("\"links\""))
    }
}
