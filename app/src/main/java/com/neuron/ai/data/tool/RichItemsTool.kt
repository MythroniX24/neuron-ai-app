package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.conversation.RichItemsParser
import com.neuron.ai.core.permissions.Capability

/**
 * Native rich-card renderer, exposed to the model as a tool.
 *
 * The tool does not "draw" anything: it validates the model's structured JSON
 * and tells it the cards are already on screen, so the final answer can stay a
 * short intro instead of repeating the list as Markdown. The validated payload
 * travels to the ViewModel as an [com.neuron.ai.core.agent.AgentEvent.RichContent]
 * event and is persisted with the assistant message.
 */
class RichItemsTool : Tool {

    override val id = RichItemsParser.TOOL_ID
    override val title = "Preparing recommendations"
    override val description =
        "Render a rich recommendation list as native cards with images, badges, " +
            "descriptions, key facts, status and source links. Use this INSTEAD of " +
            "writing a list in Markdown whenever the user asks for recommendations, " +
            "comparisons or picks of distinct real items (movies, products, books, " +
            "tools, repositories, places, recipes). Provide 1-10 items; every item " +
            "needs a title, and optional fields make the card richer. Use real public " +
            "https image URLs and real source URLs — never invent links. After the tool " +
            "succeeds, reply with only a brief intro or closing note."
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val riskLevel = RiskLevel.SAFE
    override val timeoutMs = 10_000L

    override val parametersSchemaJson = """
        {
          "type": "object",
          "properties": {
            "title": { "type": "string", "description": "Heading above the cards, e.g. Your 3 picks this Friday" },
            "intro": { "type": "string", "description": "One short paragraph introducing the selection" },
            "items": {
              "type": "array",
              "minItems": 1,
              "maxItems": 10,
              "items": {
                "type": "object",
                "properties": {
                  "title": { "type": "string", "description": "Item name" },
                  "year": { "type": "string", "description": "Year, release range or version" },
                  "meta": { "type": "string", "description": "Short category line, e.g. Animated series · 7 seasons" },
                  "description": { "type": "string", "description": "Brief overview" },
                  "badge": { "type": "string", "description": "Optional pill, e.g. Best match for your tastes" },
                  "imageUrl": { "type": "string", "description": "Public https image/poster URL" },
                  "details": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "label": { "type": "string" },
                        "value": { "type": "string" }
                      },
                      "required": ["label", "value"]
                    }
                  },
                  "status": { "type": "string", "description": "Availability / confirmation line" },
                  "statusTone": { "type": "string", "enum": ["positive", "warning", "neutral"] },
                  "links": {
                    "type": "array",
                    "items": {
                      "type": "object",
                      "properties": {
                        "label": { "type": "string" },
                        "url": { "type": "string" }
                      },
                      "required": ["label", "url"]
                    }
                  }
                },
                "required": ["title"]
              }
            }
          },
          "required": ["title", "items"]
        }
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): ToolResult {
        val set = RichItemsParser.parse(argumentsJson)
            ?: return ToolResult.Failure(
                "Invalid rich recommendation payload: title and at least one item with a title are required. " +
                    "Fix the JSON and call ui.rich_items again."
            )
        return ToolResult.Success(
            "Rendered ${set.items.size} recommendation card(s): ${set.title}. " +
                "The cards are already visible to the user. Do not repeat the list in Markdown; " +
                "reply with only a brief intro or closing note."
        )
    }
}
