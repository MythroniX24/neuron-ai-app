package com.neuron.ai.data.local

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JVM-side checks for the engine loader facade. The native library is never
 * present in unit tests, so every probe must degrade to "unavailable" without
 * throwing — the repository treats these as "never request GPU offload".
 */
class LocalEngineLoaderTest {

    @Test
    fun `gpu probe degrades to false without the native library`() {
        assertFalse(LocalEngineLoader.gpuAvailable)
    }

    @Test
    fun `load reports unavailable instead of throwing on the jvm`() {
        val result = LocalEngineLoader.load("/nonexistent.gguf", 2048, 4, useGpu = true)
        assertFalse(result is LocalEngineLoader.LoadResult.Success)
    }
}
