# Neuron-AI ProGuard rules.
# Minification is disabled for Phase 0; these rules keep future reflective layers safe.

# Keep core abstraction surfaces if reflection-based tool/provider discovery is added later.
-keep class com.neuron.ai.core.agent.** { *; }
-keep class com.neuron.ai.core.provider.** { *; }

# Coroutines debug metadata
-dontwarn kotlinx.coroutines.**
