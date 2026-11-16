package com.neuron.ai.core.conversation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Structured comparison surfaces the model can render natively instead of
 * writing the same facts as Markdown. Deliberately domain-agnostic: phones,
 * apps, AI models, plans, tools, places or services all use the same five
 * shapes — the payload carries its own labels and copy, so no domain text is
 * ever hardcoded in the UI.
 *
 * The model produces these through the `ui.compare` tool (validated by
 * [ComparisonBlocksParser]); each block is persisted on the assistant message
 * as a [ComparisonPlacement] so it re-renders after restart.
 */
@Serializable
sealed class ComparisonUiBlock {

    /** Two equal-width cards with an image, a pill label and key facts. */
    @Serializable
    @SerialName("side_by_side")
    data class SideBySide(
        val title: String? = null,
        val optionA: ComparisonOption,
        val optionB: ComparisonOption
    ) : ComparisonUiBlock()

    /** Feature / Option A / Option B table. */
    @Serializable
    @SerialName("feature_table")
    data class FeatureTable(
        val title: String? = null,
        val optionALabel: String = "Option A",
        val optionBLabel: String = "Option B",
        val rows: List<FeatureRow> = emptyList()
    ) : ComparisonUiBlock()

    /** Two score cards; the higher score is highlighted and gets a Winner pill. */
    @Serializable
    @SerialName("score")
    data class Score(
        val title: String? = null,
        val metric: String = "Overall score",
        val optionA: ScoreOption,
        val optionB: ScoreOption
    ) : ComparisonUiBlock()

    /** Pros / Cons side-by-side lists. */
    @Serializable
    @SerialName("pros_cons")
    data class ProsCons(
        val title: String? = null,
        val prosTitle: String = "Pros",
        val consTitle: String = "Cons",
        val pros: List<String> = emptyList(),
        val cons: List<String> = emptyList()
    ) : ComparisonUiBlock()

    /** Interactive "compare by priority" radio card with tailored advice. */
    @Serializable
    @SerialName("priority")
    data class Priority(
        val title: String = "Compare by priority",
        val priorities: List<PriorityOption> = emptyList()
    ) : ComparisonUiBlock()
}

/** One side of a comparison. */
@Serializable
data class ComparisonOption(
    /** Pill text, e.g. "Option A" — the model supplies it, nothing is assumed. */
    val label: String = "Option A",
    val title: String,
    val subtitle: String? = null,
    /** Verified public image URL (only when a tool actually returned one). */
    val imageUrl: String? = null,
    /** Search phrase used to look up a real image when [imageUrl] is absent. */
    val imageQuery: String? = null,
    val specs: List<ComparisonSpec> = emptyList()
)

@Serializable
data class ComparisonSpec(val label: String, val value: String)

@Serializable
data class FeatureRow(val feature: String, val a: String, val b: String)

@Serializable
data class ScoreOption(
    val label: String,
    val score: Double,
    val maxScore: Double = 10.0
)

@Serializable
data class PriorityOption(val label: String, val recommendation: String = "")

/**
 * A block plus the position in the message text where it appeared, so a single
 * answer can read as prose → card → prose → card.
 */
@Serializable
data class ComparisonPlacement(val anchor: Int = 0, val block: ComparisonUiBlock)

/** One renderable piece of an assistant message, in reading order. */
sealed class ComparisonSegment {
    data class Text(val text: String) : ComparisonSegment()
    data class Block(val block: ComparisonUiBlock) : ComparisonSegment()
}

/** Splits a message body at the placement anchors — pure, unit-tested. */
object ComparisonLayout {

    fun segments(
        markdown: String,
        placements: List<ComparisonPlacement>
    ): List<ComparisonSegment> {
        if (placements.isEmpty()) {
            return if (markdown.isBlank()) emptyList() else listOf(ComparisonSegment.Text(markdown))
        }
        val out = mutableListOf<ComparisonSegment>()
        var cursor = 0
        placements.sortedBy { it.anchor }.forEach { placement ->
            val end = placement.anchor.coerceIn(cursor, markdown.length)
            if (end > cursor) out += ComparisonSegment.Text(markdown.substring(cursor, end))
            out += ComparisonSegment.Block(placement.block)
            cursor = end
        }
        if (cursor < markdown.length) out += ComparisonSegment.Text(markdown.substring(cursor))
        return out.filterNot { it is ComparisonSegment.Text && it.text.isBlank() }
    }

