package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 6 — capability-aware on-device routing. The router decides what an
 * on-device model may do, so it must NEVER overpromise: unprovable
 * capabilities stay off and every degradation stays visible.
 */
class LocalModelRouterTest {

    private fun record(
        id: String = "local-test",
        name: String = "Test 1B",
        architecture: String? = "llama",
        contextLength: Long? = 4096L,
        supportsTools: Boolean = false
    ) = LocalModelRecord(
        id = id,
        displayName = name,
        fileName = "$id.gguf",
        sizeBytes = 1024L,
        architecture = architecture,
        contextLength = contextLength,
        supportsTools = supportsTools
    )

    // ---- Context window ----------------------------------------------------

    @Test
    fun `context is capped for phones and floored for tiny windows`() {
        assertEquals(4096, LocalModelRouter.effectiveContextTokens(8192L))
        assertEquals(2048, LocalModelRouter.effectiveContextTokens(null))
        assertEquals(4096, LocalModelRouter.effectiveContextTokens(4096L))
        // A 256-token GGUF would be useless; floor it instead of trusting it.
        assertEquals(512, LocalModelRouter.effectiveContextTokens(256L))
    }

    @Test
    fun `capability notes explain the context cap`() {
        val caps = LocalModelRouter.capabilities(record(contextLength = 32_768L))
        assertEquals(4096, caps.contextWindowTokens)
        assertEquals(32_768, caps.declaredContextTokens)
        assertTrue(caps.notes.any { it.contains("capped at 4096") })
    }

    // ---- Vision / tools ----------------------------------------------------

    @Test
    fun `vision is only claimed for architectures that declare a vision tower`() {
        assertTrue(LocalModelRouter.architectureSupportsVision("qwen2vl"))
        assertFalse(LocalModelRouter.architectureSupportsVision("llama"))
        assertFalse(LocalModelRouter.architectureSupportsVision(null))

        val textOnly = LocalModelRouter.capabilities(record(architecture = "llama"))
        assertFalse(textOnly.supportsVision)
        assertTrue(textOnly.notes.any { it.contains("Text only") })
    }

    @Test
    fun `a vision tower without its mmproj file is not vision yet`() {
        // Milestone 9: the projector is a SEPARATE GGUF. Without it the model
        // cannot read a picture, so the router must not claim it can.
        val towerOnly = LocalModelRouter.capabilities(
            record(architecture = "qwen2vl"),
            hasProjector = false
        )
        assertFalse(towerOnly.supportsVision)
        assertTrue(towerOnly.notes.any { it.contains("no mmproj projector") })

        val ready = LocalModelRouter.capabilities(
            record(architecture = "qwen2vl"),
            hasProjector = true
        )
        assertTrue(ready.supportsVision)
        assertTrue(ready.notes.any { it.contains("Vision ready") })
    }

    @Test
    fun `a projector cannot make a text-only architecture see`() {
        val wrong = LocalModelRouter.capabilities(
            record(architecture = "llama"),
            hasProjector = true
        )
        assertFalse(wrong.supportsVision)
    }

    @Test
    fun `images route only when the projector is really there`() {
        val withoutProjector = LocalModelRouter.decide(
            record(architecture = "qwen2vl"),
            LocalRouteRequest(hasImageAttachments = true),
            hasProjector = false
        )
        assertTrue(withoutProjector is LocalRouteDecision.Refuse)

        val withProjector = LocalModelRouter.decide(
            record(architecture = "qwen2vl"),
            LocalRouteRequest(hasImageAttachments = true),
            hasProjector = true
        )
        assertTrue(withProjector is LocalRouteDecision.Route)
        assertTrue((withProjector as LocalRouteDecision.Route).capabilities.supportsVision)
    }

    @Test
    fun `tool support comes from the gguf chat template, never the name`() {
        val honest = LocalModelRouter.capabilities(record(supportsTools = false))
        assertFalse(honest.supportsTools)
        assertTrue(honest.notes.any { it.contains("No tool-calling support") })

        val toolModel = LocalModelRouter.capabilities(record(supportsTools = true))
        assertTrue(toolModel.supportsTools)
    }

    // ---- Routing decisions -------------------------------------------------

    @Test
    fun `images on a text-only model are refused with an actionable reason`() {
        val decision = LocalModelRouter.decide(
            record(architecture = "llama"),
            LocalRouteRequest(hasImageAttachments = true)
        )
        assertTrue(decision is LocalRouteDecision.Refuse)
        val reason = (decision as LocalRouteDecision.Refuse).reason
        assertTrue(reason.contains("text-only"))
        // The way out must be named, not just the problem.
        assertTrue(reason.contains("vision model"))
    }

    @Test
    fun `wanted tools are degraded visibly, not silently dropped`() {
        val decision = LocalModelRouter.decide(
            record(supportsTools = false),
            LocalRouteRequest(wantsTools = true)
        )
        assertTrue(decision is LocalRouteDecision.Route)
        val route = decision as LocalRouteDecision.Route
        assertFalse(route.capabilities.supportsTools)
        assertTrue(route.degraded.any { it.contains("Tools disabled") })
    }

    @Test
    fun `a tool-capable model keeps tools and reports no tool degradation`() {
        val decision = LocalModelRouter.decide(
            record(supportsTools = true),
            LocalRouteRequest(wantsTools = true)
        )
        val route = decision as LocalRouteDecision.Route
        assertTrue(route.capabilities.supportsTools)
        assertFalse(route.degraded.any { it.contains("Tools disabled") })
    }

    @Test
    fun `timeline text carries the model, the window and the throttle note`() {
        val decision = LocalModelRouter.decide(record(name = "Qwen2.5 1B"), LocalRouteRequest())
        val caps = (decision as LocalRouteDecision.Route).capabilities
        val label = LocalModelRouter.timelineLabel(caps)
        assertTrue(label.contains("On-device"))
        assertTrue(label.contains("Qwen2.5 1B"))
        assertTrue(label.contains("4096 ctx"))

        val detail = LocalModelRouter.timelineDetail(caps, decision.degraded, "Device is hot")
        assertTrue(detail.contains("Device is hot"))
        assertTrue(detail.contains("Text only"))
    }
}