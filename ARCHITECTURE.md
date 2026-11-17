# Neuron-AI — Architecture

Neuron-AI is an Android AI-agent platform built with **Kotlin** and **Jetpack
Compose**. This document describes the architecture **as implemented** in release
1.2.5: module boundaries, the agent loop, the tool system, context engineering,
storage, security, and the on-device inference stack.

---

## 1. Layering

A single-module app (`:app`) with strict internal layering. Packages are the
module boundaries; a layer can graduate to a real Gradle module without API
changes.

```
┌─────────────────────────────────────────────┐
│ ui/        Compose screens + ViewModels     │  presentation
├─────────────────────────────────────────────┤
│ di/        AppContainer (manual DI)         │  wiring
├─────────────────────────────────────────────┤
│ data/      Repository implementations       │  data
├─────────────────────────────────────────────┤
│ context/   Context pipeline (budget/rank)   │  request assembly
├─────────────────────────────────────────────┤
│ core/      Interfaces + domain models       │  domain (no Android UI deps)
└─────────────────────────────────────────────┘
```

Rules:

- `core/` never imports Compose, Activity, or any UI type.
- `ui/` talks to `core/` interfaces, never to concrete implementations.
- `data/` implements `core/` interfaces; consumers depend on the interface only.
- All dependencies point downward (`ui → di → data → context → core`). No cycles.

## 2. Package map

```
com.neuron.ai
├── NeuronApplication.kt          # owns AppContainer for the process lifetime
├── MainActivity.kt               # single activity, edge-to-edge, hosts Compose
├── core/
│   ├── agent/                    # Agent, ToolPolicy, AgentGoal, AgentEvent,
│   │                             #   AgentRun/AgentSession/AgentRuntime, Tool,
│   │                             #   AgentStepMapper (UI-safe action details)
│   ├── conversation/             # Conversation, Message, Attachment,
│   │                             #   AgentStepRecord, MessageMetadata,
│   │                             #   RichItems(+Parser), ConversationRepository
│   ├── provider/                 # AIProvider, Model, ChatMessage, StreamEvent,
│   │                             #   CompletionRequest/Completion
│   ├── permissions/              # Capability, AccessMode, DecisionScope,
│   │                             #   PermissionRequest/Decision/Manager
│   ├── task/                     # Task, TaskManager
│   ├── memory/                   # MemoryEntry, MemoryType, MemoryStore
│   ├── workspace/                # Project/Workspace models
│   ├── web/                      # SearchProvider, PageFetcher, search models
│   ├── integration/              # BrowserSession, TerminalSession seams
│   ├── settings/                 # SettingsRepository, ThemeMode,
│   │                             #   ResponseSpacing, ReplySpacingProfile
│   ├── security/                 # SecureCredentialStore (+ Keystore factory)
│   ├── error/                    # NeuronError, CrashRecorder
│   ├── log/                      # Logger facade + secret redaction
│   └── coroutines/               # DispatcherProvider
├── data/
│   ├── db/                       # Room entities, DAOs, mappers, codecs,
│   │                             #   NeuronDatabase (v5), task + memory stores
│   ├── conversation/             # RoomConversationRepository
│   ├── provider/                 # ProviderRepository, ProviderPresets,
│   │                             #   OpenAICompatibleProvider + wire codec,
│   │                             #   ModelCapabilities
│   ├── agent/                    # ToolUsingAgent, DefaultToolExecutor,
│   │                             #   DefaultAgentRuntime, TextToolCallParser
│   ├── tool/                     # SafeTools, WorkspaceTools, WebTools,
│   │                             #   BrowserTools, TerminalAndCodingTools,
│   │                             #   RichItemsTool, ReplySpacingTool,
│   │                             #   InMemoryToolRegistry
│   ├── web/                      # DuckDuckGo/Bing/Mojeek adapters,
│   │                             #   SearchOrchestrator, aggregator, reranker,
│   │                             #   sanitizer, HttpPageFetcher
│   ├── browser/                  # AndroidBrowserManager (per-conversation
│   │                             #   WebView sessions)
│   ├── memory/                   # MemoryManager, RoomMemoryStore
│   ├── workspace/                # WorkspaceManagerImpl, WorkspaceContext
│   ├── terminal/                 # TerminalManager/TerminalSession
│   ├── task/                     # DefaultTaskManager, TaskRecordStore
│   ├── permissions/              # SessionPermissionManager
│   ├── attachment/               # AttachmentStore (app-private files)
│   └── local/                    # On-device inference (see §13)
├── context/
│   ├── model/                    # ContextItem, ContextPriority, ContextSourceType
│   ├── providers/                # Conversation/Memory/Task context providers
│   ├── ranker/                   # ContextRanker
│   ├── budget/                   # TokenBudgetManager, TokenBudget
│   ├── compaction/               # CompactionManager, StructuredState
│   ├── compression/              # ToolResultProcessor
│   └── orchestrator/             # ContextOrchestrator
├── di/
│   └── AppContainer.kt           # explicit manual dependency graph
└── ui/
    ├── NeuronApp.kt              # drawer shell + NavHost
    ├── navigation/Routes.kt      # route constants + builders
    ├── chat/                     # ChatScreen, ChatViewModel(+Factory),
    │                             #   AgentTimelineCard, RichRecommendationCard,
    │                             #   AttachmentSheet, TerminalPanel,
    │                             #   EmptyChatHero, ChatContextEngine, AiNaming
    ├── conversations/            # ConversationsScreen + ViewModel
    ├── drawer/                   # AppDrawer (chat list, new chat, settings)
    ├── providers/                # ProvidersScreen, ProviderEditScreen
    ├── settings/                 # Settings, LocalAi, LocalModelSettings,
    │                             #   DebugConsole screens + ViewModels
    ├── tasks/                    # TasksScreen + ViewModel
    ├── permissions/              # PermissionDialogHost
    ├── markdown/                 # parser, renderer, highlighter, inline Markdown
    ├── components/               # shared components, DraggablePanel, Motion
    └── theme/                    # colors, typography, spacing, shapes
```

