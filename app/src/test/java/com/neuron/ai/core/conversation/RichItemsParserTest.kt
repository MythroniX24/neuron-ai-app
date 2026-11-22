package com.neuron.ai.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichItemsParserTest {

    @Test
    fun `parses a complete recommendation payload`() {
        val set = RichItemsParser.parse(
            """
            {
              "title": "Your 3 picks this Friday",
              "intro": "Dragons, animated fantasy and cinematic adventure.",
              "items": [{
                "title": "The Dragon Prince",
                "year": "2018–2024",
                "meta": "Animated fantasy series · 7 seasons",
                "description": "Two princes and an elven assassin quest through Xadia.",
                "badge": "Best match for your tastes",
                "imageUrl": "https://image.tmdb.org/t/p/w500/poster.jpg",
                "details": [
                  {"label": "Episode length", "value": "About 28 minutes"},
                  {"label": "Language", "value": "Hindi and English"}
                ],
                "status": "Hindi dub confirmed",
                "statusTone": "positive",
                "links": [
                  {"label": "Watch on Netflix", "url": "https://www.netflix.com/title/80212245"}
                ]
              }]
            }
            """.trimIndent()
        )!!

        assertEquals("Your 3 picks this Friday", set.title)
        assertTrue(set.intro.contains("Dragons"))
        val item = set.items.single()
        assertEquals("The Dragon Prince", item.title)
        assertEquals("2018–2024", item.year)
        assertEquals("Best match for your tastes", item.badge)
        assertEquals(2, item.details.size)
        assertEquals("Hindi dub confirmed", item.status)
        assertEquals("https://www.netflix.com/title/80212245", item.links.single().url)
    }

    @Test
    fun `accepts aliases and object-shaped details`() {
        val set = RichItemsParser.parse(
            """
            {
              "headline": "Tools worth trying",
              "summary": "Three options.",
              "results": [{
                "name": "Neuron",
                "version": "1.2",
                "subtitle": "Android AI app",
                "overview": "Runs cloud and local models.",
                "thumbnail": "https://example.com/neuron.png",
                "features": {"Platform": "Android", "License": "MIT"},
                "actions": {"Website": "https://example.com"}
              }]
            }
            """.trimIndent()
        )!!

        assertEquals("Tools worth trying", set.title)
        assertEquals("Three options.", set.intro)
        val item = set.items.single()
        assertEquals("Neuron", item.title)
        assertEquals("1.2", item.year)
        assertEquals("Android AI app", item.meta)
        assertEquals(2, item.details.size)
        assertEquals("Website", item.links.single().label)
    }

    @Test
    fun `rejects payloads without a usable item`() {
        assertNull(RichItemsParser.parse("""{"title":"Empty","items":[]}"""))
        assertNull(RichItemsParser.parse("""{"title":"No title","items":[{"year":"2024"}]}"""))
        assertNull(RichItemsParser.parse("not json"))
    }

    @Test
    fun `filters unsafe image and action urls`() {
        val set = RichItemsParser.parse(
            """
            {
              "title": "Picks",
              "items": [{
                "title": "Thing",
                "imageUrl": "javascript:alert(1)",
                "links": [
                  {"label": "Bad", "url": "file:///etc/passwd"},
                  {"label": "Good", "url": "https://example.com/good"}
                ]
              }]
            }
            """.trimIndent()
        )!!

        assertNull(set.items.single().imageUrl)
        assertEquals(listOf("Good"), set.items.single().links.map { it.label })
    }

    @Test
    fun `fenced fallback is extracted and removed from visible text`() {
        val text = """
            Here are my picks.
            ```json
            {"title":"Picks","items":[{"title":"A"}]}
            ```
            Enjoy!
        """.trimIndent()

        val (set, visible) = RichItemsParser.extractFenced(text)!!
        assertEquals("A", set.items.single().title)
        assertTrue(!visible.contains("```"))
        assertTrue(visible.contains("Here are my picks."))
        assertTrue(visible.contains("Enjoy!"))
    }

    @Test
    fun `fenced rich_items tag is extracted and removed from visible text`() {
        val text = """
            Here are my movie picks.
            ```rich_items
            {"title":"Latest Movies","items":[{"title":"Deadpool & Wolverine","year":"2026"}]}
            ```
            Let me know which you prefer!
        """.trimIndent()

        val (set, visible) = RichItemsParser.extractFenced(text)!!
        assertEquals("Deadpool & Wolverine", set.items.single().title)
        assertTrue(!visible.contains("```rich_items"))
        assertTrue(!visible.contains("Deadpool"))
        assertTrue(visible.contains("Here are my movie picks."))
        assertTrue(visible.contains("Let me know which you prefer!"))
    }
}
