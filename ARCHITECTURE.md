# Neuron-AI — Architecture

Neuron-AI is a mobile AI-agent platform built with **Kotlin** and **Jetpack Compose**.
This document describes the Phase 0 foundation: the architectural decisions, module
boundaries, and the seams every future feature must plug into.

---

## 1. Overall architecture

A single-module app (`:app`) with strict internal layering. Packages are the module
boundaries; when a layer grows, it graduates to a real Gradle module without API changes.

```
┌─────────────────────────────────────────────┐
│ ui/        Compose screens + ViewModels     │  presentation
├─────────────────────────────────────────────┤
│ di/        AppContainer (manual DI)         │  wiring
├─────────────────────────────────────────────┤
│ data/      Repository implementations       │  data
├─────────────────────────────────────────────┤
│ core/      Interfaces + domain models       │  domain (no Android UI deps)
└─────────────────────────────────────────────┘
```

Rules:

- `core/` never imports Compose, Activity, or any UI type.
- `ui/` talks to `core/` interfaces, never to concrete implementations.
- `data/` implements `core/` interfaces; consumers depend on the interface only.
- All dependencies point downward (`ui → di → data → core`). No cycles.

## 2. Package structure

```
com.neuron.ai
├── NeuronApplication.kt        # owns AppContainer for the process lifetime
├── MainActivity.kt             # single activity, edge-to-edge, hosts Compose
├── core/
│   ├── log/                    # Logger facade + secret redaction
│   ├── error/                  # NeuronError sealed hierarchy
│   ├── coroutines/             # DispatcherProvider
│   ├── security/               # SecureCredentialStore (Keystore-backed)
│   ├── settings/               # SettingsRepository (DataStore)
│   ├── provider/               # AIProvider, Model, ChatMessage, StreamEvent
│   ├── agent/                  # Agent, AgentRuntime, Tool, ToolRegistry
│   ├── task/                   # Task, TaskManager
│   ├── permissions/            # Capability, PermissionManager
│   ├── conversation/           # Conversation, Message, ConversationRepository
│   ├── workspace/              # Project, Workspace (sandboxed filesystem)
│   ├── memory/                 # Memory seam
│   └── integration/            # BrowserSession, TerminalSession seams
├── data/
│   ├── db/                     # Room entities, DAO, database, codecs
│   ├── conversation/           # RoomConversationRepository
│   ├── provider/               # ProviderRepository, OpenAI-compatible provider, wire codec
│   ├── agent/                  # ToolUsingAgent, DefaultToolExecutor
│   ├── tool/                   # InMemoryToolRegistry, SafeTools
│   ├── attachment/             # AttachmentStore (app-private storage)
│   ├── permissions/            # SessionPermissionManager
│   └── task/                   # DefaultTaskManager
├── di/
│   └── AppContainer.kt         # explicit manual dependency graph
└── ui/
    ├── theme/                  # design system: colors, type, shapes, spacing
    ├── components/             # shared components (composer, cards, states)
    ├── navigation/             # Routes
    ├── home/  chat/  settings/ # screens + ViewModels + factories
    └── NeuronApp.kt            # NavHost shell
```

## 3. UI architecture

- **Single Activity + Compose.** No fragments, no XML layouts.
- **Navigation:** Navigation-Compose with a central `Routes` object. Screens receive
  navigation lambdas (`onOpenChat`, `onBack`) and never touch the `NavController`
  themselves — this keeps screens previewable and testable.
- **State:** Unidirectional data flow. Screens observe `StateFlow` from ViewModels via
  `collectAsStateWithLifecycle()` and report events upward through function calls.
- **ViewModels** are created through explicit `ViewModelProvider.Factory` classes that
  receive `AppContainer`. No `by viewModels { … }` magic inside screens.
- **Edge-to-edge** is enabled; screens pad with `systemBarsPadding()`.

## 4. State management

