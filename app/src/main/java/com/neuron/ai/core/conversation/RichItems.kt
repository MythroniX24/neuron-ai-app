package com.neuron.ai.core.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured recommendation payload rendered by the native "Rich Item Card"
 * UI. Deliberately entity-agnostic: movies, products, books, tools, repos,
 * places or recipes all use the same shape.
 */
@Serializable
data class RichRecommendationSet(
    val title: String,
    val intro: String = "",
    val items: List<RichItem> = emptyList()
)

@Serializable
data class RichItem(
    val title: String,
    val year: String? = null,
    val meta: String? = null,
    val description: String? = null,
    val badge: String? = null,
    val imageUrl: String? = null,
    val details: List<RichItemDetail> = emptyList(),
    val status: String? = null,
    /** positive | warning | neutral — drives the status color only. */
    val statusTone: String = "positive",
    val links: List<RichItemLink> = emptyList()
)

@Serializable
data class RichItemDetail(val label: String, val value: String)

@Serializable
data class RichItemLink(val label: String, val url: String)

/**
 * Tolerant parser for model output. The model may use synonyms ("name",
 * "overview", "features"…) and may omit optional fields; only a title and at
 * least one item are required. All strings are length-capped and links are
 * restricted to http(s), so malformed or hostile JSON cannot inject URLs into
 * the native UI.
 *
 * Pure Kotlin: unit-tested without Android or a network.
 */
object RichItemsParser {

    const val TOOL_ID = "ui.rich_items"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String): RichRecommendationSet? = runCatching {
        parseElement(json.parseToJsonElement(raw))
    }.getOrNull()

    /**
     * Finds fenced JSON blocks containing recommendation items and returns
     * the parsed set together with the visible text with those blocks removed.
     * Used as a fallback for models that output recommendation cards directly in markdown.
     */
    fun extractFenced(text: String): Pair<RichRecommendationSet, String>? {
        if (text.isBlank()) return null
        val matches = FENCE.findAll(text).toList()
        if (matches.isEmpty()) return null
        var foundSet: RichRecommendationSet? = null
        var cleanedText = text
        for (match in matches.reversed()) {
            val body = match.groupValues.getOrNull(1).orEmpty()
            val set = parse(body)
            if (set != null) {
                if (foundSet == null) {
                    foundSet = set
                }
                cleanedText = cleanedText.removeRange(match.range).trim()
            }
        }
        return if (foundSet != null) foundSet to cleanedText else null
    }

    private val FENCE = Regex("""```(?:json|rich_items|rich-items|neuron-rich-items|ui\.rich_items)?\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE)

    private fun parseElement(element: JsonElement): RichRecommendationSet? {
        // A bare array is accepted as a recommendation list.
        if (element is JsonArray) {
            val items = parseItems(element)
            return if (items.isEmpty()) null else {
                RichRecommendationSet(title = "Recommendations", items = items)
            }
        }
        val obj = element as? JsonObject ?: return null
        val array = listOf("items", "recommendations", "results", "picks")
            .firstNotNullOfOrNull { key -> obj[key] as? JsonArray }
            ?: return null
        val items = parseItems(array)
        if (items.isEmpty()) return null
        val title = text(obj, "title", "headline", "heading")
            ?: "Recommendations"
        return RichRecommendationSet(
            title = title.take(160),
            intro = text(obj, "intro", "introduction", "summary", "description")
                ?.take(1_200)
                .orEmpty(),
            items = items
        )
    }

    private fun parseItems(array: JsonArray): List<RichItem> =
        array.mapNotNull { element -> parseItem(element as? JsonObject ?: return@mapNotNull null) }
            .take(MAX_ITEMS)

    private fun parseItem(obj: JsonObject): RichItem? {
        val title = text(obj, "title", "name", "label") ?: return null
        if (title.isBlank()) return null
        return RichItem(
            title = title.take(160),
            year = text(obj, "year", "releaseYear", "version", "date")?.take(40),
            meta = text(obj, "meta", "subtitle", "category", "categories", "type")
                ?.take(240),
            description = text(obj, "description", "overview", "summary", "details")
                ?.take(1_500),
            badge = text(obj, "badge", "reason", "why", "match", "bestFor")?.take(100),
            imageUrl = text(obj, "imageUrl", "image_url", "image", "thumbnail", "poster", "cover")
                ?.let(::safeUrl),
            details = parseDetails(obj),
            status = text(obj, "status", "availability", "note", "statusText")?.take(160),
            statusTone = when (text(obj, "statusTone", "tone")?.lowercase()) {
                "warning", "warn", "caution" -> "warning"
                "neutral", "info" -> "neutral"
                else -> "positive"
            },
            links = parseLinks(obj)
        )
    }

    private fun parseDetails(obj: JsonObject): List<RichItemDetail> {
        val element = obj["details"] ?: obj["features"] ?: obj["facts"] ?: obj["specs"]
            ?: return emptyList()
        val details = when (element) {
            is JsonArray -> element.mapNotNull { entry ->
                val entryObj = entry as? JsonObject ?: return@mapNotNull null
                val label = text(entryObj, "label", "key", "name") ?: return@mapNotNull null
                val value = text(entryObj, "value", "text", "detail") ?: return@mapNotNull null
                RichItemDetail(label.take(80), value.take(320))
            }
            is JsonObject -> element.entries.mapNotNull { (key, value) ->
                val textValue = (value as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: return@mapNotNull null
                if (textValue.isBlank()) null
                else RichItemDetail(key.take(80), textValue.take(320))
            }
            else -> emptyList()
        }
        return details.take(MAX_DETAILS)
    }

    private fun parseLinks(obj: JsonObject): List<RichItemLink> {
        val element = obj["links"] ?: obj["actions"] ?: obj["sources"]
            ?: return emptyList()
        val links = when (element) {
            is JsonArray -> element.mapNotNull { entry ->
                val entryObj = entry as? JsonObject ?: return@mapNotNull null
                val url = text(entryObj, "url", "href", "link")?.let(::safeUrl)
                    ?: return@mapNotNull null
                val label = text(entryObj, "label", "title", "name")?.take(100)
                    ?: url.substringAfterLast('/').ifBlank { "Open link" }
                RichItemLink(label, url)
            }
            is JsonObject -> element.entries.mapNotNull { (label, value) ->
                val url = (value as? JsonPrimitive)?.contentOrNull?.let(::safeUrl)
                    ?: return@mapNotNull null
                RichItemLink(label.take(100), url)
            }
            else -> emptyList()
        }
        return links.distinctBy { it.url }.take(MAX_LINKS)
    }

    private fun text(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj[key] as? JsonPrimitive ?: continue
            val content = value.contentOrNull?.trim().orEmpty()
            if (content.isNotBlank()) return content
        }
        return null
    }

    private fun safeUrl(raw: String): String? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        return trimmed.takeIf {
            it.length <= 2_048 &&
                (lower.startsWith("https://") || lower.startsWith("http://"))
        }
    }

    private const val MAX_ITEMS = 10
    private const val MAX_DETAILS = 6
    private const val MAX_LINKS = 4
}
