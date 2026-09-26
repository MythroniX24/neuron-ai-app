package com.neuron.ai.data.web

/**
 * Security (§ Security requirements): fetched web content is UNTRUSTED
 * DATA. Search snippets and page text may contain injected instructions
 * ("ignore previous instructions...", fake tool calls, fence-breakers).
 *
 *  1. Neutralize fence escapes: content cannot open/close the
 *     <<<UNTRUSTED_WEB_DATA>>> fence itself.
 *  2. Strip the classic injection openers ("ignore (all)
 *     previous instructions..." etc.) so the model never sees a clean
 *     directive form.
 *  3. Wrap everything in the same fence the system prompt already warns
 *     about (ToolUsingAgent) so the model treats it as data, not rules.
 */
object WebContentSanitizer {

    const val FENCE_OPEN = "<<<UNTRUSTED_WEB_DATA>>>"
    const val FENCE_CLOSE = "<<<END_UNTRUSTED_WEB_DATA>>>"

    fun wrap(content: String): String =
        "$FENCE_OPEN\n${sanitize(content)}\n$FENCE_CLOSE"

    /** Neutralized copy of [content], safe to place inside the fence. */
    fun sanitize(content: String): String {
        var text = content

        // 1. Fence-breakers: any close/open marker inside the payload must
        //    not look like a real fence boundary. Insert a zero-width space
        //    so the literal marker never appears contiguously.
        text = text
            .replace(FENCE_OPEN, "<<< UNTRUSTED_WEB_DATA >>>")
        text = text.replace(FENCE_CLOSE, "<<< END_UNTRUSTED_WEB_DATA >>>")

        // 2. Direct-injection openers — collapsed to a neutral marker so the
        //    model sees an obvious, quarantined attempt, not an instruction.
        text = INJECTION_OPENERS.replace(text) { match ->
            "[${match.value.trim().uppercase()} — INJECTED INSTRUCTION REMOVED]"
        }
        return text
    }

    private val INJECTION_OPENERS = Regex(
        "(?im)(ignore|disregard|forget)\\s+(all\\s+)?(previous|prior|earlier|above)\\s+" +
            "(instructions?|directions?|prompts?|rules?)"
    )
}
