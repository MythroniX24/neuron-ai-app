package com.neuron.ai.core.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object QuestionCardParser {

    const val TOOL_ID = "ui.ask_question"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses the tool arguments JSON into a [QuestionCardPayload].
     */
    fun parse(argumentsJson: String): QuestionCardPayload? {
        val root = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() ?: return null
        return when (root) {
            is JsonObject -> parseObject(root)
            is JsonArray -> {
                val questions = root.mapNotNull { parseQuestion(it) }
                if (questions.isNotEmpty()) QuestionCardPayload(questions = questions) else null
            }
            else -> null
        }
    }

    private fun parseObject(obj: JsonObject): QuestionCardPayload? {
        val title = obj["title"]?.jsonPrimitive?.content
        val questionsElem = obj["questions"]
        if (questionsElem is JsonArray) {
            val list = questionsElem.mapNotNull { parseQuestion(it) }
            if (list.isNotEmpty()) {
                return QuestionCardPayload(title = title, questions = list)
            }
        }
        // Direct single question at root: {"question": "...", "options": [...]}
        val single = parseQuestion(obj)
        return if (single != null) {
            QuestionCardPayload(title = title, questions = listOf(single))
        } else {
            null
        }
    }

    private fun parseQuestion(element: JsonElement): InteractiveQuestion? {
        val obj = element as? JsonObject ?: return null
        val question = obj["question"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: obj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val optionsList = mutableListOf<String>()
        val optionsElem = obj["options"]
        if (optionsElem is JsonArray) {
            for (item in optionsElem) {
                val opt = (item as? JsonObject)?.get("label")?.jsonPrimitive?.content
                    ?: item.jsonPrimitive.content
                if (opt.isNotBlank()) optionsList.add(opt)
            }
        }
        val allowCustom = obj["allowCustom"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val header = obj["header"]?.jsonPrimitive?.content
        return if (optionsList.isNotEmpty() || allowCustom) {
            InteractiveQuestion(
                question = question,
                options = optionsList,
                allowCustom = allowCustom,
                header = header
            )
        } else {
            null
        }
    }

    /**
     * Scans markdown prose for a fenced ```question or ```ask_question block
     * (used as fallback when models output question cards directly in text).
     */
    fun parseFromText(text: String): QuestionCardPayload? {
        val fenceRegex = Regex("""```(?:question|ask_question)\s*([\s\S]*?)```""")
        val match = fenceRegex.find(text) ?: return null
        val body = match.groupValues[1].trim()
        return parse(body)
    }

    /**
     * Extracts a fenced question block and returns the payload paired with the
     * remaining visible text (with the fenced block removed).
     */
    fun extractFenced(text: String): Pair<QuestionCardPayload, String>? {
        if (text.isBlank()) return null
        val fence = Regex("""```(?:question|ask_question)\s*([\s\S]*?)```""")
        fence.findAll(text).forEach { match ->
            val body = match.groupValues.getOrNull(1).orEmpty()
            val payload = parse(body) ?: return@forEach
            val visible = text.removeRange(match.range).trim()
            return payload to visible
        }
        return null
    }
}
