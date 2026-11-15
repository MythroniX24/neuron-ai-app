package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.settings.ReplySpacingProfile
import com.neuron.ai.core.settings.ResponseSpacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the model control how airy its own reply looks instead of asking the
 * user to tune a global text setting. Paragraphs and lists are independent:
 * a dense list can stay compact while the explanation around it breathes.
 * The validated profile is emitted as an
 * [com.neuron.ai.core.agent.AgentEvent.ReplySpacing] event and stored on the
 * assistant message.
 */
class ReplySpacingTool : Tool {

    override val id = TOOL_ID
    override val title = "Adjusting reply spacing"
    override val description =
        "Choose line spacing separately for prose paragraphs and lists so the reply " +
            "reads cleanly. Call once per answer before the final text: compact for " +
            "dense lists and short facts, comfortable for normal chat, relaxed for " +
            "explanations, airy for long walkthroughs. Do not mention this tool to the user."
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val riskLevel = RiskLevel.SAFE
    override val timeoutMs = 10_000L

    override val parametersSchemaJson = """
        {
          "type": "object",
          "properties": {
            "paragraph": {
              "type": "string",
              "enum": ["compact", "comfortable", "relaxed", "airy"],
              "description": "Line spacing for normal prose paragraphs and quotes"
            },
            "list": {
              "type": "string",
              "enum": ["compact", "comfortable", "relaxed", "airy"],
              "description": "Line spacing inside bullet and numbered lists"
            }
          },
          "required": ["paragraph", "list"]
        }
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): ToolResult {
        val profile = profileFrom(argumentsJson)
            ?: return ToolResult.Failure(
                "Invalid reply spacing. Choose compact, comfortable, relaxed or airy " +
                    "for both paragraph and list. Call ui.reply_spacing again."
            )
        return ToolResult.Success(
            "Reply spacing set: paragraphs ${profile.paragraph.label.lowercase()}, " +
                "lists ${profile.list.label.lowercase()}. The UI applies these to the " +
                "matching blocks. Continue with the final response and do not mention this tool."
        )
    }

    companion object {
        const val TOOL_ID = "ui.reply_spacing"

        /**
         * Tolerant parser: accepts the new paragraph/list fields, and treats an
         * old single `spacing` value as the default for both.
         */
        fun profileFrom(argumentsJson: String): ReplySpacingProfile? {
            val obj = runCatching {
                Json.parseToJsonElement(argumentsJson).jsonObject
            }.getOrNull() ?: return null
            val legacy = nameOf(obj, "spacing")
            val paragraph = nameOf(obj, "paragraph") ?: legacy
            val list = nameOf(obj, "list") ?: legacy
            // An invalid explicit value must fail instead of silently
            // falling back and hiding the model's mistake.
            if (obj["paragraph"] != null && nameOf(obj, "paragraph") == null) return null
            if (obj["list"] != null && nameOf(obj, "list") == null) return null
            if (obj["spacing"] != null && legacy == null) return null
            if (paragraph == null && list == null) return null
            return ReplySpacingProfile.fromNames(
                paragraph = paragraph,
                list = list,
                legacy = legacy
            )
        }

        private fun nameOf(obj: JsonObject, key: String): String? =
            runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()
                ?.trim()
                ?.lowercase()
                ?.let { raw ->
                    when (raw) {
                        "default", "normal", "medium" -> ResponseSpacing.COMFORTABLE.name
                        else -> ResponseSpacing.fromNameOrNull(raw)?.name
                    }
                }
    }
}
