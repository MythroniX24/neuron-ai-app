# Neuron-AI

A mobile AI-agent platform built with **Kotlin** and **Jetpack Compose**.

Neuron-AI is designed from the ground up as an agent platform — not just a chatbot.
The roadmap spans AI chat with multiple custom providers, a coding agent, browser and
terminal agents, tool calling, workspace management, and background tasks.

**Current status: Phase 0 — Foundation & Architecture.**

## Phase 0 — what ships today

- ✅ Kotlin + Jetpack Compose (Material 3) foundation, single-activity
- ✅ Light-first design system: colors, typography, spacing, shapes, shared components
- ✅ Dark theme architecture (Light / Dark / System in Settings)
- ✅ Home screen with greeting, quick actions, and chat composer
- ✅ Chat screen with reactive message flow
- ✅ Core abstractions: `AIProvider`, `Agent`, `Tool`, `Task`, `PermissionManager`,
  `Conversation`, `Project`/`Workspace`, `Memory`, `BrowserSession`, `TerminalSession`
- ✅ Secure credential storage (Android Keystore) + log redaction
- ✅ Settings persistence via DataStore
- ✅ Unit tests for the data layer
- ✅ `ARCHITECTURE.md` documenting every layer and decision

There is deliberately **no mock AI** in Phase 0 — provider connectivity arrives in
Phase 1 behind the already-defined `AIProvider` seam.

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
| **1 — Core Neuron-AI** | Real AI providers (OpenAI-compatible), streaming chat, markdown, conversation persistence (Room), provider settings |
| **2 — Advanced Agent Platform** | Agents, tool calling, browser/terminal integrations, workspaces, tasks, background execution |

## License

TBD — all rights reserved until a license is chosen.
