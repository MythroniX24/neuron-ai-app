package com.neuron.ai.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyChatHeroTest {

    @Test
    fun testChatSuggestionDefaults() {
        val suggestion = ChatSuggestion(
            label = "Explain a topic",
            prompt = "Explain quantum physics",
            icon = Icons.Outlined.Lightbulb
        )
        assertEquals("Explain a topic", suggestion.label)
        assertEquals("Explain quantum physics", suggestion.prompt)
        assertNotNull(suggestion.icon)
        assertEquals(Color(0xFF8B5CF6), suggestion.accentColor)
    }

    @Test
    fun testChatSuggestionCustomAccent() {
        val customColor = Color(0xFF06B6D4)
        val suggestion = ChatSuggestion(
            label = "Search web",
            prompt = "Search for AI news",
            icon = Icons.Outlined.Lightbulb,
            accentColor = customColor
        )
        assertEquals(customColor, suggestion.accentColor)
    }

    @Test
    fun testGreetingHelperCoverage() {
        val greet = greeting()
        assertTrue(greet.isNotBlank())
        assertTrue(
            greet in listOf("Good morning", "Good afternoon", "Good evening", "Working late")
        )
    }
}
