package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.conversation.ComparisonBlocksParser
import com.neuron.ai.core.permissions.Capability

/**
 * Native comparison-surface renderer, exposed to the model as a tool.
 *
 * The tool draws nothing itself: it validates the model's structured JSON and
 * tells it the cards are already on screen, so the answer can stay a short
 * intro instead of repeating every fact as Markdown. The validated blocks
 * travel to the ViewModel as an [com.neuron.ai.core.agent.AgentEvent.ComparisonBlocks]
 * event (each anchored to the text written so far) and persist with the
 * assistant message.
 */
class ComparisonTool : Tool {

    override val id = ComparisonBlocksParser.TOOL_ID
    override val title = "Building comparison"
    override val description =
        "Render a native comparison surface for ANY topic (phones, apps, AI models, " +
            "plans, tools, services, places) instead of a Markdown list or table. " +
            "Send 1-6 blocks; each block is one visual section and they render in order, " +
            "interleaved with the text you write. Types: " +
            "\"side_by_side\" (two cards with image, pill label and key facts; give each " +
            "option a title plus either a real public https imageUrl or an imageQuery " +
            "search phrase — never invent image URLs), " +
            "\"feature_table\" (rows of feature / a / b), " +
            "\"score\" (two scores out of a max; the higher one is highlighted as Winner), " +
            "\"pros_cons\" (pros and cons lists), " +
            "\"priority\" (interactive radio choices where each priority maps to your " +
            "tailored recommendation). Use the user's language for all copy. After the " +
            "tool succeeds, do not repeat the comparison in Markdown — add at most a " +
            "short takeaway."
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val riskLevel = RiskLevel.SAFE
    override val timeoutMs = 10_000L

    override val parametersSchemaJson = """
        {
          "type": "object",
          "properties": {
            "blocks": {
              "type": "array",
              "minItems": 1,
              "maxItems": 6,
              "description": "Comparison sections, rendered in this order.",
              "items": {
                "type": "object",
                "properties": {
                  "type": {
                    "type": "string",
                    "enum": ["side_by_side", "feature_table", "score", "pros_cons", "priority"]
                  },
                  "title": { "type": "string", "description": "Optional heading for this section" },
                  "optionA": {
                    "type": "object",
                    "description": "side_by_side: left card. score: left score card.",
                    "properties": {
                      "label": { "type": "string", "description": "Pill text, e.g. Option A" },
                      "title": { "type": "string", "description": "Item name" },
                      "subtitle": { "type": "string", "description": "Short category/meta line" },
                      "imageUrl": { "type": "string", "description": "Verified public https image URL" },
                      "imageQuery": { "type": "string", "description": "Search phrase used to look up a real image when imageUrl is unknown" },
                      "specs": {
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
                      "score": { "type": "number", "description": "score blocks: this option's score" },
                      "maxScore": { "type": "number", "description": "score blocks: maximum, default 10" }
                    }
                  },
                  "optionB": {
                    "type": "object",
                    "description": "side_by_side / score: right card, same fields as optionA"
                  },
                  "optionALabel": { "type": "string", "description": "feature_table: column A header" },
                  "optionBLabel": { "type": "string", "description": "feature_table: column B header" },
                  "rows": {
                    "type": "array",
                    "description": "feature_table rows",
                    "items": {
                      "type": "object",
                      "properties": {
                        "feature": { "type": "string" },
                        "a": { "type": "string" },
                        "b": { "type": "string" }
                      },
                      "required": ["feature", "a", "b"]
                    }
                  },
                  "metric": { "type": "string", "description": "score: label under the number, e.g. Overall score" },
                  "pros": {
                    "type": "array",
                    "description": "pros_cons advantages",
                    "items": { "type": "string" }
                  },
                  "cons": {
                    "type": "array",
                    "description": "pros_cons drawbacks",
                    "items": { "type": "string" }
                  },
                  "prosTitle": { "type": "string" },
                  "consTitle": { "type": "string" },
                  "priorities": {
                    "type": "array",
                    "description": "priority: interactive choices with your recommendation for each",
                    "items": {
                      "type": "object",
                      "properties": {
                        "label": { "type": "string", "description": "e.g. Performance, Camera, Battery, Price" },
                        "recommendation": { "type": "string", "description": "Which option wins for this priority and why" }
                      },
                      "required": ["label", "recommendation"]
                    }
                  }
                },
                "required": ["type"]
              }
            }
          },
          "required": ["blocks"]
        }
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): ToolResult {
        val blocks = ComparisonBlocksParser.parse(argumentsJson)
        if (blocks.isEmpty()) {
            return ToolResult.Failure(
                "Invalid comparison payload: send {\"blocks\": [ … ]} where every block has a " +
                    "supported \"type\" (side_by_side, feature_table, score, pros_cons, priority) " +
                    "and the fields that type needs. Fix the JSON and call ui.compare again."
            )
        }
        val kinds = blocks.joinToString(", ") { it::class.simpleName.orEmpty() }
        return ToolResult.Success(
            "Rendered ${blocks.size} comparison block(s) [$kinds]. They are already visible " +
                "to the user, in the order sent and interleaved with your text. Do not repeat " +
                "the same facts as a Markdown list or table; reply with at most a short takeaway."
        )
    }
}