## 3. UI architecture

- **Single Activity + Compose.** No fragments, no XML layouts.
- **Drawer shell.** `NeuronApp` wraps the whole `NavHost` in a
  `ModalNavigationDrawer`; the drawer lists conversations (pin/rename/delete),
  opens a new chat and reaches Settings.
- **One chat surface.** `Routes.HOME` *is* the chat screen: a fresh conversation
  id is minted per visit and the conversation is created lazily on first send or
  capability change, so the composer is identical from the first frame.
- **Navigation:** Navigation-Compose with a central `Routes` object
  (`home`, `chat/{conversationId}`, `conversations`, `providers`,
  `provider-edit`, `settings`, `tasks`, `local-ai`,
  `local-model-settings/{modelId}`, `debug-console`). Screens receive navigation
  lambdas and never touch the `NavController` themselves.
- **State:** unidirectional data flow. Screens observe `StateFlow` from
  ViewModels with `collectAsStateWithLifecycle()` and report events upward.
- **ViewModels** are created through explicit `ViewModelProvider.Factory`
  classes that receive `AppContainer`.
- **Edge-to-edge** is enabled; screens pad with `systemBarsPadding()`.
- **Theme:** Material 3, light-first, dark and system modes; the root paints the
  app background and provides `LocalContentColor` so screens outside a `Surface`
  stay readable in dark mode.

### Chat screen anatomy

Composer (`Ask Neuron…`), model chip with the selected provider/model, the `+`
controls sheet (Camera / Photos / Files, Terminal, Browser, Workspace), the
inline agent timeline (collapsible run header, per-step tool rows with action
details and narration), assistant bubbles with Markdown/LaTeX/rich cards and
per-message actions (copy, retry, regenerate, edit & resend), the queued-message
bar with undo, an attachment strip, and the draggable terminal panel.

## 4. Agent runtime and the tool loop

`core/agent/Agent.kt` defines the contract:

```kotlin
interface Agent {
    val id: String
    val displayName: String
    fun run(goal: AgentGoal): Flow<AgentEvent>
}
```

- `AgentGoal` carries the instruction, current-message attachments, prior turns
  (`history`), an optional system-prompt suffix and a `ToolPolicy`
  (`All` = every registered tool, `Only` = strict intersection).
- `AgentEvent` is the only channel to the UI: `ActivityStarted` /
  `ActivityUpdated` (user-meaningful step titles), `PermissionRequested` /
  `PermissionResolved`, `IntermediateMessage` (mid-loop narration),
  `RichContent`, `ReplySpacing`, `TextDelta`, `TextBuffer` (replaces the visible
  buffer after a stream repair), `Finished`, `Failed`.
