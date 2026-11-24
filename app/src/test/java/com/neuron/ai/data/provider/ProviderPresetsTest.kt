package com.neuron.ai.data.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test
    fun testFreeZeroKeyProviders() {
        val kilo = ProviderPresets.all.firstOrNull { it.id == "kilo" }
        assertNotNull(kilo)
        assertFalse(kilo!!.requiresApiKey)
        assertTrue(kilo.defaultModels.contains("kilo-auto/free"))

        val pollinations = ProviderPresets.all.firstOrNull { it.id == "pollinations" }
        assertNotNull(pollinations)
        assertFalse(pollinations!!.requiresApiKey)
        assertTrue(pollinations.defaultModels.contains("openai"))

        val openCode = ProviderPresets.all.firstOrNull { it.id == "opencode" }
        assertNotNull(openCode)
        assertFalse(openCode!!.requiresApiKey)
    }
}
