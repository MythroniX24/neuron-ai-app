package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRoutingRulesTest {

    private fun model(id: String, enabled: Boolean = true, architecture: String? = "llama") =
        LocalModelRecord(
            id = id,
            displayName = id,
            fileName = "$id.gguf",
            sizeBytes = 1024L,
            enabledForChat = enabled,
            architecture = architecture
        )

    private val tiny = model("tiny")
    private val tooler = model("tooler")
    private val seer = model("seer", architecture = "qwen2vl")

    @Test
    fun `the first matching rule wins`() {
        val rules = listOf(
            ModelRoutingRule("tiny", text = true),
            ModelRoutingRule("tooler", text = true, tools = true)
        )
        assertEquals(
            "tiny",
            LocalRoutingRules.pick(LocalTurnKind.TEXT, rules, listOf(tiny, tooler)) { false }?.id
        )
        assertEquals(
            "tooler",
            LocalRoutingRules.pick(LocalTurnKind.TOOLS, rules, listOf(tiny, tooler)) { false }?.id
        )
    }

    @Test
    fun `a rule naming a disabled model is skipped`() {
        val rules = listOf(ModelRoutingRule("tiny", text = true))
        val disabled = model("tiny", enabled = false)
        assertEquals(
            "tooler",
            LocalRoutingRules.pick(LocalTurnKind.TEXT, rules, listOf(disabled, tooler)) { false }?.id
        )
    }

    @Test
    fun `a rule naming an unknown model is skipped`() {
        val rules = listOf(ModelRoutingRule("deleted-model", text = true))
        assertEquals(
            "tiny",
            LocalRoutingRules.pick(LocalTurnKind.TEXT, rules, listOf(tiny)) { false }?.id
        )
    }

    @Test
    fun `a vision rule only counts when the model can really see`() {
        val blindVisionRule = listOf(ModelRoutingRule("tiny", text = true, vision = true))
        // "tiny" has no projector → the rule is ignored and the capable model
        // from the capability fallback serves the turn.
        assertEquals(
            "seer",
            LocalRoutingRules.pick(
                LocalTurnKind.VISION,
                blindVisionRule,
                listOf(tiny, seer)
            ) { it.id == "seer" }?.id
        )

        val goodRule = listOf(ModelRoutingRule("seer", text = true, vision = true))
        assertEquals(
            "seer",
            LocalRoutingRules.pick(
                LocalTurnKind.VISION,
                goodRule,
                listOf(tiny, seer)
            ) { it.id == "seer" }?.id
        )
    }

    @Test
    fun `without rules a vision turn still never lands on a text-only model`() {
        val picked = LocalRoutingRules.pick(
            LocalTurnKind.VISION,
            emptyList(),
            listOf(tiny, seer)
        ) { it.id == "seer" }
        assertEquals("seer", picked?.id)
    }

    @Test
    fun `a vision turn with no capable model has no answer`() {
        assertNull(
            LocalRoutingRules.pick(LocalTurnKind.VISION, emptyList(), listOf(tiny)) { false }
        )
    }

    @Test
    fun `nothing enabled means nothing can serve the turn`() {
        val off = model("tiny", enabled = false)
        assertNull(LocalRoutingRules.pick(LocalTurnKind.TEXT, emptyList(), listOf(off)) { false })
    }

    @Test
    fun `toggling one axis leaves the others alone`() {
        val rule = ModelRoutingRule("m", text = true)
        val off = rule.with(LocalTurnKind.TEXT, false)
        assertEquals(ModelRoutingRule("m", text = false), off)

        val toolsOn = off.with(LocalTurnKind.TOOLS, true)
        assertEquals(ModelRoutingRule("m", text = false, tools = true), toolsOn)
        assertEquals(toolsOn, LocalRoutingRules.ruleFor(listOf(toolsOn), "m"))
        // An unknown model gets an inert default rather than a silent "text".
        assertEquals(
            ModelRoutingRule("other", text = false),
            LocalRoutingRules.ruleFor(listOf(toolsOn), "other")
        )
    }
}