- `DefaultAgentRuntime` owns run lifecycles as observable `AgentRun` state
  machines (`QUEUED → RUNNING → WAITING_FOR_PERMISSION →
  COMPLETED/FAILED/CANCELLED`). Terminal states are frozen — a late event can
  never resurrect a run — and runs are cancellable and re-observable from any
  screen.

### `ToolUsingAgent` — the single agent

- Streams one completion per loop iteration, appends tool calls and results to
  the working history, and repeats.
- **Budgets:** `maxSteps = 24` per turn (enough for
  search → read → edit → build → test → fix), `maxAttemptsPerProblem = 3` per
  *distinct* failing call (keyed on tool id + normalised args), so one stubborn
  failure cannot consume another problem's budget.
- **Reply vs work:** a text-only turn is normally the final answer, but if it
  merely *announces* a step ("Let me check the profile directly…", trailing `…`
  or `:`, or a first-person action cue) the loop re-prompts the model to perform
  it. `maxContinuationNudges = 3` bounds this, and closing offers
  ("Let me know if…") are never treated as a plan, so ordinary answers still end
  the turn.
- **Marker hygiene:** `[used tool: …]` / `[tool result] …` tokens are stripped
  everywhere (content sharing the line survives), pure tool-log artifacts such
  as `(Tool executed successfully with no output)` are removed, a marker-only
  reply is re-requested instead of displayed, and the cleaned text is emitted as
  `TextBuffer` so the visible bubble is repaired.
- **Recovery:** one bounded retry for empty completions, a toolless retry when a
  provider rejects tools, and a bounded restore of tools if that backfires.
  Plain-text or "function-assignment" tool markup (some local models, e.g. Qwen
  styles) is recovered by `TextToolCallParser`.
- **System prompt:** written in sections — tools, loop behaviour, REPLY vs WORK,
  OUTPUT FORMAT (no internal markers), retry policy, security fences, memory,
  attachments, rich recommendations, readability. A toolless goal (plain chat,
  small on-device model) gets a short prompt instead, so no context is wasted on
  tools that do not exist.

### Execution and permissions

`DefaultToolExecutor` wraps every call:

- permission gate per declared `Capability` (denial is terminal, never retried),
- `RiskLevel`-aware prompts (SAFE / ELEVATED / DESTRUCTIVE),
- per-tool timeout with a 60 s hard ceiling and structured `ToolResult.TimedOut`,
- one bounded retry (400 ms × attempt) for transient failures only,
- `ToolResult` values (`Success`/`Failure`/`TimedOut`/`Denied`) instead of
  exceptions, so the loop always has data to reason about.

## 5. Tools

`core/agent/Tool.kt`: `id`, `title`, `description`, `requiredCapabilities`,
`parametersSchemaJson`, optional output schema, `riskLevel`, `timeoutMs`.
`ToolRegistry` is observable; `InMemoryToolRegistry` is the implementation.
Tools are registered in `AppContainer` — adding a tool never touches the loop.

| Area | Tools | Notes |
|---|---|---|
| Basics | `time.now`, `math.evaluate`, `text.stats` | calculator is a recursive-descent evaluator (no `javax.script`) |
| Files | `fs.read`, `fs.search` | confined to `filesDir/workspace` |
| Workspace | `fs.list`, `fs.readfile`, `fs.writefile`, `fs.mkdir`, `fs.rename`, `fs.move`, `fs.copy`, `fs.delete`, `fs.searchfiles`, `fs.metadata` | canonical-path checks; delete carries `FILESYSTEM_DELETE` and always re-prompts |
| Web | `web.search`, `web.read` | keyless engines, fenced untrusted output |
| Google | `google.drive.*`, `google.gmail.*`, `google.sheets.*`, `google.docs.*` | thin proxies to the Cloudflare Worker, which holds the encrypted refresh token |
| Browser | `browser.open/read/find/links`, `browser.click/type/select` | side-effect tools always ask, even with the toggle ON |
| Terminal/code | `terminal.run`, `code.edit`, `code.patch`, `code.diff`, `code.build`, `code.test` | terminal toggle = standing consent; catastrophic-command guard stays |
| Memory | `memory.remember`, `memory.recall`, `memory.forget` | scope fixed by conversation context; secrets rejected |
| Presentation | `ui.rich_items`, `ui.compare`, `ui.reply_spacing` | validated JSON → `RichContent` / `ComparisonBlocks` / `ReplySpacing` events, persisted on the assistant message |

