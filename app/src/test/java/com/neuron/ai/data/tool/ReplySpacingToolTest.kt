package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.settings.ResponseSpacing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySpacingToolTest {

    private val tool = ReplySpacingTool()

    @Test
    fun `valid spacing succeeds and resolves synonyms`() = runTest {
        val result = tool.execute("""{"spacing":"relaxed"}""")
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("Relaxed"))

        assertEquals(
            ResponseSpacing.COMFORTABLE,
            ReplySpacingTool.spacingFrom("""{"spacing":"normal"}""")
        )
        assertEquals(
            ResponseSpacing.AIRY,
            ReplySpacingTool.spacingFrom("""{"spacing":" AIRY "}""")
        )
    }

    @Test
    fun `invalid or missing spacing fails without applying a choice`() = runTest {
        val result = tool.execute("""{"spacing":"extra-wide"}""")
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("compact, comfortable"))
        assertEquals(null, ReplySpacingTool.spacingFrom("{}"))
    }
}
