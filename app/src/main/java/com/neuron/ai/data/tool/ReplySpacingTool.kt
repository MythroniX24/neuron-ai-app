package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.settings.ResponseSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the model control how airy its own reply looks instead of asking the
 * user to tune a global text setting. The chosen spacing is emitted as an
 * [com.neuron.ai.core.agent.AgentEvent.ReplySpacing] event and stored on the
 * assistant message, so each answer keeps the density the model picked.
 */
class ReplySpacingTool : Tool {

    override val id = TOOL_ID
    override val title = "Adjusting reply spacing"
    override val description =
        "Choose the line spacing for your reply so it reads cleanly. Call this once " +
            "per answer before the final text: compact for short factual answers, " +
            "comfortable for normal conversation, relaxed for explanations, airy for " +
            "long step-by-step guides. Do not mention the tool to the user."
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val riskLevel = RiskLevel.SAFE
    override val timeoutMs = 10_000L

    override val parametersSchemaJson = """
        {
          "type": "object",
          "properties": {
            "spacing": {
              "type": "string",
              "enum": ["compact", "comfortable", "relaxed", "airy"],
              "description": "How much vertical space every line of the reply should have"
            },
            "reason": {
              "type": "string",
              "description": "Optional one-line reason, e.g. long walkthrough"
            }
          },
          "required": ["spacing"]
        }
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): ToolResult {
        val spacing = spacingFrom(argumentsJson)
            ?: return ToolResult.Failure(
                "Invalid reply spacing. Use one of: compact, comfortable, relaxed, airy. " +
                    "Call ui.reply_spacing again with a valid choice."
            )
        return ToolResult.Success(
            "Reply spacing set to ${spacing.label}. The UI applies it to every line of " +
                "this answer. Continue with the final response and do not mention this tool."
        )
    }

    companion object {
        const val TOOL_ID = "ui.reply_spacing"

        fun spacingFrom(argumentsJson: String): ResponseSpacing? {
            val raw = runCatching {
                Json.parseToJsonElement(argumentsJson).jsonObject["spacing"]
                    ?.jsonPrimitive?.content
            }.getOrNull() ?: return null
            return when (raw.trim().lowercase()) {
                "default", "normal", "medium" -> ResponseSpacing.COMFORTABLE
                else -> ResponseSpacing.fromNameOrNull(raw)
            }
        }
    }
}
