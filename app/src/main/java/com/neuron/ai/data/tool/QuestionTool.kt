package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.conversation.QuestionCardParser
import com.neuron.ai.core.permissions.Capability

/**
 * Interactive question tool: allows the AI to present 1 or more multiple-choice
 * questions as interactive cards with tappable numbered options and custom text input,
 * instead of asking plain text in the chat bubble.
 */
class QuestionTool : Tool {

    override val id = QuestionCardParser.TOOL_ID
    override val title = "Asking interactive question"
    override val description =
        "Ask the user 1 or more interactive multiple-choice questions with tappable " +
            "numbered option buttons and custom text input instead of plain text in chat. " +
            "Use this whenever asking the user to pick an option (project type, design preference, " +
            "tech stack, choices, settings, confirmation). Provide 2-6 options per question; " +
            "the user can tap an option or type their own custom answer."
    override val requiredCapabilities: Set<Capability> = emptySet()
    override val riskLevel = RiskLevel.SAFE
    override val timeoutMs = 10_000L

    override val parametersSchemaJson = """
        {
          "type": "object",
          "properties": {
            "title": { "type": "string", "description": "Optional title or context for the questions" },
            "questions": {
              "type": "array",
              "minItems": 1,
              "maxItems": 5,
              "description": "List of questions to ask sequentially or in a multi-step card",
              "items": {
                "type": "object",
                "properties": {
                  "question": { "type": "string", "description": "The question to ask, e.g. Kaunsa project pe kaam karna hai?" },
                  "options": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "List of 2 to 6 distinct selectable options"
                  },
                  "allowCustom": { "type": "boolean", "description": "Whether to allow user to type custom answer (default true)" }
                },
                "required": ["question", "options"]
              }
            }
          },
          "required": ["questions"]
        }
    """.trimIndent()

    override suspend fun execute(argumentsJson: String): ToolResult {
        val payload = QuestionCardParser.parse(argumentsJson)
            ?: return ToolResult.Failure(
                "Invalid question payload: at least one question with options is required. " +
                    "Fix the JSON and call ui.ask_question again."
            )
        return ToolResult.Success(
            "Presented ${payload.questions.size} interactive question(s) to the user. " +
                "The options card is now displayed in the chat. Awaiting user selection."
        )
    }
}
