# Neuron-AI

An on-device **AI agent platform for Android**, built with **Kotlin** and
**Jetpack Compose**.

Neuron-AI is not a chat wrapper: it is one chat surface where a real agent can
search the web, read pages in an embedded browser, use a sandboxed workspace,
run shell commands, edit code, build and test projects, remember durable facts,
and — with Local AI — run a GGUF model entirely on the phone with no API key and
no network at inference time.

Current release: **1.2.5** (`versionCode` 13, `minSdk` 26, `targetSdk` 34).

---

## What ships today

### One chat, every capability

There is exactly **one** chat screen. Coding, terminal, browsing, searching and
memory are *tools of the same agent* inside a normal conversation — no separate
"coding chat", "browser chat" or "search chat", and no second runtime.

- Streaming answers over any OpenAI-compatible endpoint, or from an on-device
  GGUF model, through the same provider interface.
- Agent activity timeline rendered inline in the conversation: ✓ / ✗ / ⟳ step
  rows with the query, command or path each step used, plus short narration —
  never chain-of-thought.
- **A reply does not end the turn.** If the model answers with a promise
  ("Let me check that profile…") instead of doing the work, the loop sends the
  reply back so the announced tool actually runs. Only the model decides when to
  stop, and the nudge budget is bounded so a model that only ever promises still
  finishes.
- **No internal tool-log junk in the bubble.** Markers such as
  `[used tool: …]` / `[tool result] …` are stripped wherever they appear, the
  visible buffer is replaced with the cleaned text, and a reply made only of
  those artifacts is re-requested instead of shown.
- Message actions: copy, retry, regenerate, edit & resend; a message typed while
  the agent is working is queued and shown as `⏳ Queued: …` with undo.
- AI-generated chat titles (2–3 words) with an offline heuristic fallback, pin,
  rename, search and delete in the drawer / conversations screen.
- Per-conversation model selection from the top bar, including on-device models
  with a live loading state.

### Chat controls (the `+` sheet)

| Control | What it does |
|---|---|
| **Camera / Photos / Files** | Attach images or documents. Text-like files and PDFs are flattened into the prompt; images go to vision models only. |
| **Terminal** | Per-chat AI shell access, **OFF** by default. ON is standing consent for the agent's commands (a catastrophic-command guard still applies). With it OFF, shell/coding tools raise a real permission dialog. |
| **Browser** | Per-chat browsing tools, **ON** by default. Read-only tools need the toggle; clicking, typing and selecting always ask for explicit permission. |
| **Workspace** | Attach or create a workspace. The agent's filesystem, terminal and coding tools all live inside it. |

Switching a capability creates the conversation lazily, so a brand-new chat
starts with the same composer from the first frame.

### Agent tools

Every tool is registered in one registry and exposed to the model with a JSON
schema; no tool logic lives in the UI.

| Domain | Tools |
|---|---|
| Basics | `time.now`, `math.evaluate`, `text.stats` |
| Files (sandbox root) | `fs.read`, `fs.search` |
| Workspace | `fs.list`, `fs.readfile`, `fs.writefile`, `fs.mkdir`, `fs.rename`, `fs.move`, `fs.copy`, `fs.delete`, `fs.searchfiles`, `fs.metadata` |
| Web | `web.search` (keyless multi-engine), `web.read` |
| Google | `google.drive.*`, `google.gmail.*`, `google.sheets.*`, `google.docs.*` — thin proxies; every Google call runs on the connector Worker (`backend/`) |
| Browser | `browser.open`, `browser.read`, `browser.find`, `browser.links`, `browser.click`, `browser.type`, `browser.select` |
| Terminal & code | `terminal.run`, `code.edit`, `code.patch`, `code.diff`, `code.build`, `code.test` |
| Memory | `memory.remember`, `memory.recall`, `memory.forget` |
| Presentation | `ui.rich_items` (native recommendation cards), `ui.compare` (native comparison surfaces), `ui.reply_spacing` (AI-chosen line spacing for prose and lists) |

The tool loop alternates freely between reasoning, tool calls and short progress
notes, with a 24-step budget, per-sub-problem retry limits, and a system prompt
that forbids repeating an identical failed call or claiming success it did not
observe.

### Comparison surfaces (any topic)

When you ask "which one is better", the answer is not a Markdown table. The
model calls `ui.compare` with typed blocks and they render natively, in order,
interleaved with its prose — for phones, apps, AI models, plans, tools, places,
anything:

| Block | Renders |
|---|---|
| `side_by_side` | two equal cards: image, title, pill label, key facts |
| `feature_table` | borderless Feature / Option A / Option B table |
| `score` | two score cards, higher score highlighted, `Winner` pill |
| `pros_cons` | green Pros card beside a red/crimson Cons card |
| `priority` | interactive radio choices; picking one shows the tailored recommendation |

