package com.neuron.ai.ui.navigation

/**
 * Central navigation contract. Future destinations (Projects, Tasks, Files,
 * Agent) plug in here without restructuring the shell.
 */
object Routes {
    const val HOME = "home"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"

    fun chat(conversationId: String) = "chat/$conversationId"
}
