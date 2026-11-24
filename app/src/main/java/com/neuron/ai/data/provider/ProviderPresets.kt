package com.neuron.ai.data.provider

/**
 * Prebuilt provider presets. Picking one fills the add-provider form so the
 * user only pastes an API key (or uses zero-key free providers).
 * All endpoints are OpenAI-compatible chat-completions APIs.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKeyUrl: String,
    val notes: String,
    val defaultModels: List<String> = emptyList(),
    val requiresApiKey: Boolean = true
)

object ProviderPresets {

    val ALL = listOf(
        ProviderPreset(
            id = "kilo",
            displayName = "Kilo AI (Zero-Key Free)",
            baseUrl = "https://ai-gateway.kilo.ai/api/v1",
            apiKeyUrl = "kilo.ai",
            notes = "Built-in free models (kilo-auto/free) with no API key required.",
            defaultModels = listOf("kilo-auto/free"),
            requiresApiKey = false
        ),
        ProviderPreset(
            id = "pollinations",
            displayName = "Pollinations AI (100% Free)",
            baseUrl = "https://text.pollinations.ai/openai",
            apiKeyUrl = "pollinations.ai",
            notes = "Free streaming models (OpenAI, Mistral, Qwen, DeepSeek) with zero keys or signups.",
            defaultModels = listOf("openai", "mistral", "qwen-coder", "deepseek-r1"),
            requiresApiKey = false
        ),
        ProviderPreset(
            id = "opencode",
            displayName = "OpenCode Free AI",
            baseUrl = "https://opencode.ai/inference/v1",
            apiKeyUrl = "opencode.ai",
            notes = "OpenCode inference gateway for coding and assistant models.",
            defaultModels = listOf("deepseek-chat", "qwen-2.5-coder"),
            requiresApiKey = false
        ),
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
            notes = "One key, hundreds of models from every major lab (including free-tier models)."
        ),
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama (local)",
            baseUrl = "http://localhost:11434/v1",
            apiKeyUrl = "ollama.com",
            notes = "Run models on your own machine. No API key needed.",
            requiresApiKey = false
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
