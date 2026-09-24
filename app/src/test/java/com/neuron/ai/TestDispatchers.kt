package com.neuron.ai

import com.neuron.ai.core.coroutines.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Real dispatchers for tests that do actual IO (MockWebServer, processes). */
object TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
}
