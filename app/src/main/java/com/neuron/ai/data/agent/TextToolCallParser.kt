package com.neuron.ai.data.agent

import com.neuron.ai.core.provider.ProposedToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Recovers tool calls the model emitted as PLAIN TEXT instead of using the
 * native function-calling channel. Smaller / quantized / vision models and
 * some OpenAI-compatible gateways do this: they print markup like
 *
 *   <tool_call><function-web.search><parameter-query>Elon Musk net worth 2025</parameter></function></tool_call>
 *   <tool_call>{"name":"web.search","arguments":{"query":"..."}}</tool_call>
 *   <tool_call>{"name":"web.search","parameters":{"query":"..."}}</tool_call>
 *
 * The model has the RIGHT intent — the transport is just wrong. Parsing here
 * keeps tool use working instead of dumping raw markup into the chat bubble.
 *
 * Pure functions, no I/O — trivially unit-testable. SECURITY: only tool ids
 * that exist in the registry ever execute; parsed arguments are treated as
 * untrusted model output exactly like native channel calls.
 */
object TextToolCallParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val BLOCK = Regex("(?s)<tool_call>(.*?)</tool_call>")

    /** One recovered tool call plus the raw text it was extracted from. */
    data class Recovered(
        val call: ProposedToolCall,
        /** The full raw block(s) this call was parsed from — caller strips it. */
        val rawBlocks: List<String>
    )

    /**
     * Scans [text] for every tool-call block it can recover, in order.
     * Returns an empty list when nothing looks like a tool call — the text
     * is then just ordinary assistant output.
     */
    fun parse(text: String): List<Recovered> {
        if (!text.contains("<tool_call>")) return emptyList()
        val recovered = mutableListOf<Recovered>()
        val unclosed = BLOCK.findAll(text).isEmpty()
        val blocks = BLOCK.findAll(text).map { it.groupValues[1] } +
            (if (unclosed) listOf(text.substringAfter("<tool_call>")) else emptyList())

        for (block in blocks) {
            parseBlock(block.trim())?.let { recovered += it }
        }
        return recovered
    }

    /** True when the text contains tool-call markup worth stripping from UI. */
    fun looksLikeToolMarkup(text: String): Boolean = text.contains("<tool_call>")

    /**
     * Removes every tool-call block from [text] so raw markup never reaches
     * the chat bubble. Handles unclosed blocks too. Pure text hygiene — the
     * calls themselves are recovered separately via [parse].
     */
    fun stripToolMarkup(text: String): String {
        if (!looksLikeToolMarkup(text)) return text
        var cleaned = BLOCK.replace(text, " ")
        val opener = cleaned.indexOf("<tool_call>")
        if (opener >= 0) cleaned = cleaned.substring(0, opener)
        return cleaned.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun parseBlock(block: String): Recovered? {
        val asJson = parseJsonBlock(block)
        if (asJson != null) return asJson
        return parseXmlDashBlock(block)
    }

    /** JSON style: {"name":"web.search","arguments":{...}} (or "parameters"). */
    private fun parseJsonBlock(block: String): Recovered? {
        val start = block.indexOf('{')
        if (start < 0) return null
        val obj: JsonObject = runCatching {
            json.parseToJsonElement(block.substring(start)).jsonObject
        }.getOrNull() ?: return null

        val name = (obj["name"] ?: obj["tool"] ?: obj["function"])?.toString()
            ?.trim('"') ?: return null
        val args = (obj["arguments"] ?: obj["parameters"] ?: obj["args"])
            ?.let { runCatching { it.jsonObject }.getOrNull() }
        return Recovered(
            call = ProposedToolCall(
                callId = "text-call-${System.nanoTime()}",
                toolId = name,
                argumentsJson = args?.toString() ?: "{}"
            ),
            rawBlocks = listOf(block)
        )
    }

    /**
     * XML-dash style (the model's actual output):
     *   <function-web.search><parameter-query>Elon Musk net worth 2025</parameter></function>
     * Parameter values are raw text — NOT JSON. When the whole block parses
     * as one JSON object we hand it through; otherwise we build
     * {"param":"value",...} from the <parameter-*> children.
     */
    private fun parseXmlDashBlock(block: String): Recovered? {
        val nameRegex = Regex("<function-([A-Za-z0-9_.\\-]+)>")
        val nameMatch = nameRegex.find(block) ?: return null
        val toolId = nameMatch.groupValues[1]

        val paramRegex = Regex("(?s)<parameter-([A-Za-z0-9_.\\-]+)>(.*?)</parameter>")
        val params = paramRegex.findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

        // Some models emit the parameters as ONE embedded JSON blob.
        val argumentsJson: String = if (params.isEmpty()) {
            val brace = block.indexOf('{')
            if (brace >= 0) block.substring(brace) else "{}"
        } else {
            buildParamsJson(params)
        }

        return Recovered(
            call = ProposedToolCall(
                callId = "text-call-${System.nanoTime()}",
                toolId = toolId,
                argumentsJson = argumentsJson
            ),
            rawBlocks = listOf(block)
        )
    }

    /** Builds {"k":"v",...}; integers/booleans stay quoted — tools coerce. */
    private fun buildParamsJson(params: Map<String, String>): String {
        val escape: (String) -> String = { value ->
            value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
        }
        return params.entries.joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }
    }
}
