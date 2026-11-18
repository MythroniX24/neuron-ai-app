# Neuron-AI — Agent instructions

## CI / GitHub workflow (standing rule)

Whenever GitHub access is available for this repository (connected via the
Freebuff GitHub integration), the agent MUST:

1. Commit the changes it makes.
2. Push them — first the working branch, then `main` once the work is ready.
3. Trigger and **follow** the `android-ci` workflow (`.github/workflows/android-ci.yml`)
   and keep watching it until a **debug APK artifact** (`neuron-ai-debug-apk`)
   is actually produced by the `build-debug` job's "Upload debug APK artifact"
   step. Do not stop after a push — an APK must be generated.
4. If a CI run fails, diagnose from the workflow logs, fix the cause, and
   re-push / re-run until the run succeeds and the APK artifact exists.

In short: with GitHub access available, always commit + push, and follow CI
through to a generated APK.

## Project quick reference

- **What it is:** an Android AI-agent platform — one chat where the agent can
  search the web, browse pages, use a sandboxed workspace, run shell commands,
  edit/build/test code, remember facts, and run GGUF models fully on-device.
- **Stack:** Kotlin 2.x, Jetpack Compose (Material 3), Room, DataStore,
  OkHttp/SSE, kotlinx.serialization, bundled llama.cpp (CMake/NDK).
  Single Gradle module `:app`, namespace/applicationId `com.neuron.ai`,
  `minSdk` 26, `targetSdk`/`compileSdk` 34, version in `app/build.gradle.kts`.
- **Layering:** `ui → di → data → context → core`. `core/` has no Android UI
  dependencies; `ui/` talks to `core/` interfaces only; `di/AppContainer.kt` is
  the single explicit dependency graph (no Hilt/Koin).
- **The agent:** `data/agent/ToolUsingAgent.kt` is the only agent loop
  (streaming, 24 steps, per-problem retries, reply-continuation nudges, marker
  cleanup). Tools live in `data/tool/` and are registered in `AppContainer`.
- **Product rule:** there is ONE chat — coding, terminal, browser, search and
  memory are tools/capabilities of the same runtime, never separate chats or
  runtimes.

## Build & test

```bash
./gradlew testDebugUnitTest     # JVM suite (400+ tests) — run after logic changes
./gradlew assembleDebug         # debug APK
./gradlew assembleRelease       # signed only when NEURON_KEYSTORE_* env vars exist
```

JDK 17, Android SDK 34, NDK + CMake 3.22 are required for native builds. If the
llama.cpp sources cannot be fetched the bridge compiles to a stub and local
inference reports itself unavailable — cloud features must keep working.

Keep the Gradle wrapper and lockfile untouched unless the task is a dependency
change. Never commit secrets, keystores or `.b64` files; release signing is
env-driven (see `SIGNING.md`).

## Code conventions

- Values, not exceptions, at subsystem boundaries (`ToolResult`,
  `NeuronError`); failures of tools must be data the agent can reason about.
- User-facing text comes from the agent/UI layers, never chain-of-thought;
  never surface internal tool-log markers (`[used tool: …]`, `[tool result] …`)
  in a reply.
- Behaviour that affects prompts, routing, throttling or context must stay in
  pure, unit-tested helpers rather than inside Android-bound classes.
- Security rules that must not regress: file tools stay inside their root —
  the shared-storage root the user granted via Settings → Storage access, or an
  attached workspace — page content stays fenced as untrusted data, destructive
  operations always re-prompt, secrets never reach logs or plain-text storage.

## Documentation map

| File | Contents |
|---|---|
| `README.md` | product overview, feature list, tool catalog, build/CI, roadmap |
| `ARCHITECTURE.md` | package map, agent loop, providers, context pipeline, storage, local AI |
| `CONTEXT_ARCHITECTURE.md` | context assembly spec: what is implemented, what is planned |
| `SIGNING.md` | release keystore, GitHub Secrets, artifacts, tags |
| `AGENTS.md` | this file — CI rule, quick reference, conventions |

Update the docs in the same change whenever behaviour, tools, settings or CI
change. Keep them describing the app as it actually is.
