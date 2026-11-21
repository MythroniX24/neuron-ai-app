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
     * Extracts a fenced question block (or XML tag or JSON block) and returns the payload paired with the
     * remaining visible text (with the fenced block removed).
     */
    fun extractFenced(text: String): Pair<QuestionCardPayload, String>? {
        if (text.isBlank()) return null

        // 1. Standard markdown fence ```question or ```ask_question
        val fence = Regex("""```(?:question|ask_question)\s*([\s\S]*?)```""")
        fence.findAll(text).forEach { match ->
            val body = match.groupValues.getOrNull(1).orEmpty()
            val payload = parse(body) ?: return@forEach
            val visible = text.removeRange(match.range).trim()
            return payload to visible
        }

        // 2. XML tags <question>...</question> or <ask_question>...</ask_question> or <interactive_question>...</interactive_question>
        val xmlRegex = Regex("""<(?:question|ask_question|interactive_question)>\s*([\s\S]*?)\s*</(?:question|ask_question|interactive_question)>""")
        xmlRegex.findAll(text).forEach { match ->
            val body = match.groupValues.getOrNull(1).orEmpty()
            val payload = parse(body) ?: return@forEach
            val visible = text.removeRange(match.range).trim()
            return payload to visible
        }

        // 3. Fenced json ```json with "questions" or "question"
        val jsonFence = Regex("""```(?:json)?\s*(\{\s*"(?:questions|question)"[\s\S]*?\})\s*```""")
        jsonFence.findAll(text).forEach { match ->
            val body = match.groupValues.getOrNull(1).orEmpty()
            val payload = parse(body) ?: return@forEach
            val visible = text.removeRange(match.range).trim()
            return payload to visible
        }

        return null
    }

    /**
     * Heuristically detects if the trailing part of the AI's response is asking a multi-choice
     * question in natural language (numbered/bulleted list or parenthetical choices), and
     * promotes it to an interactive question card so the user gets tappable options.
     */
    fun extractFromProse(text: String): Pair<QuestionCardPayload, String>? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        val lines = trimmed.lines()
        if (lines.isEmpty()) return null

        // Case A: Trailing list of numbered or bulleted options (e.g. 1. / 2. / - / * / •)
        val itemRegex = Regex("""^\s*(?:[1-9]\.?|[1-9]\)|[-*•])\s+(.+)$""")
        val trailingItems = mutableListOf<String>()
        var itemStartIndex = lines.size

        for (idx in lines.indices.reversed()) {
            val line = lines[idx].trim()
            if (line.isBlank()) {
                if (trailingItems.isNotEmpty()) continue else continue
            }
            val match = itemRegex.find(line)
            if (match != null) {
                trailingItems.add(0, match.groupValues[1].trim())
                itemStartIndex = idx
                if (trailingItems.size > 6) break
            } else {
                break
            }
        }

        if (trailingItems.size in 2..6 && itemStartIndex > 0) {
            var leadInLine = ""
            for (idx in (itemStartIndex - 1) downTo 0) {
                val line = lines[idx].trim()
                if (line.isNotBlank()) {
                    leadInLine = line
                    break
                }
            }

            val looksLikeQuestion = leadInLine.endsWith("?") ||
                leadInLine.endsWith(":") ||
                listOf("choose", "select", "which", "option", "prefer", "next step", "would you like",
                       "बताएं", "चुनें", "विकल्प", "कदम", "क्या आप", "पसंद").any {
                    leadInLine.contains(it, ignoreCase = true)
                }

            if (looksLikeQuestion && leadInLine.isNotBlank()) {
                val cleanQuestion = leadInLine.trimEnd(':').trim()
                val question = InteractiveQuestion(
                    question = cleanQuestion,
                    options = trailingItems,
                    allowCustom = true
                )
                val payload = QuestionCardPayload(questions = listOf(question))
                val remainingLines = lines.take(itemStartIndex).joinToString("\n").trim()
                return payload to remainingLines
            }
        }

        // Case B: Trailing sentence with parenthetical choices like (जैसे 10 ग्राम, 50 ग्राम या 1 किलो)
        // or (e.g. option A, option B, or option C)
        val parentheticalRegex = Regex("""\((?:जैसे|e\.g\.|for example|such as|like)\s*[:]?\s*([^)]+)\)""")
        val lastSentence = lines.lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        val match = parentheticalRegex.find(lastSentence)
        if (match != null) {
            val inside = match.groupValues[1].trim()
            val rawTokens = inside.split(Regex(""",|\s+या\s+|\s+or\s+|\/"""))
                .map { it.trim().replace(Regex("""^[1-9][0-9]?[.)]\s+"""), "").trim() }
                .filter { it.length in 2..40 }

            if (rawTokens.size in 2..6) {
                val questionText = lastSentence.replace(match.value, "").replace(Regex("""\s+"""), " ").trim()
                val question = InteractiveQuestion(
                    question = if (questionText.isNotBlank()) questionText else "विकल्प चुनें (Select an option):",
                    options = rawTokens,
                    allowCustom = true
                )
                val payload = QuestionCardPayload(questions = listOf(question))
                val remaining = trimmed.removeSuffix(lastSentence).trim()
                return payload to (if (remaining.isNotBlank()) remaining else lastSentence)
            }
        }

        return null
    }
}
