package com.neuron.ai.core.a2ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2uiProtocolTest {

    @Test
    fun `parses v1 messages and resolves data bindings`() {
        val source = """
            {"version":"1.0","createSurface":{"surfaceId":"s1"}}
            {"version":"1.0","updateComponents":{"surfaceId":"s1","components":[
              {"id":"root","component":"Column","children":["title","file"]},
              {"id":"title","component":"Text","text":{"@path":"/title"}},
              {"id":"file","component":"FileCard","path":"Download/report.pdf"}
            ]}}
            {"version":"1.0","updateDataModel":{"surfaceId":"s1","value":{"title":"**Report**"}}}
        """.trimIndent()

        val surfaces = A2uiProtocol.render(source)

        assertEquals(1, surfaces.size)
        val surface = surfaces.first()
        assertEquals("s1", surface.id)
        assertNotNull(surface.root)
        assertEquals("Column", surface.root?.type)

        val resolver = A2uiResolver(surface)
        val title = surface.component("title")!!
        assertEquals("**Report**", resolver.string(title, "text", surface.dataModel))
        assertEquals(
            listOf("title", "file"),
            resolver.children(surface.root!!, surface.dataModel).map { it.id }
        )
    }

    @Test
    fun `accepts a single document with inline components`() {
        val source = """
            {"version":"1.0","createSurface":{"surfaceId":"card","components":[
              {"id":"root","component":"Card","child":"text"},
              {"id":"text","component":"Text","text":"hello"}
            ]}}
        """.trimIndent()

        val surface = A2uiProtocol.render(source).single()
        assertEquals("Card", surface.root?.type)
        assertEquals("hello", A2uiValue.asString(surface.dataModel.let {
            A2uiValue.resolve(surface.component("text")!!.props["text"], surface.dataModel, it)
        }))
    }

    @Test
    fun `reads the v0-8 wire format`() {
        val source = """
            {"beginRendering":{"surfaceId":"old","root":"root"}}
            {"surfaceUpdate":{"surfaceId":"old","components":[
              {"id":"root","component":{"Text":{"text":{"literalString":"Hi"}}}}
            ]}}
            {"dataModelUpdate":{"surfaceId":"old","contents":[{"key":"name","valueString":"Ada"}]}}
        """.trimIndent()

        val surface = A2uiProtocol.render(source).single()
        val resolver = A2uiResolver(surface)
        assertEquals("Hi", resolver.string(surface.root!!, "text", surface.dataModel))
        assertEquals(
            "Ada",
            A2uiValue.asString(A2uiValue.pointer(surface.dataModel, "/name"))
        )
    }

    @Test
    fun `expands template children over a data list`() {
        val source = """
            {"createSurface":{"surfaceId":"list","dataModel":{"files":[
              {"name":"main.kt"},{"name":"config.json"}
            ]}}}
            {"updateComponents":{"surfaceId":"list","components":[
              {"id":"root","component":"Column","children":{"componentId":"row","path":"/files"}},
              {"id":"row","component":"Text","text":{"@path":"/name"}}
            ]}}
        """.trimIndent()

        val surface = A2uiProtocol.render(source).single()
        val resolver = A2uiResolver(surface)
        val children = resolver.children(surface.root!!, surface.dataModel)
        assertEquals(2, children.size)
        val texts = children.map { child ->
            resolver.string(surface.component(child.id)!!, "text", child.scope)
        }
        assertEquals(listOf("main.kt", "config.json"), texts)
    }

    @Test
    fun `writes data model values at a pointer`() {
        val root: kotlinx.serialization.json.JsonElement = JsonObject(
            mapOf("a" to JsonObject(mapOf("b" to JsonPrimitive("old"))))
        )
        val updated = A2uiValue.setPointer(root, "/a/b", JsonPrimitive("new"))
        assertEquals(
            "new",
            A2uiValue.asString(A2uiValue.pointer(updated, "/a/b"))
        )
    }

    @Test
    fun `partially streamed block renders nothing instead of throwing`() {
        val partial = """
            {"createSurface":{"surfaceId":"s"}}
            {"updateComponents":{"surfaceId":"s","components":[
              {"id":"root","component":"Car
        """.trimIndent()

        assertTrue(A2uiProtocol.render(partial).isEmpty())
    }

    @Test
    fun `unwraps a messages array and ignores unknown envelopes`() {
        val source = """
            {"messages":[
              {"createSurface":{"surfaceId":"m"}},
              {"unknownMessage":{"surfaceId":"m"}},
              {"updateComponents":{"surfaceId":"m","components":[
                {"id":"root","component":"Text","text":"ok"}
              ]}}
            ]}
        """.trimIndent()

        val surface = A2uiProtocol.render(source).single()
        assertEquals("Text", surface.root?.type)
        assertEquals(1, surface.components.size)
    }

    @Test
    fun `resolves catalog validation functions`() {
        val surface = A2uiSurface(
            id = "f",
            dataModel = JsonObject(mapOf("email" to JsonPrimitive("nope")))
        )
        val invalid = JsonObject(
            mapOf(
                "@call" to JsonPrimitive("email"),
                "args" to JsonObject(
                    mapOf("value" to JsonObject(mapOf("@path" to JsonPrimitive("/email"))))
                )
            )
        )
        val valid = JsonObject(
            mapOf(
                "@call" to JsonPrimitive("email"),
                "args" to JsonObject(mapOf("value" to JsonPrimitive("ada@example.com")))
            )
        )

        assertTrue(A2uiValue.asBoolean(A2uiValue.resolve(invalid, surface.dataModel, surface.dataModel)) == false)
        assertTrue(A2uiValue.asBoolean(A2uiValue.resolve(valid, surface.dataModel, surface.dataModel)) == true)
    }

    @Test
    fun `resolves smart root when rootId is omitted or custom`() {
        val source = """
            {"version":"1.0","createSurface":{"surfaceId":"s1","components":[
              {"id":"custom_card","component":"Card","child":"text1"},
              {"id":"text1","component":"Text","text":"Card Content"}
            ]}}
        """.trimIndent()

        val surface = A2uiProtocol.render(source).single()
        assertNotNull(surface.root)
        assertEquals("custom_card", surface.root?.id)
        assertEquals("Card", surface.root?.type)
    }

    @Test
    fun `parses direct component array and assigns root id if missing`() {
        val source = """
            [
              {"component":"Card","child":"c1"},
              {"id":"c1","component":"Text","text":"Direct component"}
            ]
        """.trimIndent()

        val surfaces = A2uiProtocol.render(source)
        assertEquals(1, surfaces.size)
        val surface = surfaces.first()
        assertNotNull(surface.root)
        assertEquals("Card", surface.root?.type)
        assertEquals("root", surface.root?.id)
    }

    @Test
    fun `parses single component object directly`() {
        val source = """
            {"component":"Card","child":"t1"}
        """.trimIndent()

        val surfaces = A2uiProtocol.render(source)
        assertEquals(1, surfaces.size)
        val surface = surfaces.first()
        assertNotNull(surface.root)
        assertEquals("Card", surface.root?.type)
    }
}
