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
 * Deliberately REGEX-FREE: scanning is plain indexOf/substring — deterministic
 * across JVM/ART versions and trivially unit-testable. SECURITY: parsed ids
 * flow through the normal executor (unregistered ids yield "Unknown tool");
 * parsed arguments are untrusted model output exactly like native calls.
 */
object TextToolCallParser {

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"
    private const val FN_TAG = "<function-"
    private const val PARAM_TAG = "<parameter-"
    private const val PARAM_CLOSE = "</parameter>"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** One recovered tool call plus the raw text it was extracted from. */
    data class Recovered(
        val call: ProposedToolCall,
        /** The full raw block(s) this call was parsed from — caller strips it. */
        val rawBlocks: List<String>
    )

    /** True when the text contains tool-call markup worth stripping from UI. */
    fun looksLikeToolMarkup(text: String): Boolean = text.contains(OPEN)

    /**
     * Scans [text] for every tool-call block it can recover, in order.
     * Unclosed trailing blocks are honored (models truncate sometimes).
     * Returns an empty list when nothing parses — the text is then just
     * ordinary assistant output.
     */
    fun parse(text: String): List<Recovered> {
        if (!looksLikeToolMarkup(text)) return emptyList()
        val out = mutableListOf<Recovered>()
        var idx = text.indexOf(OPEN)
        while (idx >= 0) {
            val end = text.indexOf(CLOSE, idx + OPEN.length)
            val blockEnd = if (end >= 0) end else text.length
            parseBlock(text.substring(idx + OPEN.length, blockEnd).trim())?.let { out += it }
            if (end < 0) break
            idx = text.indexOf(OPEN, end + CLOSE.length)
        }
        return out
    }

    /**
     * Removes every tool-call block from [text] so raw markup never reaches
     * the chat bubble. Handles unclosed trailing blocks too.
     */
    fun stripToolMarkup(text: String): String {
        if (!looksLikeToolMarkup(text)) return text
        val sb = StringBuilder()
        var cursor = 0
        var idx = text.indexOf(OPEN)
        while (idx >= 0) {
            sb.append(text, cursor, idx)
            val end = text.indexOf(CLOSE, idx + OPEN.length)
            if (end < 0) {
                cursor = text.length
                break
            }
            cursor = end + CLOSE.length
            idx = text.indexOf(OPEN, cursor)
        }
        sb.append(text.substring(cursor))
        var out = sb.toString()
        while (out.contains("\n\n\n")) out = out.replace("\n\n\n", "\n\n")
        return out.trim()
    }

    // ---- block parsing ---------------------------------------------------------------------

    private fun parseBlock(block: String): Recovered? {
        parseJsonBlock(block)?.let { return it }
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
     * Parameter values are raw text — NOT JSON; they are assembled into a
     * JSON object. When no parameter tags exist, an embedded JSON blob after
     * the first '{' is handed through as-is.
     */
    private fun parseXmlDashBlock(block: String): Recovered? {
        val fnStart = block.indexOf(FN_TAG)
        if (fnStart < 0) return null
        val nameStart = fnStart + FN_TAG.length
        val nameEnd = block.indexOf('>', nameStart)
        if (nameEnd < 0) return null
        val toolId = block.substring(nameStart, nameEnd).trim()
        if (toolId.isEmpty()) return null

        val params = LinkedHashMap<String, String>()
        var i = block.indexOf(PARAM_TAG)
        while (i >= 0) {
            val keyStart = i + PARAM_TAG.length
            val keyEnd = block.indexOf('>', keyStart)
            if (keyEnd < 0) break
            val key = block.substring(keyStart, keyEnd).trim()
            // Models close parameters BOTH ways: "</parameter>" and the
            // named form "</parameter-query>". Accept whichever comes first.
            val plainEnd = block.indexOf(PARAM_CLOSE, keyEnd + 1)
            val namedCloser = "</parameter-$key>"
            val namedEnd = if (key.isNotEmpty()) block.indexOf(namedCloser, keyEnd + 1) else -1
            val (valEnd, closerLen) = when {
                plainEnd >= 0 && (namedEnd < 0 || plainEnd < namedEnd) ->
                    plainEnd to PARAM_CLOSE.length
                namedEnd >= 0 -> namedEnd to namedCloser.length
                else -> break
            }
            if (key.isNotEmpty()) {
                params[key] = block.substring(keyEnd + 1, valEnd).trim()
            }
            i = block.indexOf(PARAM_TAG, valEnd + closerLen)
        }

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