    /** Search phrases this block still needs an image for. */
    fun imageQueries(block: ComparisonUiBlock): List<String> = when (block) {
        is ComparisonUiBlock.SideBySide -> listOfNotNull(
            block.optionA.imageQuery,
            block.optionB.imageQuery
        ).filter { it.isNotBlank() }
        else -> emptyList()
    }
}

/**
 * Tolerant parser for model output. Accepts snake_case / camelCase / plain
 * English type names, alternative key spellings, single blocks or arrays, and
 * silently drops anything unusable instead of throwing. Every string is
 * length-capped and only http(s) image URLs survive, so a hostile payload
 * cannot inject schemes into the native UI.
 *
 * Pure Kotlin: unit-tested without Android or a network.
 */
object ComparisonBlocksParser {

    const val TOOL_ID = "ui.compare"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val MAX_BLOCKS = 6
    private const val MAX_SPECS = 8
    private const val MAX_ROWS = 12
    private const val MAX_LIST_ITEMS = 8
    private const val MAX_PRIORITIES = 6

    /** Marker the model may use when it cannot call the tool. */
    private const val FENCE_MARKER = "(?:neuron-comparison|neuron-compare|comparison)"

    private val FENCE = Regex(
        "```\\s*$FENCE_MARKER\\s*\\n([\\s\\S]*?)```",
        RegexOption.IGNORE_CASE
    )

    private val OPEN_MARKER = Regex(
        "```\\s*$FENCE_MARKER\\b",
        RegexOption.IGNORE_CASE
    )

    private val TYPES = mapOf(
        "sidebyside" to "side_by_side",
        "sidebysidecards" to "side_by_side",
        "twocards" to "side_by_side",
        "cards" to "side_by_side",
        "comparisoncards" to "side_by_side",
        "options" to "side_by_side",
        "featuretable" to "feature_table",
        "table" to "feature_table",
        "features" to "feature_table",
        "specs" to "feature_table",
        "score" to "score",
        "scores" to "score",
        "scorecomparison" to "score",
        "ratings" to "score",
        "rating" to "score",
        "proscons" to "pros_cons",
        "prosandcons" to "pros_cons",
        "advantagesdisadvantages" to "pros_cons",
        "priority" to "priority",
        "priorities" to "priority",
        "interactive" to "priority",
        "prioritycomparison" to "priority",
        "comparebypriority" to "priority"
    )

    /** Parses a whole tool payload: one block, a block list, or nothing. */
    fun parse(raw: String): List<ComparisonUiBlock> = runCatching {
        parseElement(json.parseToJsonElement(raw))
    }.getOrDefault(emptyList())

    /**
     * Fallback for models that cannot call the tool: pulls fenced comparison
     * JSON out of the answer, returns the blocks (anchored at their original
     * position) and the answer with those fences removed.
     */
    fun extractFenced(raw: String): FencedComparison? {
        if (raw.isBlank()) return null
        val matches = FENCE.findAll(raw).toList()
        if (matches.isEmpty()) return null
        val placements = mutableListOf<ComparisonPlacement>()
        val builder = StringBuilder()
        var cursor = 0
        matches.forEach { match ->
            val blocks = parse(match.groupValues.getOrNull(1).orEmpty())
            if (blocks.isEmpty()) return@forEach
            builder.append(raw, cursor, match.range.first)
            val anchor = builder.length
            blocks.take(MAX_BLOCKS).forEach { placements += ComparisonPlacement(anchor, it) }
            cursor = match.range.last + 1
        }
        if (placements.isEmpty()) return null
        builder.append(raw, cursor, raw.length)
        return FencedComparison(placements, builder.toString().trim())
    }

