package com.neuron.ai.data.local

/**
 * Milestone 8 — prompt-prefix reuse, the pure half.
 *
 * Every chat turn re-renders the WHOLE conversation, so the naive path
 * re-processes thousands of already-seen tokens before writing a single word.
 * On a phone CPU that wait dominates the answer time. The KV cache already
 * holds those tokens from the previous turn, so only the new tail has to be
 * decoded.
 *
 * This object owns the decision (how many leading tokens can be skipped) and
 * nothing else — no native calls, no state — so it is exhaustively unit
 * tested. The native side re-verifies the same number against its own copy of
 * the cache and can only ever LOWER it, so a wrong value here degrades to
 * "recompute everything", never to a corrupted answer.
 */
object PromptPrefix {

    /**
     * Length of the longest common prefix of [cached] (what the KV cache
     * holds) and [fresh] (this turn's tokenized prompt).
     *
     * A prefix of length 0 or 1 is never worth reusing: llama.cpp needs at
     * least the BOS-style first token re-evaluated for the logits to be
     * meaningful, so tiny overlaps are reported as no reuse at all.
     */
    fun commonPrefixLength(cached: IntArray, fresh: IntArray): Int {
        if (cached.isEmpty() || fresh.isEmpty()) return 0
        val limit = minOf(cached.size, fresh.size)
        var i = 0
        while (i < limit && cached[i] == fresh[i]) {
            i++
        }
        return if (i >= MIN_REUSABLE_TOKENS) i else 0
    }

    /**
     * Clamps a prompt to the model's context window, keeping the TAIL (the
     * newest turns) and dropping the oldest prefix — which is also what makes
     * the next turn's prefix reuse cheap.
     */
    fun clampToContext(promptTokens: IntArray, contextWindow: Int): IntArray {
        if (contextWindow <= 0 || promptTokens.size <= contextWindow) return promptTokens
        return promptTokens.copyOfRange(promptTokens.size - contextWindow, promptTokens.size)
    }

    /** Reuse percentage for the UI (0 when nothing was reused). */
    fun reusePercent(reused: Int, promptTokens: Int): Int {
        if (promptTokens <= 0 || reused <= 0) return 0
        return ((reused.toLong() * 100) / promptTokens).toInt().coerceIn(0, 100)
    }

    /**
     * Below this many shared tokens the bookkeeping costs more attention
     * bookkeeping than it saves compute.
     */
    const val MIN_REUSABLE_TOKENS = 2
}
