package com.neuron.ai

import android.app.Application
import com.neuron.ai.di.AppContainer

/**
 * Application entry point. Owns the dependency container so every layer
 * receives the same instances for the lifetime of the process.
 */
class NeuronApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
