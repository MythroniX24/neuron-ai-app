package com.neuron.ai.data.local

import com.neuron.ai.data.local.ChatTemplateRenderer.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These lock in what the app used to get wrong: a hand-written
 * `system\n...\nassistant\n` prompt with no control tokens, which no
 * instruct-tuned model was trained on.
 */
class ChatTemplateRendererTest {

    @Test
    fun `a single user turn renders with ChatML control tokens and opens the assistant turn`() {
        assertEquals(
            "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n",
            ChatTemplateRenderer.renderChatMl(listOf(Turn(Turn.USER, "hi")))
        )
    }

    @Test
    fun `every role is delimited by the control tokens the model was trained on`() {
        val rendered = ChatTemplateRenderer.renderChatMl(
            listOf(
                Turn(Turn.SYSTEM, "be brief"),
                Turn(Turn.USER, "hello"),
                Turn(Turn.ASSISTANT, "hi")
            )
        )
        assertEquals(
            "<|im_start|>system\nbe brief<|im_end|>\n" +
                "<|im_start|>user\nhello<|im_end|>\n" +
                "<|im_start|>assistant\nhi<|im_end|>\n" +
                "<|im_start|>assistant\n",
            rendered
        )
    }

    @Test
    fun `no bos token is prepended - the tokenizer adds it and a doubled bos is a regression`() {
        assertFalse(ChatTemplateRenderer.renderChatMl(listOf(Turn(Turn.USER, "hi")))
            .startsWith("<s>"))
    }

    @Test
    fun `an unknown role degrades to a user turn instead of being dropped`() {
        val rendered = ChatTemplateRenderer.renderChatMl(listOf(Turn("tool", "42")))
        assertEquals(
            "<|im_start|>user\n42<|im_end|>\n<|im_start|>assistant\n",
            rendered
        )
    }

    @Test
    fun `multi-line content stays inside its own turn`() {
        val rendered = ChatTemplateRenderer.renderChatMl(
            listOf(Turn(Turn.USER, "line one\nline two"))
        )
        assertEquals(1, Regex("<\\|im_start\\|>user").findAll(rendered).count())
        assertTrue(rendered.contains("line one\nline two"))
    }

    @Test
    fun `the media marker goes inside the image turn so mtmd can splice embeddings there`() {
        val content = ChatTemplateRenderer.withMediaMarker("what is this?")
        assertTrue(content.endsWith("<|image|>"))
        // It must not be a separate turn of its own.
        assertFalse(content.startsWith("<|image|>"))
    }

    @Test
    fun `the media marker is not added twice`() {
        val once = ChatTemplateRenderer.withMediaMarker("what is this?")
        assertEquals(once, ChatTemplateRenderer.withMediaMarker(once))
        assertEquals(1, Regex("<\\|image\\|>").findAll(once).count())
    }

    @Test
    fun `the media marker constant matches the one the native side is built with`() {
        // neuron_llama.cpp sets mtmd media_marker to this exact string; if the
        // two ever drift, mtmd rejects every image as a marker mismatch.
        assertEquals("<|image|>", LocalEngineLoader.MEDIA_MARKER)
    }

    @Test
    fun `without a loaded model the renderer falls back to ChatML instead of failing`() {
        // Unit tests have no native library, so this exercises the fallback
        // branch the app takes on a template-less GGUF.
        val turns = listOf(Turn(Turn.USER, "hello"))
        assertEquals(
            "<|im_start|>user\nhello<|im_end|>\n<|im_start|>assistant\n",
            LocalEngineLoader.renderWithChatTemplate(turns)
        )
    }

    @Test
    fun `an empty conversation still produces an openable assistant turn`() {
        assertEquals(
            "<|im_start|>assistant\n",
            LocalEngineLoader.renderWithChatTemplate(emptyList())
        )
    }
}