## 6. AI providers

`core/provider/AIProvider.kt` is transport-agnostic:

- `ChatMessage` — role, content, attachments, tool calls (no provider field
  names leak above this layer).
- `Model` — id, display name, tool support, context window.
- `CompletionRequest` → `Completion` (one-shot) or `Flow<StreamEvent>`
  (`Delta`, `ToolCallRequested`, `Failed`, `Completed`).
- `ProviderConfig` — user endpoint; **only a `credentialKey` reference is
  stored**, the secret lives in `SecureCredentialStore`.

Implementations:

- `OpenAICompatibleProvider` (OkHttp + SSE streaming, tool-call wire format,
  vision data-URLs), with `OpenAIWireCodec` handling the wire details and tool
  sequence repair for long conversations.
- `LocalAiProvider` — on-device inference behind the same interface (§13).
- `ProviderRepository` owns user configs, connection testing, model discovery
  and per-conversation model binding; presets cover OpenAI, Gemini
  (OpenAI-compatible), Groq, xAI, OpenRouter, Ollama and Custom.
- `ModelCapabilities` estimates TEXT / VISION / TOOL_CALLING from model ids plus
  the provider's config flags and rejects unsupported input *before* a request.

## 7. Context engineering

Two layers coexist:

**1) `ChatContextEngine` (in use).** Builds the model request from prioritised
sources under a character budget derived from the selected model's window:
current request → recent turns (last 24, tool rows compressed) → memory block →
tool results. It drops the trailing non-user rows and persisted error rows so
providers never see dangling tool results, and never sends whole workspaces or
unbounded history.

**2) `ContextOrchestrator` (shadow mode).** One assembly point: providers fetch
candidates in parallel → `ContextRanker` orders them (hard priority tiers;
keyword relevance and recency as tie-breaks) → `TokenBudgetManager` fits them to
the model's window (P0–P2 never dropped, 10 % safety margin, reserved output
tokens) → `CompactionManager` produces an extractive `StructuredState` if still
over budget → `ToolResultProcessor` keeps TOOL rows compressed (threshold
1 200 chars). Every provider call is failure-tolerant; an impossible fit falls
back to a safe minimal context (system + request + last 8 turns).
`ChatViewModel`.`buildHistory()` runs it alongside the legacy engine, logs both
side by side, and only returns the orchestrator's messages when
`USE_ORCHESTRATOR_CONTEXT` is enabled — default is shadow-only.

See [CONTEXT_ARCHITECTURE.md](CONTEXT_ARCHITECTURE.md) for the full spec and the
implemented-vs-planned breakdown.

## 8. Web search pipeline

- `core/web` defines `SearchProvider`, `PageFetcher` and the query/result models.
- `data/web` ships three keyless adapters (DuckDuckGo HTML, Bing HTML, Mojeek);
  `SearchOrchestrator` fans out in parallel, retries, trips a per-provider
  circuit breaker on failures, caches results (10 min TTL, 64 entries), and
  hands candidates to `ResultAggregator` + `SearchReranker` for dedup and
  ordering.
- `HttpPageFetcher` reads at most 512 KB per page, unwraps redirects and
  extracts readable text.
- `WebContentSanitizer` wraps page content in
  `<<<UNTRUSTED_WEB_DATA>>> … <<<END_UNTRUSTED_WEB_DATA>>>` and neutralises
  fence-spoofing inside the payload; the system prompt states that fenced
  content is data, never instructions. Sources are returned as `[n]` and stored
  as TOOL messages so citations stay with the answer.

## 9. Browser integration

`AndroidBrowserManager` keeps one real embedded WebView session per
conversation; `WebViewBrowserSession` implements the `BrowserSession` seam
(open, read text, find, links, click, type, select). The JS bridge only extracts
text/links — page scripts cannot call tools or mutate app state. The per-chat
Browser toggle (ON by default) enables read-only tools; side-effect tools always
raise an explicit permission request on top. Sessions are closed when the chat's
`ChatViewModel` is cleared, so no engine leaks between chats.

## 10. Memory

