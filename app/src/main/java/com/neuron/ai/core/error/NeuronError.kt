package com.neuron.ai.core.error

/**
 * Unified error model for Neuron-AI. Every subsystem surfaces failures as a
 * [NeuronError] subtype so the UI can present one consistent error experience.
 */
sealed class NeuronError(open val message: String, open val cause: Throwable? = null) {

    data class Network(
        override val message: String,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    data class Provider(
        override val message: String,
        val providerId: String? = null,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    data class Tool(
        override val message: String,
        val toolId: String? = null,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    data class Permission(
        override val message: String,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    data class Storage(
        override val message: String,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : NeuronError(message, cause)

    /** Short, human-friendly text suitable for toasts, banners and chat errors. */
    val userMessage: String
        get() = when (this) {
            is Network -> "Network problem. Check your connection and try again."
            is Provider -> "The AI provider returned an error."
            is Tool -> "A tool failed to run."
            is Permission -> "Permission was not granted."
            is Storage -> "Could not access local storage."
            is Unknown -> "Something went wrong."
        }
}