| Scope            | Mechanism                                      |
|------------------|------------------------------------------------|
| UI state         | `StateFlow` in ViewModels (`HomeUiState`)      |
| Repository state | `MutableStateFlow` inside data implementations |
| Preferences      | DataStore (`SettingsRepository`)               |
| Persistence      | Room (`ConversationRepository` via `RoomConversationRepository`) |

All state is observable through `kotlinx.coroutines.flow.Flow`; nothing caches UI state
globally. Repository writes run on the IO dispatcher; Room flows keep the UI reactive.

## 5. AI provider architecture

`core/provider/AIProvider.kt` defines the transport-agnostic contract:

- `ChatMessage` — role + content; no provider field names leak above this layer.
- `Model` — id, display name, tool support, context window.
- `CompletionRequest` → `Completion` (one-shot) or `Flow<StreamEvent>` (streaming).
- `StreamEvent` — `Delta`, `ToolCallRequested`, `Failed`, `Completed`.
- `ProviderConfig` — user-configured endpoint. **Only a `credentialKey` reference is
  stored**; the secret itself lives in `SecureCredentialStore`.

Phase 1 adds an `OpenAICompatibleProvider` implementation plus a `ProviderRegistry`.
No provider logic is hard-coded anywhere in the UI.

## 6. Agent architecture

`core/agent/Agent.kt`:

- `Agent.run(goal): Flow<AgentEvent>` — agents are cold flows of events.
- `AgentEvent` covers visible activity (`ActivityStarted`/`ActivityUpdated` with
  `AgentActivity` step titles like "✓ Searching web"), `TextDelta`, `Finished`, `Failed`.
- Agents must expose only user-meaningful steps — never chain-of-thought.
- `AgentRuntime` owns run lifecycles (`start`, `cancel`, `activityOf`); Phase 2 makes
  it concurrent and background-capable.

## 7. Tool system

- `Tool` — pure contract: `id`, `title`, `description`,
  `requiredCapabilities`, `parametersSchemaJson`.
- `ToolExecutor` — executes tools and **enforces permissions**; returns `ToolResult`
  (`Success`/`Failure`) as values, not exceptions.
- `ToolRegistry` — observable registration; Phase 0 ships an in-memory implementation.

## 8. Task system

`core/task/Task.kt` fixes the model now: a `Task` is user-visible work that may outlive
a chat turn (`QUEUED → RUNNING → PAUSED → DONE/FAILED/CANCELLED`). `TaskManager` is the
seam; real background execution arrives in Phase 2 within Android's constraints
(foreground services / WorkManager).

## 9. Permission system

`core/permissions/PermissionManager.kt`:

- `Capability` — FILESYSTEM_READ/WRITE, NETWORK, BROWSER, TERMINAL, NOTIFICATIONS.
- Tools declare required capabilities; the manager gates execution and surfaces
  `PermissionRequest` objects for user decisions. Android system permissions map onto
  the same mechanism, so the UI has one consistent permission experience.

## 10. Storage

| Concern            | Technology                            | Phase |
|--------------------|---------------------------------------|-------|
| Settings           | Preferences DataStore                 | 0     |
| Secrets            | EncryptedSharedPreferences (Keystore) | 0     |
| Conversations      | Room (`NeuronDatabase`, v1)           | 1     |
| Attachments        | App-private files + metadata in Room  | 1     |
| Provider configs   | JSON file in app-private storage      | 1     |
| Projects/workspace | Room + sandboxed root                 | 2     |

## 11. Security

- **Secrets:** `SecureCredentialStore` wraps Android Keystore (AES256-GCM master key).
  If initialization fails, the factory degrades to a **non-persisting in-memory store**
  and logs a warning — it never falls back to plain-text persistence. API keys are
  written to it the moment a provider is saved, and are read only into a request's
  `Authorization` header.
- **Redaction:** every log line passes through `redactSecrets()`, which masks values of
  keys matching `key|token|secret|password|credential|authorization`.