`RoomMemoryStore` (Room table) + `MemoryManager` behind the `MemoryStore`
interface. Types: USER_PREFERENCE (global), PROJECT (per workspace),
TASK/CONVERSATION reserved. Only explicit `memory.remember` writes persist —
nothing is captured automatically. `MemorySecretFilter` rejects API keys, tokens,
passwords and JWTs. The master switch lives in Settings; entries can be
inspected, deleted individually or cleared.

## 11. Workspace, terminal and coding

**Workspace.** `WorkspaceManagerImpl` owns roots under
`filesDir/workspaces/<id>`. `WorkspaceContext` performs every operation with
canonical-path validation (rejects `../`, absolute escapes and symlink
escapes out of the root). Reads run concurrently; every mutation
(`writeText`, `mkdirs`, `rename`, `move`, `copy`, `delete`) serialises behind a
per-workspace `Mutex`, so concurrent agent tasks cannot interleave writes.

**Terminal.** `TerminalManager` runs real `/system/bin/sh -c` processes with
streamed stdout/stderr, exit codes, working directory, per-command timeout
(120 s default, exit 124) and cooperative cancellation (SIGKILL → exit 130).
Drain readers are daemon threads and execution settles with a bounded join, so
an orphaned child (e.g. `sleep` left by `sh -c`) can never hang `execute()`.
The user panel and the AI share the same session per conversation.

**Coding tools.** `code.edit` (exact-unique replacement), `code.patch`
(anchor validation against the *current* file — a changed target fails loudly),
`code.diff`, and `code.build` / `code.test` through `BuildDetector`
(Gradle, Maven, npm, make, cargo, pip).

**Isolation.** Sessions, tasks and tool results are keyed by conversation id;
two chats sharing a workspace share files only, never session state.

## 12. Tasks, permissions, storage

**Tasks.** `DefaultTaskManager` runs supervised coroutines and persists every
transition through `TaskRecordStore` into Room. Stale RUNNING/QUEUED rows are
recovered as FAILED on restart; live activity, step progress and
waiting-for-permission state are mirrored from the chat turn.

**Permissions.** Capabilities: `FILESYSTEM_READ`, `FILESYSTEM_WRITE`,
`FILESYSTEM_DELETE`, `EXECUTE`, `NETWORK`, `BROWSER`, `TERMINAL`,
`NOTIFICATIONS`, `MEMORY`. `SessionPermissionManager` records decisions with
scope `ONCE / SESSION / WORKSPACE / ALWAYS`, keeps an inspectable decision log,
re-prompts unconditionally for DESTRUCTIVE work and exposes revocation.

**Storage.**

| Concern | Technology |
|---|---|
| Settings | Preferences DataStore (`theme_mode`, last provider/model) |
| Secrets | `EncryptedSharedPreferences` backed by the Android Keystore |
| Conversations & messages | Room `NeuronDatabase` **v5** — `conversations`, `messages`, `tasks`, `memory` (migrations 1→5) |
| Provider configs | JSON in app-private storage (keys excluded) |
| Attachments | app-private files + metadata columns |
| Workspaces | `filesDir/workspaces/<id>` |
| Local models | `filesDir/local-models/` |
| Local perf / routing | `filesDir/local-perf.json`, `filesDir/local-routing-rules.json` |

## 13. Local AI (on-device inference)

Local models are **just another `AIProvider`** — there is no parallel inference
path. The same agent loop, tool executor, context assembly and budgets apply;
the GGUF's declared context length drives the window exactly like a cloud
context window.

```
data/local/
├── GgufReader.kt             # bounded GGUF header/metadata parser (magic, arch,
│                             #   quantisation, context length) — format, not extension
├── LocalEngineLoader.kt      # JNI side: single-active load, streaming generation,
│                             #   tokens/sec benchmark, runtime backend discovery
├── LocalModelRecord.kt       # persisted record + load-state lifecycle
├── LocalModelRepository.kt   # models dir, import validation, ticks, orchestration
├── HfHubClient.kt            # free HF Hub API: GGUF search + repo files (size, sha256)
├── ModelDownloadManager.kt   # progress, pause/resume (Range), Wi-Fi gate, sha256,
│                             #   restart survival
├── ModelTransferService.kt   # foreground service so downloads survive leaving the screen
├── RecommendedModels.kt      # curated catalog with min-RAM guidance + device fit
├── LocalModelRouter.kt       # capability-aware routing per turn
├── LocalRoutingRules.kt      # per-model "use for text/tools/vision" rules
├── LocalThrottlePolicy.kt    # pure thermal/battery policy → threads, GPU, tokens
├── DeviceHealthMonitor.kt    # thermal status/headroom + battery + power-save watcher
├── PromptPrefix.kt           # pure KV prefix-reuse decision
├── CpuTopology.kt            # pure big/little classification + thread math
├── LoadMemoryBudget.kt       # RAM-fit estimate before a load
├── ContextTrimmer.kt         # on-device window trimming
├── ChatTemplateRenderer.kt   # GGUF chat template → prompt text
├── LocalPromptOptions.kt     # per-model prompt options
├── VisionProjector.kt        # mmproj discovery (which projector belongs to a model)
└── LocalAiProvider.kt        # the AIProvider implementation
```

