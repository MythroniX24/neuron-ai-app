package com.neuron.ai.data.local

import com.neuron.ai.data.local.ChatTemplateRenderer.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the failure that made a local chat permanently broken:
 * an unbounded transcript against a 4k window ended every later turn with
 * "Prompt exceeded the model's context window".
 */
class ContextTrimmerTest {

    private fun chat(turns: Int, filler: Int = 40) = buildList {
        add(Turn(Turn.SYSTEM, "you are helpful"))
        repeat(turns) { index ->
            add(Turn(Turn.USER, "q$index " + "x".repeat(filler)))
            add(Turn(Turn.ASSISTANT, "a$index " + "y".repeat(filler)))
        }
    }

    /** Deterministic stand-in for the real tokenizer: 1 token per 4 chars. */
    private val count: (String) -> Int = { it.length / 4 }

    private val render: (List<Turn>, Boolean) -> String =
        { turns, addAssistant -> ChatTemplateRenderer.renderChatMl(turns, addAssistant) }

    @Test
    fun `a short conversation is passed through untouched`() {
        val turns = chat(2)
        val fitted = ContextTrimmer.fit(turns, 4096, 1024, render = render, countTokens = count)
        assertEquals(turns, fitted.turns)
        assertEquals(0, fitted.droppedTurns)
    }

    @Test
    fun `an oversized conversation is trimmed to fit`() {
        val fitted = ContextTrimmer.fit(
            chat(40), contextTokens = 512, maxOutputTokens = 256,
            render = render, countTokens = count
        )
        assertTrue(fitted.droppedTurns > 0)
        // Prompt plus the answer must fit the window.
        assertTrue(
            "prompt was ${count(fitted.prompt)} tokens",
            count(fitted.prompt) + 256 <= 512
        )
    }

    @Test
    fun `the system prompt survives every trim`() {
        val fitted = ContextTrimmer.fit(
            chat(40), contextTokens = 512, maxOutputTokens = 128,
            render = render, countTokens = count
        )
        assertEquals(Turn.SYSTEM, fitted.turns.first().role)
        assertTrue(fitted.prompt.contains("you are helpful"))
    }

    @Test
    fun `a trimmed conversation never opens with an orphaned assistant reply`() {
        val fitted = ContextTrimmer.fit(
            chat(40), contextTokens = 512, maxOutputTokens = 64,
            render = render, countTokens = count
        )
        val firstNonSystem = fitted.turns.first { it.role != Turn.SYSTEM }
        assertEquals(Turn.USER, firstNonSystem.role)
    }

    @Test
    fun `at least the newest turn is always kept`() {
        val turns = listOf(
            Turn(Turn.USER, "q " + "x".repeat(400)),
            Turn(Turn.USER, "the newest question")
        )
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 32, maxOutputTokens = 8,
            render = render, countTokens = count
        )
        assertEquals("the newest question", fitted.turns.last().content)
    }

    @Test
    fun `room for the answer is reserved so a full prompt cannot truncate it`() {
        // 400 tokens of prompt, 400 of window: naive behaviour fills the whole
        // window and leaves the model nothing to generate.
        val turns = listOf(Turn(Turn.USER, "x".repeat(1600)))
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 400, maxOutputTokens = 200,
            render = render, countTokens = count
        )
        assertTrue(
            "prompt was ${count(fitted.prompt)} tokens",
            count(fitted.prompt) <= 400 - 200
        )
        assertTrue(fitted.turns.single().content.isNotEmpty())
    }

    @Test
    fun `one oversized turn is truncated rather than failing the whole turn`() {
        val turns = listOf(Turn(Turn.USER, "document ".repeat(2000)))
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 512, maxOutputTokens = 128,
            render = render, countTokens = count
        )
        assertTrue(count(fitted.prompt) <= 512 - 128)
        assertEquals(1, fitted.turns.size)
    }

    @Test
    fun `truncating a picture turn keeps the media marker`() {
        val turns = listOf(
            Turn(Turn.USER, "look ".repeat(2000) + LocalEngineLoader.MEDIA_MARKER)
        )
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 512, maxOutputTokens = 128,
            render = render, countTokens = count
        )
        assertTrue(fitted.hasMediaMarker)
        assertTrue(count(fitted.prompt) <= 512 - 128)
    }

    @Test
    fun `the media marker is reported so a trimmed picture falls back to text`() {
        val turns = listOf(
            Turn(Turn.USER, "old picture " + LocalEngineLoader.MEDIA_MARKER + " " + "x".repeat(800)),
            Turn(Turn.ASSISTANT, "it is a cat"),
            Turn(Turn.USER, "and now?")
        )
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 100, maxOutputTokens = 16,
            render = render, countTokens = count
        )
        // Either it still fits and the marker survives, or it was dropped and
        // the caller must NOT hand a bitmap to mtmd.
        if (fitted.droppedTurns > 0) {
            assertFalse(fitted.hasMediaMarker)
        }
    }

    /**
     * The failure this pins down: a model whose window is no bigger than the
     * answer reservation (a 1k-context GGUF with the default 1024-token
     * answer budget) used to make the prompt budget NEGATIVE, and the old code
     * answered that by sending the conversation whole — so the engine rejected
     * every turn with "prompt exceeded the model's context window".
     */
    @Test
    fun `a small window with a full-size answer budget still trims`() {
        val fitted = ContextTrimmer.fit(
            chat(40), contextTokens = 1024, maxOutputTokens = 1024,
            render = render, countTokens = count
        )
        val reserve = ContextTrimmer.answerReservation(1024, 1024)
        assertEquals(512, reserve)
        assertTrue(
            "the conversation had to be trimmed, not sent whole",
            fitted.droppedTurns > 0
        )
        assertTrue(
            "prompt was ${count(fitted.prompt)} tokens",
            count(fitted.prompt) + reserve <= 1024
        )
    }

    @Test
    fun `the answer reservation never eats more than half the window`() {
        assertEquals(512, ContextTrimmer.answerReservation(1024, 1024))
        assertEquals(64, ContextTrimmer.answerReservation(1024, 64))
        assertEquals(1024, ContextTrimmer.answerReservation(4096, 1024))
        // Unknown window: untouched, and the 0-context path bails out instead.
        assertEquals(1024, ContextTrimmer.answerReservation(0, 1024))
    }

    /**
     * When the mandatory scaffolding alone fills the window there is nothing
     * left to drop — the leading turns have to give ground too, instead of the
     * whole (over-long) prompt going to the engine.
     */
    @Test
    fun `over-long system instructions are truncated rather than sent whole`() {
        val turns = listOf(
            Turn(Turn.SYSTEM, "instructions ".repeat(200)),
            Turn(Turn.USER, "hi")
        )
        val fitted = ContextTrimmer.fit(
            turns, contextTokens = 200, maxOutputTokens = 64,
            render = render, countTokens = count
        )
        val reserve = ContextTrimmer.answerReservation(200, 64)
        assertTrue(
            "prompt was ${count(fitted.prompt)} tokens",
            count(fitted.prompt) + reserve <= 200
        )
        assertFalse(fitted.prompt.contains("instructions ".repeat(20)))
    }

    @Test
    fun `an unknown context length is not guessed at`() {
        val turns = chat(3)
        val fitted = ContextTrimmer.fit(turns, 0, 1024, render = render, countTokens = count)
        assertEquals(turns, fitted.turns)
    }

    @Test
    fun `an empty conversation renders without trimming`() {
        val fitted = ContextTrimmer.fit(emptyList(), 4096, 512, render = render, countTokens = count)
        assertEquals("<|im_start|>assistant\n", fitted.prompt)
    }
}