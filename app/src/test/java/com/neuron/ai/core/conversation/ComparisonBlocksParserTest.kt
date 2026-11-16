package com.neuron.ai.core.conversation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model writes this JSON, not a developer: the parser must accept the
 * common synonyms, drop junk instead of crashing, cap everything, and only
 * ever hand the UI http(s) image URLs.
 */
class ComparisonBlocksParserTest {

    @Test
    fun `parses all five block types in one payload`() {
        val raw = """
            {
              "blocks": [
                {
                  "type": "side_by_side",
                  "optionA": {
                    "title": "Pixel 9", "label": "Option A",
                    "imageQuery": "Pixel 9", "specs": [{"label": "RAM", "value": "12 GB"}]
                  },
                  "optionB": { "title": "iPhone 16", "label": "Option B" }
                },
                {
                  "type": "feature_table",
                  "rows": [
                    {"feature": "Battery", "a": "4700 mAh", "b": "3561 mAh"},
                    {"feature": "Display", "a": "OLED", "b": "OLED"}
                  ]
                },
                {
                  "type": "score",
                  "optionA": {"label": "Option A", "score": 8.6},
                  "optionB": {"label": "Option B", "score": "7.4/10"}
                },
                {
                  "type": "pros_cons",
                  "pros": ["Clean software"],
                  "cons": ["Heavy"]
                },
                {
                  "type": "priority",
                  "title": "Compare by priority",
                  "priorities": [
                    {"label": "Performance", "recommendation": "Pick Option A"},
                    {"label": "Battery", "recommendation": "Pick Option B"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val blocks = ComparisonBlocksParser.parse(raw)

        assertEquals(5, blocks.size)
        val side = blocks[0] as ComparisonUiBlock.SideBySide
        assertEquals("Pixel 9", side.optionA.title)
        assertEquals("Option B", side.optionB.label)
        assertEquals("RAM", side.optionA.specs.single().label)
        val table = blocks[1] as ComparisonUiBlock.FeatureTable
        assertEquals(2, table.rows.size)
        assertEquals("Battery", table.rows.first().feature)
        val score = blocks[2] as ComparisonUiBlock.Score
        assertEquals(8.6, score.optionA.score, 0.0001)
        assertEquals(7.4, score.optionB.score, 0.0001)
        assertEquals(10.0, score.optionB.maxScore, 0.0001)
        val prosCons = blocks[3] as ComparisonUiBlock.ProsCons
        assertEquals(listOf("Clean software"), prosCons.pros)
        val priority = blocks[4] as ComparisonUiBlock.Priority
        assertEquals(2, priority.priorities.size)
        assertEquals("Battery", priority.priorities[1].label)
    }

    @Test
    fun `accepts synonyms, single objects and domain-specific labels`() {
        val raw = """
            {
              "kind": "twoCards",
              "options": [
                {"name": "Basic plan", "pill": "Starter", "specs": {"Seats": "3"}},
                {"name": "Pro plan", "pill": "Pro", "specs": {"Seats": "25"}}
              ]
            }
        """.trimIndent()

        val block = ComparisonBlocksParser.parse(raw).single() as ComparisonUiBlock.SideBySide
        assertEquals("Basic plan", block.optionA.title)
        assertEquals("Starter", block.optionA.label)
        assertEquals("Pro", block.optionB.label)
        assertEquals("Seats", block.optionA.specs.single().label)
        assertEquals("3", block.optionA.specs.single().value)
    }

    @Test
    fun `drops unusable payloads instead of throwing`() {
        assertTrue(ComparisonBlocksParser.parse("not json at all").isEmpty())
        assertTrue(ComparisonBlocksParser.parse("""{"blocks": []}""").isEmpty())
        // Unknown type, no inferable shape.
        assertTrue(ComparisonBlocksParser.parse("""{"blocks":[{"type":"pie_chart"}]}""").isEmpty())
        // Sides with no name at all cannot render.
        assertTrue(
            ComparisonBlocksParser.parse(
                """{"blocks":[{"type":"side_by_side","optionA":{},"optionB":{}}]}"""
            ).isEmpty()
        )
        // A label-only side falls back to that label as its title.
        val labelled = ComparisonBlocksParser.parse(
            """{"type":"side_by_side","optionA":{"label":"A"},"optionB":{"label":"B"}}"""
        ).single() as ComparisonUiBlock.SideBySide
        assertEquals("A", labelled.optionA.title)
    }

    @Test
    fun `only http image URLs survive and queries are kept for lookup`() {
        val raw = """
            {
              "type": "side_by_side",
              "optionA": {"title": "A", "imageUrl": "javascript:alert(1)"},
              "optionB": {"title": "B", "imageUrl": "https://img.example.com/b.jpg", "imageQuery": "B phone"}
            }
        """.trimIndent()
        val block = ComparisonBlocksParser.parse(raw).single() as ComparisonUiBlock.SideBySide
        assertNull(block.optionA.imageUrl)
        assertEquals("https://img.example.com/b.jpg", block.optionB.imageUrl)
        // A verified URL wins; the phrase is not kept for a needless lookup.
        assertNull(block.optionB.imageQuery)
    }

    @Test
    fun `caps long lists so a runaway payload cannot bloat the message`() {
        val specs = (1..20).joinToString(",") { """{"label":"S$it","value":"V$it"}""" }
        val rows = (1..30).joinToString(",") { """{"feature":"F$it","a":"x","b":"y"}""" }
        val raw = """
            {"blocks":[
              {"type":"side_by_side",
               "optionA":{"title":"A","specs":[$specs]},
               "optionB":{"title":"B"}},
              {"type":"feature_table","rows":[$rows]}
            ]}
        """.trimIndent()
        val blocks = ComparisonBlocksParser.parse(raw)
        assertEquals(8, (blocks[0] as ComparisonUiBlock.SideBySide).optionA.specs.size)
        assertEquals(12, (blocks[1] as ComparisonUiBlock.FeatureTable).rows.size)
    }

    @Test
    fun `segments keep text and cards in reading order`() {
        val first = ComparisonUiBlock.ProsCons(pros = listOf("Good"), cons = listOf("Bad"))
        val second = ComparisonUiBlock.Priority(priorities = listOf(PriorityOption("Price")))
        val markdown = "Intro line\n\nOutro line"
        val placements = listOf(
            ComparisonPlacement(anchor = 10, block = first),
            ComparisonPlacement(anchor = 10, block = second)
        )

        val segments = ComparisonLayout.segments(markdown, placements)

        assertEquals(4, segments.size)
        assertEquals("Intro line", (segments[0] as ComparisonSegment.Text).text.trim())
        assertTrue(segments[1] is ComparisonSegment.Block)
        assertTrue(segments[2] is ComparisonSegment.Block)
        assertEquals("Outro line", (segments[3] as ComparisonSegment.Text).text.trim())
    }

    @Test
    fun `anchors past the end are clamped instead of crashing`() {
        val block = ComparisonUiBlock.FeatureTable(rows = listOf(FeatureRow("F", "a", "b")))
        val segments = ComparisonLayout.segments(
            "short",
            listOf(ComparisonPlacement(anchor = 9_999, block = block))
        )
        assertEquals(2, segments.size)
        assertEquals("short", (segments[0] as ComparisonSegment.Text).text)
        assertTrue(segments[1] is ComparisonSegment.Block)
    }

    @Test
    fun `fenced fallback removes the block and keeps its position`() {
        val raw = """
            Here is the comparison.

            ```neuron-comparison
            {"type":"pros_cons","pros":["Fast"],"cons":["Pricey"]}
            ```

            Overall it depends on your budget.
        """.trimIndent()

        val fenced = ComparisonBlocksParser.extractFenced(raw)!!

        assertEquals(1, fenced.placements.size)
        assertTrue(fenced.placements.single().block is ComparisonUiBlock.ProsCons)
        assertTrue(fenced.text.startsWith("Here is the comparison."))
        assertTrue(fenced.text.endsWith("Overall it depends on your budget."))
        assertFalse(fenced.text.contains("```"))
        assertTrue(fenced.placements.single().anchor <= fenced.text.length)
    }

    @Test
    fun `plain json fences are not treated as comparison blocks`() {
        val raw = "Example:\n\n```json\n{\"a\": 1}\n```\n"
        assertNull(ComparisonBlocksParser.extractFenced(raw))
    }

    @Test
    fun `streaming guard hides an open fence and shows the closed one`() {
        val partial = "Intro text\n\n```neuron-comparison\n{\"type\":\"score\",\"optionA\":{\"sc"
        val pending = ComparisonBlocksParser.splitPendingStream(partial)
        assertTrue(pending.hasPendingBlock)
        assertEquals("Intro text", pending.visible)

        val complete = "Intro\n\n```neuron-comparison\n{\"type\":\"score\"}\n```\nDone"
        val closed = ComparisonBlocksParser.splitPendingStream(complete)
        assertFalse(closed.hasPendingBlock)
        assertEquals(complete, closed.visible)
    }

    @Test
    fun `placements survive the message metadata round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val metadata = MessageMetadata(
            comparisonBlocks = listOf(
                ComparisonPlacement(
                    anchor = 12,
                    block = ComparisonUiBlock.SideBySide(
                        title = "Phones",
                        optionA = ComparisonOption(
                            label = "Option A",
                            title = "Pixel",
                            imageQuery = "Pixel 9",
                            specs = listOf(ComparisonSpec("RAM", "12 GB"))
                        ),
                        optionB = ComparisonOption(label = "Option B", title = "iPhone")
                    )
                ),
                ComparisonPlacement(
                    anchor = 40,
                    block = ComparisonUiBlock.Score(
                        optionA = ScoreOption("Option A", 9.2),
                        optionB = ScoreOption("Option B", 7.8)
                    )
                )
            )
        )

        val decoded = json.decodeFromString<MessageMetadata>(
            json.encodeToString(MessageMetadata.serializer(), metadata)
        )

        assertEquals(2, decoded.comparisonBlocks.size)
        val side = decoded.comparisonBlocks[0].block as ComparisonUiBlock.SideBySide
        assertEquals("Pixel", side.optionA.title)
        assertEquals("Pixel 9", side.optionA.imageQuery)
        assertEquals(12, decoded.comparisonBlocks[0].anchor)
        val score = decoded.comparisonBlocks[1].block as ComparisonUiBlock.Score
        assertEquals(7.8, score.optionB.score, 0.0001)
    }
}
