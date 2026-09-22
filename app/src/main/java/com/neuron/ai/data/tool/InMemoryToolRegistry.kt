package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 0 registry: thread-safe, in-memory. Phase 1 swaps in a persistent
 * implementation without touching consumers, thanks to the [ToolRegistry] seam.
 */
class InMemoryToolRegistry : ToolRegistry {

    private val mutex = Mutex()
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())

    override val tools: Flow<List<Tool>> = _tools.asStateFlow()

    override suspend fun register(tool: Tool) {
        mutex.withLock {
            val current = _tools.value
            _tools.value = current.filterNot { it.id == tool.id } + tool
        }
    }

    override suspend fun unregister(toolId: String) {
        mutex.withLock {
            _tools.value = _tools.value.filterNot { it.id == toolId }
        }
    }

    override suspend fun find(toolId: String): Tool? = mutex.withLock {
        _tools.value.firstOrNull { it.id == toolId }
    }
}
