package com.neuron.ai.core.web

/**
 * Turns the model's free-text image phrase into progressively simpler search
 * queries.
 *
 * The model writes things like "Motorola mid-range smartphone (2024) — budget
 * pick"; an encyclopedia lookup for that whole phrase finds nothing, which is
 * why cards used to sit on a shimmer forever. Trying the full phrase first and
 * then the entity alone ("Motorola") is what actually gets a picture.
 *
 * Pure and side-effect free so the fallback order is unit tested.
 */
object ImageQuery {

    /** Words that describe the category rather than the entity. */
    private val GENERIC = setOf(
        "smartphone", "smartphones", "phone", "phones", "mobile", "tablet",
        "laptop", "laptops", "desktop", "camera", "headphones", "earbuds",
        "watch", "tv", "television", "console", "movie", "movies", "film",
        "films", "animated", "animation", "cartoon", "series", "show",
        "product", "products", "brand", "model", "device", "gadget",
        "best", "top", "popular", "cheap", "budget", "flagship", "mid-range",
        "midrange", "premium", "affordable", "review", "comparison", "guide",
        "photo", "poster", "cover", "image", "picture",
        "and", "or", "with", "for", "by", "the", "a", "an", "of"
    )

    private val whitespace = Regex("\\s+")
    private val parenthetical = Regex("\\([^)]*\\)")
    private val year = Regex("\\b(19|20)\\d{2}\\b")
    private val cutCharacters = charArrayOf('-', '–', '—', ',', '.', ':', ';', '|', '/')

    /**
     * Ordered, de-duplicated candidates, most specific first. Never empty for
     * a usable phrase; empty for blank/one-character input.
     */
    fun variants(raw: String, limit: Int = 4): List<String> {
        val cleaned = clean(raw)
        if (cleaned.length < 2) return emptyList()

        val out = ArrayList<String>(6)
        fun add(value: String) {
            val normalized = clean(value.trim().trim(*cutCharacters))
            if (normalized.length >= 2 && out.none { it.equals(normalized, ignoreCase = true) }) {
                out.add(normalized)
            }
        }

        add(cleaned)
        add(cleaned.replace(parenthetical, " "))
        add(cleaned.replace(year, " "))
        add(cleaned.substringBefore('—').substringBefore(" - ").substringBefore(':')
            .substringBefore(','))

        val filtered = cleaned.split(' ')
            .filterNot { it.trim(*cutCharacters).lowercase() in GENERIC }
            .joinToString(" ")
        add(filtered)
        add(filtered.split(' ').take(FIRST_WORDS).joinToString(" "))
        add(cleaned.split(' ').take(FIRST_WORDS).joinToString(" "))

        return out.take(limit.coerceAtLeast(1))
    }

    /** Cache key: case/whitespace-insensitive, safe for a map key. */
    fun key(raw: String): String = clean(raw).lowercase()

    private fun clean(raw: String): String =
        raw.replace(whitespace, " ")
            .trim()
            .trim('"', '\'', '*', '`')
            .trim()

    private const val FIRST_WORDS = 4
}
