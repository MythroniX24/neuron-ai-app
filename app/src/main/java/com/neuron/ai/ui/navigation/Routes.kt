package com.neuron.ai.ui.navigation

/**
 * Central navigation contract. Future destinations (Projects, Files, Agent)
 * plug in here without restructuring the shell.
 */
object Routes {
    const val HOME = "home"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val CONVERSATIONS = "conversations"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDIT = "provider-edit?providerId={providerId}"
    const val TASKS = "tasks"

    fun chat(conversationId: String) = "chat/$conversationId"

    fun providerEdit(providerId: String?) =
        if (providerId == null) "provider-edit" else "provider-edit?providerId=$providerId"
}