Design rules:

- **Capability-honest:** the chat template decides tool support, the
  architecture decides vision, the declared context length is capped for phones.
  Vision is only claimed when an mmproj projector is actually present next to the
  GGUF; the image then goes through llama.cpp's `mtmd` encoder. Unsupported input
  is refused with an actionable reason; everything else is degraded visibly.
  The resolution (model, window, degradations, live throttle) is emitted as the
  first timeline row and persisted with the answer.
- **Single-active engine:** the previous model is fully unloaded before a new one
  loads, enforced in Kotlin and native code.
- **mmap weights:** llama.cpp maps the model file; weight bytes are never
  executed.
- **Integrity:** downloads verify sha256 against the Hub's LFS oid; mismatches
  delete the file. Imports validate GGUF magic and header.
- **Native build:** `app/src/main/cpp` pins a llama.cpp commit and builds via
  CMake with `-O3` and `GGML_CPU_ALL_VARIANTS` (one optimised kernel set per ISA
  level — armv8.0, dotprod, i8mm, SVE — plus KleidiAI; the best match is
  dlopen'd at load) and runtime backend discovery. Vulkan is a runtime-loaded
  backend (arm64, flash attention + Q8_0 KV cache); every GPU load retries once
  on CPU, so failures degrade to slower, never to broken. If the fetch fails at
  build time the bridge compiles to a stub and local inference reports itself
  unavailable.
- **Thermal/battery:** `DeviceHealthMonitor` publishes one snapshot; the pure
  `LocalThrottlePolicy` turns it into the load actually used (threads, GPU veto,
  token ceiling) with a one-line reason in Settings and in the chat timeline.
  The throttle applies at the next load on purpose — reloading mid-generation
  would abort an answer being read.
- **Prompt-prefix reuse:** the bridge keeps a shadow copy of the tokens in
  sequence 0, trims to the shared prefix with `llama_memory_seq_rm`, and decodes
  only the new tail. Image turns bypass reuse and clear the shadow copy.
- **Big-core threading:** `CpuTopology` reads per-core peak frequencies and
  threads the fast cluster; the thermal cap still wins.
- **Per-model settings:** custom instructions, routing rules, context window and
  answer-length overrides, GPU/thread choice, load/unload, benchmark, delete.
- **Debug console:** engine build info, device memory, model files, llama.cpp's
  log, and `CrashRecorder` (last-resort capture for native crashes that Kotlin
  cannot catch).

## 14. Rendering

- Hand-rolled Markdown parser + renderer: headings, lists, quotes, tables,
  links, code blocks with copy and basic syntax highlighting.
- LaTeX via `ru.noties:jlatexmath-android` with graceful fallback.
- Native rich cards (`RichRecommendationSet`): image, year/meta, badge,
  description, key-fact rows, status with tone, source links. Validated by
  `RichItemsParser` before anything renders, persisted with the message.
- Native comparison surfaces (`ComparisonUiBlock`): `side_by_side`,
  `feature_table`, `score`, `pros_cons` and an interactive `priority` card.
  `ComparisonBlocksParser` (pure Kotlin, fully unit-tested) accepts type/key
  synonyms, caps every list and string, and only lets http(s) image URLs
  through. Each block is stored as a `ComparisonPlacement(anchor, block)` in
  `MessageMetadata.comparisonBlocks`, and `ComparisonLayout.segments()` splits
  the answer at those anchors so text and cards interleave in reading order.
- Card images: `EntityImageResolver` (implemented by `WikipediaImageResolver`)
  turns an `imageQuery` phrase into a real public image — keyless, TTL/LRU
  cached, one bounded request; the ViewModel keeps the resolved map in a
  `StateFlow` shared by live and persisted messages. `SmartEntityImage`
  (Coil `SubcomposeAsyncImage`, `ContentScale.Fit`) shows shimmer → image, or a
  neutral icon when nothing trustworthy was found. A half-streamed fenced
  block is stripped by `ComparisonBlocksParser.splitPendingStream()` and
  replaced with a skeleton, so raw JSON never reaches the bubble.
- Reply spacing: `ResponseSpacing` presets (compact / comfortable / relaxed /
  airy) applied independently to paragraphs and lists, chosen by the model
  through `ui.reply_spacing` and persisted per message (legacy single-value
  spacing is still read for old rows).

## 15. Security and privacy

- **Secrets:** `SecureCredentialStore` wraps the Android Keystore
  (AES256-GCM master key); failure degrades to a non-persisting in-memory store,
  never to plain text. Keys are read only into a request's `Authorization`
  header.
- **Redaction:** every log line passes through `redactSecrets()`, masking values
  of `key|token|secret|password|credential|authorization`. All logging goes
  through the `Logger` facade.
- **Sandbox:** workspace and file tools cannot escape their roots; attachments
  are copied into app-private storage, shared-storage URIs are never handed to
  tools or providers.
- **Prompt injection:** all page-derived text is fenced and declared as data;
  the browser bridge cannot call tools.
- **Consent:** destructive operations always re-prompt; side-effect browser
  actions always ask; the terminal toggle is explicit standing consent that the
  agent cannot flip itself.
- **Backups:** `allowBackup` is on; encrypted prefs are the only secret store and
  contain no exportable key material.

## 16. Testing

A JVM suite of 400+ tests (`./gradlew testDebugUnitTest`), organised by package:

- `data/provider` — wire codec, streaming, tool-sequence repair, model
  capabilities, long conversations
- `data/agent` — tool loop integration (including continuation-after-reply and
  marker cleanup), tool executor timeouts/retries, plain-text tool-call parsing
- `data/tool` — safe tools, workspace tools, browser tools, patch guards, rich
  items, reply spacing, arithmetic evaluator
- `data/web` — keyless engines, search pipeline, page extraction/ranking
- `data/workspace` — sandbox boundaries and concurrency
- `data/terminal` — session lifecycle, timeouts, cancellation
- `data/task`, `data/permissions`, `data/memory` — state machines and scopes
- `data/local` — GGUF parser, hub client, downloads, routing rules, throttling,
  prefix reuse, topology, memory budget, template rendering, recommended models
- `context/*` — budget, ranking, compaction, providers, orchestrator
- `ui/chat` — context engine and context assembly

## 17. Key decisions

| Decision | Reasoning |
|---|---|
| Kotlin + Compose, single module | Modern declarative UI; package-level boundaries are enough for this size |
| minSdk 26 | Adaptive icons + `java.time` without desugaring; covers ~97 % of devices |
| Material 3, light-first | Product default is light; dark is first-class but secondary |
| Manual DI (`AppContainer`) | Explicit, inspectable, zero reflection; Hilt migration stays trivial |
| Values-not-exceptions tool results | Agent loops must reason about failure as data |
| One chat, capabilities as tools | No duplicate runtimes; the user never chooses a "mode" |
| Keyless search engines | No paid API, no backend; queries leave the user's own device |
| App-private workspaces only | Never expose the device filesystem wholesale |
| Local model = just another provider | One agent loop, one context pipeline, one budget path |
| Extractors and policies kept pure | `LocalThrottlePolicy`, `CpuTopology`, `PromptPrefix`, `WebContentSanitizer` are unit-testable without Android |
| AI-owned reply presentation | Spacing and rich cards are chosen by the model through validated tools, not global settings |

## 18. Phase status

| Phase | Contents | Status |
|---|---|---|
| 0 — Foundation | Layering, design system, abstractions, shell | ✅ |
| 1 — Core | Providers, streaming chat, Markdown + LaTeX, Room, agent loop, permissions, tasks | ✅ |
| 2 — Advanced agent | Workspace, terminal, coding tools, browser, web search, memory, context engine | ✅ |
| Local AI | GGUF import/Hub, downloads, GPU/CPU variants, vision via mmproj, thermal policy, prefix reuse, routing rules, instructions | ✅ |
| Next | Retrieval (FTS) wired into the orchestrator, cache/debug UI, speculative decoding | 🔜 |
