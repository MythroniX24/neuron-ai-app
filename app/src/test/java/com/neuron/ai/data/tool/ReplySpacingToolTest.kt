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
    fun `paragraph and list spacing are chosen independently`() = runTest {
        val args = """{"paragraph":"relaxed","list":"compact"}"""
        val result = tool.execute(args)
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("paragraphs relaxed"))
        assertTrue(output.contains("lists compact"))

        val profile = ReplySpacingTool.profileFrom(args)!!
        assertEquals(ResponseSpacing.RELAXED, profile.paragraph)
        assertEquals(ResponseSpacing.COMPACT, profile.list)
    }

    @Test
    fun `legacy single spacing fills both kinds`() {
        val profile = ReplySpacingTool.profileFrom("""{"spacing":"airy"}""")!!
        assertEquals(ResponseSpacing.AIRY, profile.paragraph)
        assertEquals(ResponseSpacing.AIRY, profile.list)
    }

    @Test
    fun `invalid or missing spacing fails without applying a choice`() = runTest {
        val result = tool.execute("""{"paragraph":"extra-wide","list":"compact"}""")
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("compact, comfortable"))
        assertEquals(null, ReplySpacingTool.profileFrom("{}"))
    }
}
