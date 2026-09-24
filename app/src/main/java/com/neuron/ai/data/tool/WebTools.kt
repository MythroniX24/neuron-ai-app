package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.core.web.PageFetcher
import com.neuron.ai.core.web.SearchProvider
import com.neuron.ai.core.web.SearchQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Milestone 3 web tools. WebSearchTool runs the full result pipeline
 * (dedup → rank → select → optional page reads) and returns compact JSON the
 * model can cite. Sources are returned WITH the result and persisted as TOOL
 * messages — citations always stay attached to the response that used them.
 */
object WebTools {

    private val json = Json { ignoreUnknownKeys = true }

    private fun arg(args: String, key: String): String? = runCatching {
        json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    // ---- web.search ------------------------------------------------------------------------
    class WebSearch(
        private val searchProvider: SearchProvider,
        private val pageFetcher: PageFetcher,
        private val openPages: Int = 2
    ) : Tool {

        override val id = "web.search"
        override val title = "Searching web"
        override val description =
            "Searches the public web for current information. Returns ranked results " +
                "with title, url and snippet; optionally opens the top pages to extract " +
                "content for cross-checking. Cite sources by their [n] index in the answer."
        override val requiredCapabilities = setOf(Capability.NETWORK)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 30_000L
        override val parametersSchemaJson =
            """{"type":"object","properties":{"query":{"type":"string"},"maxResults":{"type":"integer"},"openPages":{"type":"integer"},"recencyDays":{"type":"integer"}},"required":["query"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val queryText = arg(argumentsJson, "query")
                ?: return ToolResult.Failure("Missing required argument: query")
            val maxResults = arg(argumentsJson, "maxResults")?.toIntOrNull()?.coerceIn(1, 10) ?: 5
            val openPages = arg(argumentsJson, "openPages")?.toIntOrNull()
                ?.coerceIn(0, 3) ?: this.openPages
            val recencyDays = arg(argumentsJson, "recencyDays")?.toIntOrNull()?.coerceIn(1, 365)

            val response = searchProvider.search(
                SearchQuery(
                    text = queryText,
                    maxResults = maxResults,
                    recencyDays = recencyDays
                )
            )
            val results = when (response) {
                is com.neuron.ai.core.web.SearchResponse.Success -> response.results
                is com.neuron.ai.core.web.SearchResponse.Failure ->
                    return ToolResult.Failure(response.message)
            }
            if (results.isEmpty()) {
                return ToolResult.Failure("No results found for \"$queryText\".")
            }

            // Pipeline: results are already deduped + ranked by the provider.
            // Select the top slice, then open a FEW pages for cross-checking —
            // never every hit, bounded per-page chars.
            val selected = results.take(maxResults)
            val opened = mutableListOf<String>()
            selected.take(openPages).forEach { result ->
                val page = pageFetcher.fetch(result.url, maxChars = 2_500) ?: return@forEach
                opened += "SOURCE ${result.position} — ${page.title ?: result.title}\n${page.text.take(1_800)}"
            }

            val sb = StringBuilder()
            sb.append("SOURCES:\n")
            selected.forEach { r ->
                sb.append("[${r.position}] ${r.title}\n    ${r.url}\n")
                r.snippet?.let { sb.append("    ${it.take(200)}\n") }
            }
            if (opened.isNotEmpty()) {
                sb.append("\nPAGE CONTENT:\n")
                opened.forEach { sb.append(it).append("\n\n") }
            }
            sb.append(
                "Citation rule: cite sources as [n] matching the indices above; " +
                    "never cite a page that was not opened or listed."
            )
            return ToolResult.Success(sb.toString())
        }
    }

    // ---- web.read ------------------------------------------------------------------------------
    class WebRead(
        private val pageFetcher: PageFetcher
    ) : Tool {

        override val id = "web.read"
        override val title = "Reading page"
        override val description =
            "Fetches a URL and extracts its readable text (bounded). Use after web.search " +
                "to read a specific source. Returns the page title and text."
        override val requiredCapabilities = setOf(Capability.NETWORK)
        override val riskLevel = RiskLevel.SAFE
        override val timeoutMs = 30_000L
        override val parametersSchemaJson =
            """{"type":"object","properties":{"url":{"type":"string"},"maxChars":{"type":"integer"}},"required":["url"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val url = arg(argumentsJson, "url")
                ?: return ToolResult.Failure("Missing required argument: url")
            if (!Regex("(?i)^https?://").containsMatchIn(url)) {
                return ToolResult.Failure("Only http(s) URLs are supported.")
            }
            val maxChars = arg(argumentsJson, "maxChars")?.toIntOrNull()?.coerceIn(500, 12_000) ?: 8_000
            val page = pageFetcher.fetch(url, maxChars)
                ?: return ToolResult.Failure(
                    "Could not read the page (unavailable, blocked, or not text content)."
                )
            return ToolResult.Success(
                "TITLE: ${page.title ?: "(untitled)"}\nURL: ${page.url}\n\n${page.text}"
            )
        }
    }
}
