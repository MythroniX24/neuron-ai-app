package com.neuron.ai.data.provider

/**
 * Prebuilt provider presets. Picking one fills the add-provider form so the
 * user only pastes an API key (and can optionally load models from /models).
 * All endpoints are OpenAI-compatible chat-completions APIs.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKeyUrl: String,
    val notes: String
)

object ProviderPresets {

    val ALL = listOf(
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKeyUrl = "platform.openai.com/api-keys",
            notes = "GPT-4o, GPT-4o mini and friends. Vision + tool calling."
        ),
        ProviderPreset(
            id = "gemini",
            displayName = "Google Gemini (OpenAI-compatible)",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            apiKeyUrl = "aistudio.google.com/apikey",
            notes = "Gemini 2.0/2.5 models via the OpenAI-compatibility layer."
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKeyUrl = "console.groq.com/keys",
            notes = "Ultra-fast Llama, DeepSeek and Qwen inference."
        ),
        ProviderPreset(
            id = "xai",
            displayName = "xAI (Grok)",
            baseUrl = "https://api.x.ai/v1",
            apiKeyUrl = "console.x.ai",
            notes = "Grok models with tool calling and vision."
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKeyUrl = "openrouter.ai/keys",
            notes = "One key, hundreds of models from every major lab."
        ),
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama (local)",
            baseUrl = "http://localhost:11434/v1",
            apiKeyUrl = "ollama.com",
            notes = "Run models on your own machine. No API key needed."
        ),
        ProviderPreset(
            id = "custom",
            displayName = "Custom / other",
            baseUrl = "",
            apiKeyUrl = "",
            notes = "Any OpenAI-compatible endpoint (vLLM, LM Studio, Together…)."
        )
    )

    fun byId(id: String?): ProviderPreset? = ALL.firstOrNull { it.id == id }
}
