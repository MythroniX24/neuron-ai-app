package com.neuron.ai

import android.app.Application
import com.neuron.ai.core.error.CrashRecorder
import com.neuron.ai.di.AppContainer

/**
 * Application entry point. Owns the dependency container so every layer
 * receives the same instances for the lifetime of the process.
 */
class NeuronApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Crash capture comes FIRST: every line after it can die and still
        // leave behind the reason (see CrashRecorder).
        CrashRecorder.install(this)
        // The native signal handler needs libneuron_llama, and loading that
        // library is not instant — arm it off the main thread instead of
        // stalling startup. Nothing native runs before a model load, which is
        // always seconds later.
        Thread({ CrashRecorder.armNativeHandler(this) }, "neuron-crash-arm").apply {
            isDaemon = true
            start()
        }
    }
}
