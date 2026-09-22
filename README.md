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
- ✅ Unit tests for the provider wire protocol, tools, permissions, tasks and storage

There is deliberately **no mock AI** anywhere — the chat works against real providers
you configure yourself.

## Building

Requirements: **JDK 17**, Android SDK 34.

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

## License

TBD — all rights reserved until a license is chosen.
