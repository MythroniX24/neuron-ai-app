# Neuron-AI

A mobile AI-agent platform built with **Kotlin** and **Jetpack Compose**.

Neuron-AI is designed from the ground up as an agent platform — not just a chatbot.
The roadmap spans AI chat with multiple custom providers, a coding agent, browser and
terminal agents, tool calling, workspace management, and background tasks.

**Current status: Phase 1 — Core Neuron-AI.**

## Phase 1 — what ships today

- ✅ Everything from Phase 0 (foundation, design system, abstractions)
- ✅ Real AI chat with **streaming** responses over any OpenAI-compatible endpoint
  (OpenAI, OpenRouter, Groq, Together, Ollama, LM Studio, vLLM…)
- ✅ Provider manager: add/edit/test/delete providers, custom base URLs, model IDs,
  custom headers, vision & tool toggles — API keys stored in the Android Keystore
- ✅ Per-conversation model selection from the chat top bar
- ✅ Persistent conversations (Room): rename, delete, search, continue old chats
- ✅ Markdown rendering: headings, lists, quotes, tables, links, code blocks with
  syntax highlighting and copy
- ✅ LaTeX math rendering (JLaTeXMath) with graceful fallback
- ✅ File attachments: picker, previews, removal; images sent as vision input when
  the provider supports it
- ✅ Agent runtime v1: tool loop with streaming, agent activity UI (✓ ⟳ steps)
- ✅ Safe foundational tools: time, calculator, text stats, workspace file read/search
- ✅ Permission system: tools request capabilities, user allow/deny dialog, revocable
- ✅ Task manager: stop running tasks, clear finished, statuses surfaced in UI
- ✅ **Local AI (on-device inference)**: run GGUF models fully on-device via
  llama.cpp — no API key, no internet at inference time. Import from file or
  download from the Hugging Face Hub (search, variant/quantization picker,
  progress with pause/resume, Wi-Fi-only gate, sha256 integrity checks).
  Heavily optimized: per-ISA CPU kernel variants (KleidiAI, dotprod/i8mm/SVE
  — best match picked per device at load time) and optional Vulkan GPU
  offload with flash attention + Q8_0 KV cache, with automatic CPU fallback.
  Single-active model with mmap loading, per-model benchmark (tokens/sec),
  curated recommended list with RAM-fit guidance, and a chat-model switcher
  integration with a live loading state.
  Capability-aware routing: each GGUF's own chat template/architecture/context
  decide what a turn may use (tools, vision, capped window), and the live agent
  timeline shows that resolution plus any degradation. Thermal/battery aware:
  the app reads thermal status, headroom, battery and power-save mode and
  throttles the next on-device answer (fewer threads, GPU off, shorter answers)
  instead of melting down mid-sentence.
- ✅ Unit tests for the provider wire protocol, tools, permissions, tasks, storage,
  the agent tool loop, the GGUF parser and the Hub/download stack

There is deliberately **no mock AI** anywhere — the chat works against real providers
you configure yourself.

## Building

Requirements: **JDK 17**, Android SDK 34, **NDK + CMake 3.22** (for the bundled
llama.cpp local-inference bridge; Android Studio installs these via SDK Manager →
SDK Tools). If the llama.cpp sources cannot be fetched at build time the bridge
compiles to a stub and the app reports local inference as unavailable — cloud
features are unaffected.

```bash
./gradlew assembleDebug     # debug APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest # unit tests
```

Or open the project in Android Studio (Ladybug or newer) and press Run.

### CI — automatic APK on every push

`.github/workflows/android-ci.yml` builds a debug APK on every push/PR to `main`
and uploads it as a workflow artifact (**Actions → android-ci → build-debug**).
Pushing a tag like `v0.1.0` additionally builds a release APK and attaches it to a
GitHub Release.

## Project layout

```
app/src/main/java/com/neuron/ai
├── core/     domain interfaces & models (pure Kotlin, no UI deps)
├── data/     repository implementations
├── di/       AppContainer — explicit manual dependency graph
└── ui/       Compose screens, ViewModels, design system
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design.

## Roadmap

| Phase | Focus |
|-------|-------|
| **0 — Foundation** ✅ | Architecture, design system, abstractions, polished shell |
| **1 — Core Neuron-AI** ✅ | Real AI providers, streaming chat, markdown + LaTeX, Room persistence, provider settings, agent tools, permissions |
| **2 — Advanced Agent Platform** | Coding/browser/terminal agents, workspaces, project management, background execution |
| **Local AI** 🔶 | ✅ Import/download/run GGUF models, HF search, download manager, benchmark, GPU (Vulkan) offload + per-ISA CPU variants + perf prefs, capability-aware routing in the agent timeline, thermal/battery throttling · next: vision projector (mmproj) support, per-model routing rules |

## License

TBD — all rights reserved until a license is chosen.
