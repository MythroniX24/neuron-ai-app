package com.neuron.ai.data.local

/**
 * Fits a conversation into the context window of a small on-device model.
 *
 * The bug this exists to fix: the prompt was sent whole, so once a chat grew
 * past `n_ctx` EVERY later turn died with "Prompt exceeded the model's context
 * window" — and because the history only ever grows, the failure was permanent
 * until the user started a new chat.
 *
 * Two things make the arithmetic awkward and are handled here rather than at
 * the call site:
 *
 *  - the prompt must leave ROOM TO ANSWER. A prompt that exactly fills n_ctx
 *    leaves the model zero tokens to generate, so the answer is cut off after
 *    a token or two. The generation budget is reserved up front.
 *  - the oldest turns have to go in PAIRS. Dropping a user turn and leaving
 *    its orphaned assistant reply turns the conversation into a monologue,
 *    which small models imitate rather than answer.
 *
 * Token counts are exact (the loaded model's own tokenizer), measured through
 * the injected [countTokens]; [render] is injected too so the whole thing is
 * pure and unit-tested without a native library.
 */
object ContextTrimmer {

    /**
     * Result of fitting: the turns that survived and the prompt rendered from
     * them. [hasMediaMarker] reports whether the vision marker is still
     * present, so the caller can fall back to text-only generation instead of
     * handing mtmd a bitmap the prompt no longer refers to.
     */
    data class Fitted(
        val turns: List<ChatTemplateRenderer.Turn>,
        val prompt: String,
        val hasMediaMarker: Boolean,
        val droppedTurns: Int
    )

    /** Rough token count used only when no tokenizer is reachable. */
    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun fit(
        turns: List<ChatTemplateRenderer.Turn>,
        contextTokens: Int,
        maxOutputTokens: Int,
        addAssistant: Boolean = true,
        render: (List<ChatTemplateRenderer.Turn>, Boolean) -> String,
        countTokens: (String) -> Int
    ): Fitted {
        fun measured(candidate: List<ChatTemplateRenderer.Turn>): Fitted {
            val prompt = render(candidate, addAssistant)
            return Fitted(
                turns = candidate,
                prompt = prompt,
                hasMediaMarker = candidate.any { turn ->
                    turn.content.contains(LocalEngineLoader.MEDIA_MARKER)
                },
                droppedTurns = turns.size - candidate.size
            )
        }

        if (turns.isEmpty()) return measured(turns)

        // Prompt + answer must both fit. A context of 0 means "unknown", in
        // which case we leave the conversation alone rather than guess a limit.
        //
        // The answer reservation never eats the whole window (see
        // [answerReservation]) — otherwise `room` went negative on a small
        // model and the old code sent the conversation WHOLE, which is exactly
        // the "prompt exceeded the context window" failure on every turn.
        val room = contextTokens - answerReservation(contextTokens, maxOutputTokens) -
            RESERVED_TOKENS
        if (room <= 0) return measured(turns)

        var candidate = turns
        var fitted = measured(candidate)
        if (countTokens(fitted.prompt) <= room) return fitted

        // Leading system turns are the model's operating instructions, not
        // history: they go last and, in practice, never go at all.
        val systemCount = candidate.takeWhile { it.role == ChatTemplateRenderer.Turn.SYSTEM }.size
        while (true) {
            val removable = candidate.size - systemCount
            if (removable <= 1) {
                // One turn left and it is still too big: a pasted document, a
                // huge tool result. Truncating it beats failing the turn, and
                // beats silently letting the decode stop mid-answer.
                val shrunkLast = shrinkTurnAt(
                    candidate, candidate.lastIndex, room, addAssistant, render, countTokens
                )
                if (shrunkLast != null) return measured(shrunkLast)

                // Even an EMPTIED final turn does not fit, so what is left is
                // the mandatory scaffolding: the leading system/memory turns.
                // Truncate those too (oldest first) rather than handing the
                // engine a prompt it must reject wholesale — a small-context
                // model whose system turn alone filled the window used to fail
                // EVERY turn this way.
                var squeezed: List<ChatTemplateRenderer.Turn>? = null
                for (index in 0 until candidate.lastIndex) {
                    val shortened = shrinkTurnAt(
                        squeezed ?: candidate, index, room, addAssistant, render, countTokens
                    ) ?: continue
                    squeezed = shortened
                    if (countTokens(render(shortened, addAssistant)) <= room) {
                        return measured(shortened)
                    }
                }
                // Nothing fits: return the smallest prompt we could build and
                // let the engine report the real numbers in the failure.
                return measured(squeezed ?: fitted.turns)
            }
            val next = candidate.toMutableList()
            next.removeAt(systemCount)
            // Drop the orphaned assistant reply along with its question.
            if (next.size > systemCount &&
                next[systemCount].role == ChatTemplateRenderer.Turn.ASSISTANT
            ) {
                next.removeAt(systemCount)
            }
            candidate = next
            val measuredNext = measured(candidate)
            fitted = measuredNext
            if (countTokens(measuredNext.prompt) <= room) return measuredNext
        }
    }

    /**
     * Tokens held back for the answer. Never more than HALF the window: a
     * 1024-token reservation against a 1024-token model made the prompt budget
     * negative, and a negative budget used to mean "send everything". Half the
     * window always leaves room for a prompt, which is the difference between
     * a trimmed turn and a permanently broken chat on a small model.
     */
    fun answerReservation(contextTokens: Int, requestedOutputTokens: Int): Int {
        if (contextTokens <= 0) return requestedOutputTokens
        val half = maxOf(1, contextTokens / 2)
        return requestedOutputTokens.coerceAtLeast(1).coerceAtMost(half)
    }

    /**
     * Largest prefix of turn [index] that still fits, found by bisection (token
     * count rises monotonically with the text). The vision marker is
     * re-appended so a truncated picture turn stays usable.
     *
     * null means NOT EVEN an empty turn fits — the caller then has to shrink
     * something else instead of giving up with an over-long prompt.
     */
    private fun shrinkTurnAt(
        turns: List<ChatTemplateRenderer.Turn>,
        index: Int,
        room: Int,
        addAssistant: Boolean,
        render: (List<ChatTemplateRenderer.Turn>, Boolean) -> String,
        countTokens: (String) -> Int
    ): List<ChatTemplateRenderer.Turn>? {
        val last = turns[index]
        val marker = LocalEngineLoader.MEDIA_MARKER
        val hasMarker = last.content.contains(marker)
        val text = if (hasMarker) {
            last.content.substringBefore(marker).trimEnd()
        } else {
            last.content
        }
        fun withLength(length: Int) = turns.toMutableList().also { list ->
            list[index] = last.copy(
                content = text.take(length) + if (hasMarker) "\n$marker" else ""
            )
        }
        var low = 0
        var high = text.length
        var best: List<ChatTemplateRenderer.Turn>? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val trial = withLength(mid)
            if (countTokens(render(trial, addAssistant)) <= room) {
                best = trial
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * Headroom kept free for control tokens, the answer's stop sequence and
     * off-by-one between tokenize and decode.
     */
    private const val RESERVED_TOKENS = 64
}