    /**
     * Streaming guard: while a comparison fence is still open, everything from
     * the opening marker onwards is hidden, so raw JSON brackets never reach
     * the chat. [hasPendingBlock] lets the UI show a compact skeleton instead.
     */
    fun splitPendingStream(text: String): StreamingText {
        val open = OPEN_MARKER.findAll(text).lastOrNull() ?: return StreamingText(text, false)
        val after = text.substring(open.range.last + 1)
        if (after.contains("```")) return StreamingText(text, false)
        return StreamingText(text.substring(0, open.range.first).trimEnd(), true)
    }

    data class FencedComparison(
        val placements: List<ComparisonPlacement>,
        val text: String
    )

    data class StreamingText(val visible: String, val hasPendingBlock: Boolean)

    private fun parseElement(element: JsonElement): List<ComparisonUiBlock> = when (element) {
        is JsonArray -> element.mapNotNull { parseOne(it as? JsonObject) }.take(MAX_BLOCKS)
        is JsonObject -> {
            val blocks = firstArray(element, "blocks", "comparisons", "widgets", "sections")
            if (blocks != null) {
                blocks.mapNotNull { parseOne(it as? JsonObject) }.take(MAX_BLOCKS)
            } else {
                parseOne(element)?.let { listOf(it) }.orEmpty()
            }
        }
        else -> emptyList()
    }

    private fun parseOne(obj: JsonObject?): ComparisonUiBlock? {
        if (obj == null) return null
        val declared = normalize(text(obj, "type", "kind", "widget", "style", "block", "layout"))
        val type = TYPES[declared] ?: inferType(obj) ?: return null
        return when (type) {
            "side_by_side" -> parseSideBySide(obj)
            "feature_table" -> parseTable(obj)
            "score" -> parseScore(obj)
            "pros_cons" -> parseProsCons(obj)
            "priority" -> parsePriority(obj)
            else -> null
        }
    }

    private fun inferType(obj: JsonObject): String? {
        val keys = obj.keys.map { it.lowercase() }
        return when {
            keys.any { it == "priorities" || it == "criteria" || it == "categories" } -> "priority"
            keys.any { it == "rows" || it == "features" } -> "feature_table"
            keys.any { it == "pros" || it == "advantages" || it == "cons" } -> "pros_cons"
            firstObject(obj, "optiona", "option_a", "left") != null &&
                number(firstObject(obj, "optiona", "option_a", "left")!!, "score") != null -> "score"
            firstObject(obj, "optiona", "option_a", "left") != null -> "side_by_side"
            else -> null
        }
    }

    // ---- side by side -----------------------------------------------------

    private fun parseSideBySide(obj: JsonObject): ComparisonUiBlock? {
        val options = firstArray(obj, "options", "items", "sides")
        val a = firstObject(obj, "optiona", "option_a", "a", "option1", "left", "first")
            ?: options?.getOrNull(0)?.let { it as? JsonObject }
        val b = firstObject(obj, "optionb", "option_b", "b", "option2", "right", "second")
            ?: options?.getOrNull(1)?.let { it as? JsonObject }
        val optionA = parseOption(a, "Option A") ?: return null
        val optionB = parseOption(b, "Option B") ?: return null
        return ComparisonUiBlock.SideBySide(
            title = text(obj, "title", "heading", "caption")?.take(120),
            optionA = optionA,
            optionB = optionB
        )
    }

    private fun parseOption(obj: JsonObject?, fallbackLabel: String): ComparisonOption? {
        if (obj == null) return null
        val title = text(obj, "title", "name", "item", "product", "model", "label")
            ?.take(120) ?: return null
        val specs = parseSpecs(obj)
        val imageUrl = text(obj, "imageUrl", "image_url", "image", "thumbnail", "photo", "picture")
            ?.let(::safeUrl)
        val imageQuery = text(
            obj, "imageQuery", "image_query", "imageSearch", "image_search", "searchQuery", "photoQuery"
        )?.take(120)
        return ComparisonOption(
            label = text(obj, "pill", "pillLabel", "badge", "tag", "optionLabel", "side")
                ?.take(40)
                ?: fallbackLabel,
            title = title,
            subtitle = text(obj, "subtitle", "meta", "category", "description", "summary")?.take(240),
            imageUrl = imageUrl,
            // An image query only matters when there is no verified URL.
            imageQuery = if (imageUrl == null) imageQuery else null,
            specs = specs
        )
    }

