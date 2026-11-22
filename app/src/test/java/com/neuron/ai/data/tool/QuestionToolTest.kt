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

    @Test
    fun `xml question block extracts properly`() {
        val xmlText = """
            Here are your next steps:
            <question>
            {
              "question": "Which tech stack do you prefer?",
              "options": ["Kotlin + Compose", "Flutter", "React Native"]
            }
            </question>
            Let me know your choice.
        """.trimIndent()

        val extracted = QuestionCardParser.extractFenced(xmlText)
        assertNotNull(extracted)
        val (payload, visible) = extracted!!
        assertEquals(1, payload.questions.size)
        assertEquals("Which tech stack do you prefer?", payload.questions.first().question)
        assertEquals(3, payload.questions.first().options.size)
        assertTrue(visible.contains("Here are your next steps:"))
        assertTrue(!visible.contains("<question>"))
    }

    @Test
    fun `prose with numbered choice list extracts to question card`() {
        val prose = """
            I have analyzed the database migration.

            Which option would you like to proceed with?
            1. Run dry-run migration
            2. Apply migration directly
            3. Cancel and backup
        """.trimIndent()

        val extracted = QuestionCardParser.extractFromProse(prose)
        assertNotNull(extracted)
        val (payload, visible) = extracted!!
        assertEquals(1, payload.questions.size)
        assertEquals("Which option would you like to proceed with?", payload.questions.first().question)
        assertEquals(3, payload.questions.first().options.size)
        assertEquals("Run dry-run migration", payload.questions.first().options[0])
        assertEquals("Apply migration directly", payload.questions.first().options[1])
        assertEquals("Cancel and backup", payload.questions.first().options[2])
    }

    @Test
    fun `prose with parenthetical choices extracts to question card`() {
        val hindiProse = """
            आज जयपुर में 24 कैरेट सोने का भाव ₹1,28,000 प्रति 10 ग्राम है।
            अगर आप वज़न (जैसे 10 ग्राम, 50 ग्राम या 1 किलो) बताएं, तो मैं अनुमानित लागत का हिसाब लगा सकता हूँ।
        """.trimIndent()

        val extracted = QuestionCardParser.extractFromProse(hindiProse)
        assertNotNull(extracted)
        val (payload, visible) = extracted!!
        assertEquals(1, payload.questions.size)
        assertTrue(payload.questions.first().options.contains("10 ग्राम"))
        assertTrue(payload.questions.first().options.contains("50 ग्राम"))
        assertTrue(payload.questions.first().options.contains("1 किलो"))
    }

    @Test
    fun `extractFenced removes question code block from visible text`() {
        val prose = """
            Got it! Let me ask you a few questions.
            ```question
            {"title": "Let's Get Started!", "questions": [{"question": "What's your current goal?", "options": ["Option 1", "Option 2"]}]}
            ```
            Hope this helps!
        """.trimIndent()

        val extracted = QuestionCardParser.extractFenced(prose)
        assertNotNull(extracted)
        val (payload, visible) = extracted!!
        assertEquals("Let's Get Started!", payload.title)
        assertEquals("What's your current goal?", payload.questions.first().question)
        assertEquals(2, payload.questions.first().options.size)
        assertTrue(!visible.contains("```question"))
        assertTrue(!visible.contains("Option 1"))
        assertTrue(visible.contains("Got it! Let me ask you a few questions."))
        assertTrue(visible.contains("Hope this helps!"))
    }
}
