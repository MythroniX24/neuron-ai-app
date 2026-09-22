package com.neuron.ai.core.memory

/**
 * Long-term memory surface for agents (project facts, user preferences).
 * Phase 2 fills the implementation; Phase 0 fixes the seam.
 */
interface Memory {
    suspend fun remember(namespace: String, key: String, value: String)
    suspend fun recall(namespace: String, key: String): String?
    suspend fun forget(namespace: String, key: String)
}
