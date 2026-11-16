package com.neuron.ai.core.web

/**
 * Resolves a human search phrase ("Nothing Phone 2a") into a real, public
 * image URL. Implemented in the data layer with a keyless image source; the
 * UI never builds URLs itself and the model can never invent one.
 *
 * Contract: returns null when nothing trustworthy was found — never throws.
 * Callers fall back to a neutral placeholder icon.
 */
interface EntityImageResolver {
    /** Cached where possible; safe to call on every render request. */
    suspend fun resolve(query: String): String?
}
