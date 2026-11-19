package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.conversation.QuestionCardParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionToolTest {

    private val tool = QuestionTool()

    @Test
    fun `valid single question payload succeeds`() = runTest {
        val json = """
            {
              "title": "Project Setup",
              "questions": [
                {
                  "question": "Kaunsa project pe kaam karna hai?",
                  "options": ["Battle royale map", "Gun model viewer", "Neuroevolution sim", "Etsy accounting app"],
                  "allowCustom": true
                }
              ]
            }
        """.trimIndent()

        val result = tool.execute(json)
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Presented 1 interactive question(s)"))

        val parsed = QuestionCardParser.parse(json)
        assertNotNull(parsed)
        assertEquals("Project Setup", parsed?.title)
        assertEquals(1, parsed?.questions?.size)
        assertEquals(4, parsed?.questions?.first()?.options?.size)
        assertEquals("Battle royale map", parsed?.questions?.first()?.options?.first())
    }

    @Test
    fun `valid multiple questions payload parses with multiple steps`() = runTest {
        val json = """
            {
              "questions": [
                {
                  "question": "Kaunsa project pe kaam karna hai?",
                  "options": ["Battle royale map", "Gun model viewer"]
                },
                {
                  "question": "Kaunsa architecture use karna hai?",
                  "options": ["MVI", "MVVM", "Clean Architecture"]
                }
              ]
            }
        """.trimIndent()

        val result = tool.execute(json)
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Presented 2 interactive question(s)"))

        val parsed = QuestionCardParser.parse(json)
        assertNotNull(parsed)
        assertEquals(2, parsed?.questions?.size)
        assertEquals("Kaunsa project pe kaam karna hai?", parsed?.questions?.get(0)?.question)
        assertEquals("Kaunsa architecture use karna hai?", parsed?.questions?.get(1)?.question)
    }

    @Test
    fun `empty questions payload fails with actionable message`() = runTest {
        val result = tool.execute("""{"questions":[]}""")
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("at least one question"))
    }

    @Test
    fun `markdown fenced question block extracts properly`() {
        val markdown = """
            Ye lo, aise dikhta hai wo card. Neeche tap karke try karo:

            ```question
            {
              "question": "Kaunsa project pe kaam karna hai?",
              "options": ["Battle royale map", "Gun model viewer", "Neuroevolution sim", "Etsy accounting app"]
            }
            ```

            Claude is AI and can make mistakes.
        """.trimIndent()

        val extracted = QuestionCardParser.extractFenced(markdown)
        assertNotNull(extracted)
        val (payload, visible) = extracted!!
        assertEquals(1, payload.questions.size)
        assertEquals("Kaunsa project pe kaam karna hai?", payload.questions.first().question)
        assertEquals(4, payload.questions.first().options.size)
        assertTrue(visible.contains("Ye lo, aise dikhta hai wo card."))
        assertTrue(visible.contains("Claude is AI and can make mistakes."))
        assertTrue(!visible.contains("```question"))
    }
}
