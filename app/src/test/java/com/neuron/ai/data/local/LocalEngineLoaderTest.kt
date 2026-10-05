package com.neuron.ai.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The retry decision: a Vulkan device that cannot allocate fails the
     * whole load, and the same file loads on the CPU — so ONLY the memory
     * signature may trigger the automatic CPU retry. A genuinely broken file
     * must not retry, and must never be mistaken for a memory problem.
     */
    @Test
    fun `memory pressure signature triggers the cpu retry`() {
        val memoryReasons = listOf(
            "Model file could not be loaded (corrupt or unsupported GGUF) — not enough memory for this model (try a smaller quantization, or turn GPU off in Settings)",
            "vkCreateBuffer: out of device memory",
            "failed to allocate 1.4 GiB on the GPU",
            "ggml_backend_alloc_buffer: cannot allocate buffer",
            "vkAllocateMemory failed: insufficient memory",
            "OUT OF MEMORY"
        )
        memoryReasons.forEach { reason ->
            assertTrue(
                "should retry on CPU: $reason",
                LocalEngineLoader.looksLikeMemoryPressure(reason)
            )
        }
    }

    @Test
    fun `a broken or unknown file never triggers the cpu retry`() {
        val notMemory = listOf(
            null,
            "",
            "Model file could not be loaded (corrupt or unsupported GGUF)",
            "Model file could not be loaded (corrupt or unsupported GGUF) — unknown model architecture: 'foo'",
            "file is not a GGUF file",
            "unexpected magic characters: 'HTYP'",
            "No Vulkan GPU driver is available on this device"
        )
        notMemory.forEach { reason ->
            assertFalse(
                "must not retry on CPU: $reason",
                LocalEngineLoader.looksLikeMemoryPressure(reason)
            )
        }
    }

    /**
     * A second, very different GPU failure with the same cure: ggml-vulkan
     * refuses to build a device object for a driver without
     * VK_KHR_16bit_storage (PowerVR among them) and throws "Unsupported
     * device", which llama.cpp reports as a model load failure. The model is
     * fine; only the GPU is not. It must still trigger the CPU retry, and it
     * must NOT be classified as memory pressure, because the two produce
     * different advice.
     */
    @Test
    fun `unsupported gpu device triggers the cpu retry but is not memory pressure`() {
        val gpuReasons = listOf(
            "E llama_model_load: error loading model: Unsupported device",
            "The GPU driver refused this load - the model file is fine. " +
                "Turn GPU off in Settings to run it on the CPU.\n" +
                "E llama_model_load: error loading model: Unsupported device",
            "ggml_vulkan: device Vulkan0 does not support 16-bit storage.",
            "vkCreateDevice failed"
        )
        gpuReasons.forEach { reason ->
            assertTrue(
                "should retry on CPU: $reason",
                LocalEngineLoader.shouldRetryWithoutGpu(reason)
            )
        }
        assertFalse(
            LocalEngineLoader.looksLikeMemoryPressure(
                "E llama_model_load: error loading model: Unsupported device"
            )
        )
    }

    @Test
    fun `a broken file never triggers the gpu retry`() {
        val notGpu = listOf(
            null,
            "",
            "Model file could not be loaded (corrupt or unsupported GGUF)",
            "unexpected magic characters: 'HTYP'",
            "No Vulkan GPU driver is available on this device"
        )
        notGpu.forEach { reason ->
            assertFalse(
                "must not retry on CPU: $reason",
                LocalEngineLoader.shouldRetryWithoutGpu(reason)
            )
        }
    }

    /** Diagnostics must answer even when the native library is absent. */
    @Test
    fun `diagnostics degrade gracefully without the native library`() {
        val diagnostics = LocalEngineLoader.diagnostics("/nonexistent.gguf")
        assertFalse(diagnostics.nativeLibraryLoaded)
        assertFalse(diagnostics.modelFileExists)
        assertEquals(0L, diagnostics.modelFileSizeBytes)
        assertFalse(diagnostics.gpuAvailable)
        assertTrue(diagnostics.logText.isNotBlank())
    }
}
