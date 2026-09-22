package com.neuron.ai.data

import com.neuron.ai.core.agent.Tool
import com.neuron.ai.data.tool.InMemoryToolRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryToolRegistryTest {

    private fun tool(id: String) = object : Tool {
        override val id: String = id
        override val title: String = "Title $id"
        override val description: String = "Description $id"
        override val requiredCapabilities: Set<com.neuron.ai.core.permissions.Capability> =
            emptySet()
        override val parametersSchemaJson: String = "{}"
    }

    @Test
    fun `register adds tool and exposes via flow`() = runTest {
        val registry = InMemoryToolRegistry()

        registry.register(tool("web.search"))

        assertEquals(1, registry.tools.first().size)
        assertEquals("web.search", registry.tools.first().single().id)
    }

    @Test
    fun `register same id replaces existing tool`() = runTest {
        val registry = InMemoryToolRegistry()

        registry.register(tool("web.search"))
        registry.register(tool("web.search"))

        assertEquals(1, registry.tools.first().size)
    }

    @Test
    fun `unregister removes tool`() = runTest {
        val registry = InMemoryToolRegistry()
        registry.register(tool("web.search"))

        registry.unregister("web.search")

        assertEquals(0, registry.tools.first().size)
        assertNull(registry.find("web.search"))
    }

    @Test
    fun `find returns registered tool`() = runTest {
        val registry = InMemoryToolRegistry()
        registry.register(tool("fs.read"))

        assertEquals("fs.read", registry.find("fs.read")?.id)
        assertNull(registry.find("missing"))
    }
}