    private fun parseSpecs(obj: JsonObject): List<ComparisonSpec> {
        val element = obj["specs"] ?: obj["details"] ?: obj["features"] ?: obj["facts"]
            ?: obj["keyFacts"] ?: return emptyList()
        val specs = when (element) {
            is JsonArray -> element.mapNotNull { entry ->
                val entryObj = entry as? JsonObject ?: return@mapNotNull null
                val label = text(entryObj, "label", "key", "name", "feature") ?: return@mapNotNull null
                val value = text(entryObj, "value", "text", "detail", "spec") ?: return@mapNotNull null
                ComparisonSpec(label.take(60), value.take(200))
            }
            is JsonObject -> element.entries.mapNotNull { (key, value) ->
                val content = (value as? JsonPrimitive)?.contentOrNull?.trim()
                if (content.isNullOrBlank()) null
                else ComparisonSpec(key.take(60), content.take(200))
            }
            else -> emptyList()
        }
        return specs.take(MAX_SPECS)
    }

    // ---- feature table ----------------------------------------------------

    private fun parseTable(obj: JsonObject): ComparisonUiBlock? {
        val rows = firstArray(obj, "rows", "features", "comparison", "data", "entries")
            ?: return null
        val parsed = rows.mapNotNull { entry ->
            val rowObj = entry as? JsonObject ?: return@mapNotNull null
            val feature = text(rowObj, "feature", "label", "name", "aspect", "spec", "criterion")
                ?: return@mapNotNull null
            val a = cell(rowObj, "a", "optiona", "option_a", "option1", "left", "first") ?: "—"
            val b = cell(rowObj, "b", "optionb", "option_b", "option2", "right", "second") ?: "—"
            FeatureRow(feature.take(80), a.take(200), b.take(200))
        }.take(MAX_ROWS)
        if (parsed.isEmpty()) return null
        return ComparisonUiBlock.FeatureTable(
            title = text(obj, "title", "heading", "caption")?.take(120),
            optionALabel = text(obj, "optionALabel", "option_a_label", "aLabel", "leftLabel")
                ?.take(40) ?: "Option A",
            optionBLabel = text(obj, "optionBLabel", "option_b_label", "bLabel", "rightLabel")
                ?.take(40) ?: "Option B",
            rows = parsed
        )
    }

    private fun cell(obj: JsonObject, vararg keys: String): String? {
        when (val value = element(obj, *keys)) {
            is JsonPrimitive -> return value.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            is JsonObject -> return text(value, "value", "text", "label")
            else -> return null
        }
    }

    // ---- score ------------------------------------------------------------

    private fun parseScore(obj: JsonObject): ComparisonUiBlock? {
        val a = firstObject(obj, "optiona", "option_a", "a", "option1", "left", "first")
            ?: return null
        val b = firstObject(obj, "optionb", "option_b", "b", "option2", "right", "second")
            ?: return null
        val optionA = parseScoreOption(a, "Option A", obj) ?: return null
        val optionB = parseScoreOption(b, "Option B", obj) ?: return null
        return ComparisonUiBlock.Score(
            title = text(obj, "title", "heading", "caption")?.take(120),
            metric = text(obj, "metric", "metricLabel", "scoreLabel", "subtitle")
                ?.take(60) ?: "Overall score",
            optionA = optionA,
            optionB = optionB
        )
    }

    private fun parseScoreOption(
        obj: JsonObject,
        fallbackLabel: String,
        parent: JsonObject
    ): ScoreOption? {
        val score = number(obj, "score", "value", "rating", "points") ?: return null
        val max = number(obj, "maxScore", "max_score", "max", "outOf", "scale")
            ?: number(parent, "maxScore", "max_score", "max", "outOf", "scale")
            ?: 10.0
        if (max <= 0.0) return null
        return ScoreOption(
            label = text(obj, "label", "pill", "name", "optionLabel")?.take(40) ?: fallbackLabel,
            score = score.coerceIn(0.0, max),
            maxScore = max
        )
    }