Images never come from the model's imagination: each option carries either a
verified `imageUrl` or an `imageQuery` phrase, which the app resolves to a real
public image (keyless, cached) in the ViewModel. While an image is loading the
card shows a shimmer, and a miss leaves a neutral placeholder — the two cards
stay symmetrical. A half-streamed block is hidden behind a compact skeleton, so
raw JSON brackets never appear in the chat. Blocks are persisted with the
message, so history renders identically after a restart.

### Web search — keyless, on your own connection

- Three independent adapters: **DuckDuckGo** HTML, **Bing** HTML and **Mojeek**,
  fanned out in parallel behind one `SearchProvider` interface.
- Per-provider circuit breakers, a 10-minute TTL cache and result
  dedup/reranking; adding another engine is one registry entry.
- Pages are fetched with a 512 KB cap, extracted to readable text, and fenced as
  `<<<UNTRUSTED_WEB_DATA>>>` so page content is treated as data — never as
  instructions (prompt-injection defense).
- Sources come back as `[n]` indices and are persisted as TOOL messages, so
  citations stay attached to the answer that used them.

### Memory

- Explicit only: nothing is saved unless the user asks and the model calls
  `memory.remember`.
- Scopes: USER_PREFERENCE (global), PROJECT (per workspace), TASK/CONVERSATION
  reserved. The scope is fixed by the conversation context — the model cannot
  choose or read another scope.
- `MemorySecretFilter` rejects API keys, tokens, passwords and JWTs outright.
- Settings → Memory can inspect, delete entries, clear everything, or switch the
  feature off entirely.

### Workspace, terminal and coding

- Workspaces live under `filesDir/workspaces/<id>` — app-private by
  construction; every path is canonicalised and checked, so `../` and absolute
  escapes are rejected (covered by tests).
- Reads run concurrently; every mutation serialises behind a per-workspace
  `Mutex`, so two agent tasks can never interleave writes on one project.
- `code.edit` is an exact-unique replacement; `code.patch` refuses to apply when
  the file changed since the agent read it (no blind overwrites); `code.diff`
  shows the change; `code.build` / `code.test` detect Gradle, Maven, npm, make,
  cargo and pip projects.
- The terminal panel is a draggable bottom sheet over the chat: real
  `/system/bin/sh` sessions with streamed stdout/stderr, exit codes, history,
  copy, and stop. The user panel and the agent share the same session per
  conversation.

### Local AI — no key, no cloud

Run GGUF models on the phone through a bundled llama.cpp bridge.

- Import a `.gguf` file or search the **Hugging Face Hub** (free API, no key)
  with a variant/quantisation picker, download progress, **pause/resume**,
  Wi-Fi-only gate, sha256 verification and restart survival (foreground service
  keeps downloads alive off-screen).
- Curated recommended list with RAM-fit guidance; single-active model with mmap
  loading and per-model benchmark (tokens/sec).
- **Capability-honest routing:** the GGUF's own chat template decides tool
  support, its architecture decides vision, its declared context length is
  capped for phones. Vision is only claimed when a real **mmproj projector**
  sits next to the model — then images go through llama.cpp's `mtmd` encoder.
  The routing resolution (model, window, degradations, throttle) is the first
  row of the live timeline.
- **Per-model settings:** custom instructions appended to the system prompt,
  "use this model for" routing rules (text / tools / vision), context window and
  answer-length overrides, GPU (Vulkan) and CPU-thread choice, load/unload,
  benchmark, delete.
