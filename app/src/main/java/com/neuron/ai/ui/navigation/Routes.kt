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
    const val LOCAL_AI = "local-ai"
    const val DEBUG_CONSOLE = "debug-console"

    /** When the user arrives here right after a local-model load/runtime failure,
     *  the failed model id and a short human reason can be passed so the screen
     *  highlights the relevant section and pre-fills the copy-report affordance.
     *  Path-arg style: debug-console/{failedModelId}/{reason}
     *  (both args nullable; pass null to omit).
     */
    fun debugConsole(failedModelId: String? = null, reason: String? = null): String {
        val modelArg = failedModelId?.let { "$it" } ?: ""
        val reasonArg = reason?.let { it.replace("/", "|") } ?: ""
        return buildString {
            append(DEBUG_CONSOLE)
            if (failedModelId != null || reason != null) {
                append("/")
                append(modelArg)
                if (reason != null) {
                    append("/")
                    append(reasonArg)
                }
            }
        }
    }

    fun chat(conversationId: String) = "chat/$conversationId"

    fun providerEdit(providerId: String?) =
        if (providerId == null) "provider-edit" else "provider-edit?providerId=$providerId"
}
