package com.neuron.ai.data.local

import com.neuron.ai.data.local.ChatTemplateRenderer.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptOptionsTest {

    @Test
    fun `no customization leaves the conversation untouched`() {
        val turns = listOf(Turn(Turn.USER, "hi"))
        assertEquals(turns, LocalPromptOptions.applyToTurns(turns, "", "BALANCED"))
        assertNull(LocalPromptOptions.behaviorPrompt("", "BALANCED"))
    }

    @Test
    fun `custom instructions merge into the existing system turn`() {
        val turns = listOf(
            Turn(Turn.SYSTEM, "You are Neuron."),
            Turn(Turn.USER, "hello")
        )

        val merged = LocalPromptOptions.applyToTurns(
            turns,
            "Answer in Hindi.",
            LocalReplyStyle.BALANCED.name
        )

        assertEquals(2, merged.size)
        assertEquals(Turn.SYSTEM, merged.first().role)
        assertTrue(merged.first().content.contains("You are Neuron."))
        assertTrue(merged.first().content.contains("Answer in Hindi."))
        assertEquals("hello", merged.last().content)
    }

    @Test
    fun `missing system turn gets a new one at the front`() {
        val turns = listOf(Turn(Turn.USER, "hello"))

        val merged = LocalPromptOptions.applyToTurns(
            turns,
            "",
            LocalReplyStyle.CONCISE.name
        )

        assertEquals(2, merged.size)
        assertEquals(Turn.SYSTEM, merged.first().role)
        assertTrue(merged.first().content.contains("concise"))
        assertEquals("hello", merged.last().content)
    }

    @Test
    fun `reply style and instructions combine in a stable order`() {
        val prompt = LocalPromptOptions.behaviorPrompt(
            "Use metric units.",
            LocalReplyStyle.DETAILED.name
        )!!

        val instructionIndex = prompt.indexOf("Use metric units.")
        val styleIndex = prompt.indexOf("Explain thoroughly")
        assertTrue(instructionIndex >= 0)
        assertTrue(styleIndex > instructionIndex)
    }

    @Test
    fun `unknown style and oversized instructions degrade safely`() {
        val long = "x".repeat(LocalPromptOptions.MAX_INSTRUCTIONS_CHARS + 500)
        val prompt = LocalPromptOptions.behaviorPrompt(long, "NOT_A_STYLE")!!

        assertEquals(LocalPromptOptions.MAX_INSTRUCTIONS_CHARS, prompt.length)
        assertTrue(LocalPromptOptions.behaviorPrompt(" ", "BALANCED") == null)
    }
}