    // ---- pros / cons ------------------------------------------------------

    private fun parseProsCons(obj: JsonObject): ComparisonUiBlock? {
        val pros = stringList(obj, "pros", "advantages", "plus", "strengths", "benefits")
        val cons = stringList(obj, "cons", "disadvantages", "minus", "weaknesses", "drawbacks")
        if (pros.isEmpty() && cons.isEmpty()) return null
        return ComparisonUiBlock.ProsCons(
            title = text(obj, "title", "heading", "caption")?.take(120),
            prosTitle = text(obj, "prosTitle", "pros_title", "prosLabel")?.take(40) ?: "Pros",
            consTitle = text(obj, "consTitle", "cons_title", "consLabel")?.take(40) ?: "Cons",
            pros = pros,
            cons = cons
        )
    }

    private fun stringList(obj: JsonObject, vararg keys: String): List<String> {
        val element = element(obj, *keys) ?: return emptyList()
        val items = when (element) {
            is JsonArray -> element.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                    is JsonObject -> text(entry, "text", "label", "value", "title")?.take(160)
                    else -> null
                }
            }
            is JsonObject -> element.values.mapNotNull { value ->
                (value as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            }
            is JsonPrimitive -> listOf(element.contentOrNull.orEmpty())
            else -> emptyList()
        }
        return items.map { it.take(160) }.take(MAX_LIST_ITEMS)
    }

    // ---- priority ---------------------------------------------------------

    private fun parsePriority(obj: JsonObject): ComparisonUiBlock? {
        val array = firstArray(obj, "priorities", "options", "categories", "criteria")
            ?: return null
        val priorities = array.mapNotNull { entry ->
            val entryObj = entry as? JsonObject ?: return@mapNotNull null
            val label = text(entryObj, "label", "name", "title", "category", "priority")
                ?: return@mapNotNull null
            val recommendation = text(
                entryObj,
                "recommendation",
                "summary",
                "advice",
                "detail",
                "why",
                "whatToPick",
                "result"
            ).orEmpty()
            PriorityOption(label.take(60), recommendation.take(400))
        }.take(MAX_PRIORITIES)
        if (priorities.isEmpty()) return null
        return ComparisonUiBlock.Priority(
            title = text(obj, "title", "heading", "question")?.take(80) ?: "Compare by priority",
            priorities = priorities
        )
    }

    // ---- helpers ----------------------------------------------------------

    private fun normalize(raw: String?): String =
        raw.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /**
     * Key matching ignores case and separators, so `optionA`, `option_a` and
     * `Option A` are the same key — models write all three.
     */
    private fun keyOf(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }

    private fun element(obj: JsonObject, vararg keys: String): JsonElement? {
        val wanted = keys.map(::keyOf).toHashSet()
        return obj.entries.firstOrNull { keyOf(it.key) in wanted }?.value
    }

    private fun text(obj: JsonObject, vararg keys: String): String? {
        val value = element(obj, *keys) as? JsonPrimitive ?: return null
        if (value.booleanOrNull != null) return null
        val content = value.contentOrNull?.trim().orEmpty()
        return content.ifBlank { null }
    }

    private fun firstObject(obj: JsonObject, vararg keys: String): JsonObject? =
        element(obj, *keys) as? JsonObject

    private fun firstArray(obj: JsonObject, vararg keys: String): JsonArray? =
        (element(obj, *keys) as? JsonArray)?.takeIf { it.isNotEmpty() }

    private fun number(obj: JsonObject, vararg keys: String): Double? {
        val raw = (element(obj, *keys) as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        if (raw.isBlank()) return null
        val cleaned = raw.replace(',', '.')
        cleaned.toDoubleOrNull()?.let { return it }
        cleaned.substringBefore('/').trim().toDoubleOrNull()?.let { return it }
        return Regex("-?\\d+(?:\\.\\d+)?").find(cleaned)?.value?.toDoubleOrNull()
    }

    private fun safeUrl(raw: String): String? {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        return trimmed.takeIf {
            it.length <= 2_048 && (lower.startsWith("https://") || lower.startsWith("http://"))
        }
    }
}