- **Logs:** all logging goes through the `Logger` facade — no direct `Log.*` calls.
- **Backups:** `allowBackup` is on; when secrets become persistent in Phase 1, switch to
  backup-excluding rules for the encrypted prefs file.
- **Workspace sandbox:** `SafeTools.FileRead`/`FileSearch` confine all access to the
  `filesDir/workspace` root; canonical-path checks reject `../` and absolute escapes
  (covered by unit tests).
- **Attachments:** imported files are copied into app-private storage; shared-storage
  URIs are never passed to tools or providers directly.

## 12. Browser integration

`core/integration/BrowserSession.kt` is the headless-browser seam (navigate, read page
text, act on elements, snapshot visual state). Phase 2 implements it over an embedded
engine behind this interface — callers never import WebView types.

## 13. Terminal integration

`core/integration/TerminalSession.kt` — start/stop a session, stream combined output,
execute commands, observe exit codes. The Phase 2 implementation will define its exact
execution sandbox; the contract is intentionally transport-neutral.

## 14. Project / workspace system

`core/workspace/ProjectWorkspace.kt`: a `Project` is a named root plus context.
`Workspace` is the only filesystem surface agents can touch, with directory listing,
read, write, delete, and change observation — all confined to the project root.

## 15. Future scalability

Deliberate decisions that keep the codebase open:

1. **Manual DI (`AppContainer`)** — explicit, inspectable, zero reflection. If the
   graph grows, migrate to Hilt without changing consumer call sites.
2. **Package-to-module graduation** — `core/provider`, `core/agent`, etc. can each
   become a Gradle module; boundaries are already enforced by convention.
3. **Reactive seams everywhere** — flows at every layer make background agents and
   streaming UI incremental additions, not rewrites.
4. **One error model** — `NeuronError` gives every future subsystem a consistent,
   user-presentable failure story.
5. **No fake functionality** — Phase 0 deliberately ships no mock AI replies; the chat
   UI works against the real repository seam so Phase 1 only adds a provider.

## Phase 1 implementation status

- **Provider system:** `OpenAICompatibleProvider` (OkHttp + SSE streaming, tool-calling
  wire format, vision data-URLs) behind the `AIProvider` seam; `ProviderRepository`
  owns user configs, connection testing, and per-conversation model binding.
- **Agent runtime:** `ToolUsingAgent` implements the streaming tool loop with a strict
  `goal.allowedTools` allowlist, a step budget, and user-facing activity events only.
- **Tools:** time, calculator (recursive-descent evaluator — no `javax.script`), text
  stats, workspace file read/search. New tools register without touching the loop.
- **Permissions:** `SessionPermissionManager` surfaces allow/deny decisions to the UI;
  grants last for the session and can be revoked.
- **Tasks:** `DefaultTaskManager` runs supervised coroutines with cancel/retry and
  NonCancellable status transitions.
- **Storage:** Room v1 for conversations and messages; attachments in app-private
  storage with metadata blobs; provider configs persisted as JSON (keys excluded).
- **Rendering:** hand-rolled Markdown (headings, lists, quotes, tables, code blocks
  with copy + basic highlighting) and LaTeX via `ru.noties:jlatexmath-android`.

## Key technical decisions

| Decision                          | Reasoning                                                        |
|-----------------------------------|------------------------------------------------------------------|
| Kotlin 2.0 + Compose (BOM)        | Modern declarative UI; no XML surface area to maintain           |
| minSdk 26                         | Adaptive icons + java.time without desugaring; covers ~97% devices |
| Material 3, light-first           | Product default is light; dark is first-class but secondary      |
| DataStore over SharedPreferences  | Coroutine-friendly, transactional,Flow-based                     |
| EncryptedSharedPreferences        | Keystore-backed secret storage with graceful degradation         |
| Manual DI                         | Transparency now; trivial Hilt migration later                   |
| Values-not-exceptions tool results| Agent loops must handle failure as data                          |