- **Performance:** per-ISA CPU kernel variants (KleidiAI, dotprod/i8mm/SVE —
  best match dlopen'd at load), Vulkan GPU offload with flash attention and Q8_0
  KV cache plus automatic CPU fallback, big-core threading, KV prompt-prefix
  reuse so only the new part of a conversation is re-processed.
- **Thermal/battery aware:** thermal status/headroom, battery and power-save are
  read and the *next* answer is throttled (fewer threads, GPU off, shorter
  answers) instead of melting down mid-sentence.
- **Debug console:** engine build info, device memory, model files and
  llama.cpp's own log, plus last-resort crash reports (native crashes included)
  so a failed load is diagnosable.

### Providers

- Presets for OpenAI, Google Gemini (OpenAI-compatible), Groq, xAI (Grok),
  OpenRouter, Ollama (local server) and Custom — any base URL works.
- Add / edit / test / delete providers, fetch or enter model IDs manually,
  custom headers, per-provider tool-calling and vision flags.
- API keys live in the **Android Keystore** (`SecureCredentialStore`); if the
  keystore cannot initialise, the store degrades to in-memory and never falls
  back to plain text.
- Input validation happens before the request: an image attached to a non-vision
  model is refused with a model-selection hint.

### Tasks and permissions

- Tasks screen: live status, activity, progress, waiting-for-permission state,
  retry for failures, stop for running work. Transitions are persisted to Room
  and stale RUNNING rows are recovered as FAILED after a restart.
- Risks are graded SAFE / ELEVATED / DESTRUCTIVE. DESTRUCTIVE work (and anything
  carrying `FILESYSTEM_DELETE`) always re-prompts; grants can be remembered for
  once / session / workspace / always and are revocable.

### Rendering

Hand-rolled Markdown (headings, lists, quotes, tables, links, code blocks with
syntax highlighting and copy), LaTeX via `jlatexmath-android`, native rich
recommendation cards with images/badges/key facts/links, and AI-chosen reply
spacing applied separately to paragraphs and lists.

---

## Building

Requirements: **JDK 17**, Android SDK 34, **NDK + CMake 3.22** (for the bundled
llama.cpp bridge; install via SDK Manager → SDK Tools). If the llama.cpp sources
cannot be fetched at build time the bridge compiles to a stub, the app reports
local inference as unavailable, and all cloud features keep working.

```bash
./gradlew assembleDebug        # debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM unit tests (400+ tests)
./gradlew assembleRelease      # release APK (signed only when the env vars below exist)
```

Or open the project in Android Studio and press Run. Local builds pick up
signing from `NEURON_KEYSTORE_PATH`, `NEURON_KEYSTORE_PASSWORD`,
`NEURON_KEY_ALIAS` and `NEURON_KEY_PASSWORD`; without them the release build is
simply unsigned.

## CI and releases

`.github/workflows/android-ci.yml` runs on every push/PR to `main`, on `v*` tags
and on manual dispatch:

| Job | Output |
|---|---|
| `build-debug` | runs `testDebugUnitTest`, builds the debug APK, uploads **`neuron-ai-debug-apk`** |
| `build-release` | builds `assembleRelease` (signed when the four keystore secrets exist, otherwise unsigned) and uploads **`neuron-ai-release-apk`** |
| `attach-to-release` (tags only) | attaches the release APK to the GitHub Release for the tag |

Artifacts are under **Actions → android-ci**. Install the release APK rather
than the debug one: same app, properly signed, far fewer Play Protect warnings.
"Install unknown apps" is still required for any APK installed outside the Play
Store — that is an OS rule.

Signed release APKs are also attached to
[GitHub Releases](https://github.com/MythroniX24/neuron-ai-app/releases), and
because they share one signing key each release installs straight over the last
one. See [SIGNING.md](SIGNING.md) for the one-time keystore setup.

## Project layout

```
app/src/main/
├── java/com/neuron/ai
│   ├── core/      domain contracts & models (no Android UI deps)
│   ├── data/      implementations: providers, agent, tools, db, web, local AI…
│   ├── context/   context orchestration pipeline (budget, rank, compaction)
│   ├── di/        AppContainer — explicit manual dependency graph
│   └── ui/        Compose screens, ViewModels, design system, Markdown
├── cpp/           llama.cpp bridge (CMake, pinned upstream)
└── res/           resources, adaptive icons
```

Detailed design: [ARCHITECTURE.md](ARCHITECTURE.md) · context pipeline:
[CONTEXT_ARCHITECTURE.md](CONTEXT_ARCHITECTURE.md) · release signing:
[SIGNING.md](SIGNING.md) · agent/CI rules: [AGENTS.md](AGENTS.md).

## Testing

A JVM suite of 400+ tests covers the provider wire protocol and streaming,
tool-call parsing (including plain-text/Qwen markup recovery), the agent tool
loop (continuation-after-reply and marker cleanup included), tool executor
timeouts and retries, permissions, tasks, the workspace sandbox and concurrency,
terminal sessions, web search/generation, memory, the context pipeline, the GGUF
parser and the whole local-model stack (routing, throttling, prefix reuse,
downloads, recommended models). `./gradlew testDebugUnitTest` runs them all.

## Roadmap

| Phase | Focus | Status |
|---|---|---|
| **0 — Foundation** | Architecture, design system, abstractions, shell | ✅ |
| **1 — Core Neuron-AI** | Real providers, streaming chat, Markdown + LaTeX, Room, agent tools, permissions, tasks | ✅ |
| **2 — Advanced Agent** | Workspace, terminal, coding tools, browser, web search, memory, context engine | ✅ |
| **Local AI** | GGUF import/Hub, downloads, GPU/CPU kernels, vision via mmproj, thermal policy, prefix reuse, per-model routing rules, custom instructions | ✅ |
| **Next** | FTS-backed retrieval inside the context orchestrator (currently shadow-mode), speculative decoding, per-model context experiments | 🔜 |

## License

TBD — all rights reserved until a license is chosen.
