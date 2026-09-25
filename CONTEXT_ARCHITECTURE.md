# Neuron-AI — Context Management Architecture

This document specifies the upgraded `ContextManager` → `ContextOrchestrator` system for Neuron-AI.
It **extends** the existing architecture (AgentRuntime, Workspace, Memory, Task systems from Milestones
0–3) — it does not replace any working component. Existing managers are wrapped as context providers,
not rewritten.

---

## 1. Purpose

Instead of sending the full raw conversation to the model on every request, every model call goes
through a single orchestration point that assembles a right-sized `ActiveContext` object, respecting
the model's actual context window.

Three concepts stay strictly separate:

- **Conversation History** — complete raw chat log, never deleted.
- **Memory** — intentionally retained, relevance-scoped facts (preferences, decisions, project state).
- **Active Context** — the dynamic subset actually sent to the model for *this* request.

---

## 2. High-Level Flow

```
UserRequest
    ↓
ContextOrchestrator                 (single entry point, called by AgentRuntime before every model call)

INTEGRATION POINT (current Neuron-AI code): context is built today by
ChatViewModel.buildHistory() → ChatContextEngine, passed to AgentGoal.history.
The orchestrator REPLACES that call site — AgentGoal.history remains the
carrier into the agent loop. No second context-building path may appear.
    ↓
[Providers fetch raw candidates in parallel]
    ↓
ContextRanker                        (priority + relevance + recency)
    ↓
TokenBudgetManager                   (model-aware budget allocation)
    ↓
ContextCompressor / CompactionManager (only if over budget)
    ↓
ActiveContext                        (final, immutable, sent to model)
```

---

## 3. Package Structure

```
com.neuron.ai.context/
├── orchestrator/   ContextOrchestrator, ContextBuilder
├── model/          ActiveContext, ContextItem, ContextPriority, ContextSourceType
├── providers/       ConversationContextProvider, MemoryContextProvider,
                      WorkspaceContextProvider, TaskContextProvider,
                      ToolContextProvider, AttachmentContextProvider
                      (ConnectorContextProvider is v2 — no connector
                       subsystem exists in the app yet; excluded from v1)
├── retrieval/       HybridRetriever, LexicalIndex (SQLite FTS5), Reranker
├── budget/          TokenBudgetManager, TokenEstimator
├── compaction/       CompactionManager, StructuredStateBuilder
├── compression/     ToolResultProcessor
├── cache/           ContextCacheManager
└── debug/           ContextDebugSnapshot
```

Each provider wraps an *existing* manager (`WorkspaceManager`, `MemoryManager`, `TaskManager`, etc.)
rather than duplicating its logic — providers are thin adapters that query the existing system and
translate results into `ContextItem`s.

---

## 4. Core Models

```kotlin
enum class ContextPriority(val level: Int) {
    SYSTEM(0), CURRENT_REQUEST(1), TASK_STATE(2), RECENT_CHAT(3),
    WORKSPACE(4), MEMORY(5), OLDER_HISTORY(6), LOW_VALUE(7)
}

enum class ContextSourceType {
    SYSTEM, CONVERSATION, MEMORY, WORKSPACE, TASK, TOOL_RESULT, CONNECTOR, ATTACHMENT
}

data class ContextItem(
    val id: String,
    val sourceType: ContextSourceType,
    val sourceId: String,
    val priority: ContextPriority,
    val relevanceScore: Float,          // 0f when not applicable (e.g. system context)
    val tokenEstimate: Int,
    val timestamp: Long,
    val workspaceId: String?,
    val conversationId: String,
    val taskId: String?,
    val content: String
)

data class ActiveContext(
    val systemContext: List<ContextItem>,
    val currentRequest: ContextItem,
    val recentMessages: List<ContextItem>,
    val relevantHistory: List<ContextItem>,
    val conversationSummary: ContextItem?,
    val relevantMemories: List<ContextItem>,
    val workspaceContext: List<ContextItem>,
    val taskContext: List<ContextItem>,
    val toolContext: List<ContextItem>,
    val connectorContext: List<ContextItem>,
    val attachmentContext: List<ContextItem>,
    val tokenUsage: Int,
    val tokenBudget: Int,
    val debug: ContextDebugSnapshot
)
```

---

## 5. ContextOrchestrator

```kotlin
class ContextOrchestrator(
    private val providers: List<ContextProvider>,
    private val ranker: ContextRanker,
    private val budgetManager: TokenBudgetManager,
    private val compactionManager: CompactionManager,
    private val cache: ContextCacheManager
) {
    suspend fun buildContext(
        request: UserRequest,
        model: ModelCapabilities,
        conversationId: String,
        workspaceId: String?,
        taskId: String?
    ): ActiveContext {
        val budget = budgetManager.computeBudget(model)

        val candidates = providers
            .map { provider -> async { provider.fetch(request, conversationId, workspaceId, taskId) } }
            .awaitAll()
            .flatten()

        val ranked = ranker.rank(candidates, request)
        val fitted = budgetManager.fitToBudget(ranked, budget)   // P0/P1/P2 always preserved

        val finalItems = if (fitted.overBudget) {
            compactionManager.compact(fitted, budget)             // summarize P6, drop P7
        } else {
            fitted.items
        }

        return assemble(finalItems, budget)
    }
}

interface ContextProvider {
    suspend fun fetch(
        request: UserRequest,
        conversationId: String,
        workspaceId: String?,
        taskId: String?
    ): List<ContextItem>
}
```

`ContextRanker` scores each item as `f(priority, relevanceScore, recencyDecay)`, with priority acting
as a hard tier and relevance/recency breaking ties within a tier.

---

## 6. Token Budgeting — Model-Aware, Not Hardcoded

```kotlin
data class TokenBudget(
    val total: Int,
    val reservedOutput: Int,
    val system: Int,
    val recent: Int,
    val history: Int,
    val memory: Int,
    val workspace: Int,
    val tools: Int,
    val attachments: Int
)

class TokenBudgetManager(private val estimator: TokenEstimator) {
    fun computeBudget(model: ModelCapabilities): TokenBudget {
        val usable = model.contextWindow - model.outputLimit
        // P0–P2 are reserved unconditionally; remaining space is split across P3–P7
        // proportionally, weighted by priority level. Never a fixed universal number.
        TODO("proportional allocation using model.contextWindow / model.outputLimit")
    }

    fun fitToBudget(ranked: List<ContextItem>, budget: TokenBudget): FitResult {
        TODO("greedily fill by priority tier, tracking overBudget flag")
    }
}
```

Budget must be recomputed per request using the **currently selected model's** `contextWindow` and
`outputLimit` — never a constant, since Neuron-AI is provider-agnostic (OpenAI-compatible, Gemini,
Claude-compatible, OpenRouter, local models).

---

## 7. Retrieval — Kept Lightweight for On-Device / Termux Development

No heavy vector database dependency. Hybrid lexical + metadata retrieval:

- **SQLite FTS5** (built into Android's SQLite — zero extra dependency) indexes conversation messages
  for keyword search.
- Ranking combines FTS5 BM25 score + recency decay + source priority.
- **Future upgrade path (not required for v1):** a small on-device SLM could generate lightweight
  embeddings for true semantic retrieval, avoiding any third-party embedding model or paid API —
  pluggable later via the `SemanticIndex` interface without touching the pipeline.

```kotlin
class HybridRetriever(
    private val lexicalIndex: LexicalIndex,   // FTS5-backed
    private val reranker: Reranker
) {
    suspend fun retrieve(query: String, conversationId: String, limit: Int): List<ContextItem> {
        val candidates = lexicalIndex.search(query, conversationId)
        return reranker.rerank(candidates, query, limit)
    }
}
```

---

## 8. Compaction — Structured State, Not Prose

`CompactionManager` never deletes raw messages. It produces a `StructuredState` object stored
alongside the raw log and reused as a single high-priority `ContextItem`:

```kotlin
data class StructuredState(
    val goal: String,
    val decisions: List<String>,
    val constraints: List<String>,
    val currentTask: String?,
    val implementationState: List<String>,
    val unresolved: List<String>,
    val relevantToolOutcomes: List<String>
)
```

This is far cheaper to incrementally re-summarize as the conversation grows than a free-text
paragraph, and preserves exactly the fields listed in the project's compaction requirements
(objective, decisions, constraints, current task, unresolved issues).

---

## 9. Tool Result Compression

Tool outputs (e.g. a 20,000-line Gradle build log) are never sent raw, repeatedly, into context.

```kotlin
data class ProcessedToolResult(
    val command: String,
    val exitCode: Int,
    val summary: String,
    val relevantLines: List<String>,
    val fullResultRef: String   // reference to persisted full log
)
```

Only `summary` + `relevantLines` enter `ActiveContext`. The full output stays retrievable via a
`ReadFullToolOutput` tool when the model actually needs it.

---

## 10. Context Isolation

- Every `ContextItem` carries `workspaceId`, `conversationId`, `taskId`.
- Providers must filter strictly by these IDs — no cross-workspace or cross-conversation leakage.
- Memory retrieval respects workspace/project boundaries.
- Connector data (Gmail, Drive, Calendar, GitHub, etc.) is fetched on demand per request and never
  cached as permanent context — see `ConnectorContextProvider`.

---

## 11. Debug Snapshot (developer-only)

```kotlin
data class ContextDebugSnapshot(
    val systemTokens: Int,
    val recentChatTokens: Int,
    val historyTokens: Int,
    val memoryTokens: Int,
    val workspaceTokens: Int,
    val toolTokens: Int,
    val attachmentTokens: Int,
    val reservedOutputTokens: Int,
    val totalTokens: Int,
    val compactionTriggered: Boolean,
    val retrievedMemoryCount: Int,
    val retrievedHistoryCount: Int,
    val compressedToolResultCount: Int,
    val droppedLowPriorityCount: Int
)
```

v1: logged via the existing Logger on every assembled context (debug builds only);
UI panel / end-user "context usage" indicator is deferred until the system is verified.

---

## 12. Failure Handling

All providers/retrieval/compaction steps must degrade gracefully, never crash the chat:

- Retrieval failure → fall back to recent messages + current task context only.
- Summary failure → fall back to raw recent messages, skip the summary item.
- Token estimation failure → use a conservative fixed-ratio fallback estimator.
- Attachment extraction failure → mark attachment as unavailable, continue without it.

---

## 13. Suggested Build Order

1. `ContextItem` / `ActiveContext` / `ContextPriority` models
2. `TokenBudgetManager` + `TokenEstimator`, wired into existing `ModelCapabilities`
3. Providers — wrap existing Workspace, Memory, Task managers as adapters
4. `ContextRanker` + budget-fitting logic
5. `ToolResultProcessor` (highest immediate payoff — addresses today's largest context bloat)
6. FTS5-backed `HybridRetriever`
7. `CompactionManager` with `StructuredState`
8. `ContextDebugSnapshot` UI panel

Each step is independently shippable and testable without touching the others — no big-bang rewrite
of the existing `ContextManager` is required at any point.

---

## 14. Hardening — Fixes for the Known Weaknesses

### 14.1 Semantic recall (FTS5 is keyword-only)

FTS5 alone misses paraphrases ("terminal off by default" vs "AI can't run shell commands"). Fix
without adding a heavy dependency:

- Switch the FTS5 index to the **porter tokenizer** (stemming) and add a secondary **trigram** FTS5
  table for fuzzy/typo-tolerant matches — both are built into SQLite, zero extra dependency.
- Maintain a small **synonym/alias map** (stored via the existing memory system) that the retriever
  expands the query with before searching — e.g. `"terminal permission"` ↔ `"shell access"`.
- Keep semantic embeddings as a **pluggable** `SemanticIndex` behind the same `Reranker` interface, so
  a future on-device embedding index can be swapped in later without touching the orchestrator pipeline.

```kotlin
interface SemanticIndex {
    suspend fun search(query: String, conversationId: String, limit: Int): List<ContextItem>
}

class HybridRetriever(
    private val lexicalIndex: LexicalIndex,        // FTS5 porter + trigram
    private val synonymExpander: SynonymExpander,
    private val semanticIndex: SemanticIndex?,      // null until Continuum is ready
    private val reranker: Reranker
) {
    suspend fun retrieve(query: String, conversationId: String, limit: Int): List<ContextItem> {
        val expanded = synonymExpander.expand(query)
        val lexical = lexicalIndex.search(expanded, conversationId)
        val semantic = semanticIndex?.search(query, conversationId, limit) ?: emptyList()
        return reranker.rerank(lexical + semantic, query, limit)
    }
}
```

### 14.2 Token estimation accuracy across providers

- `TokenEstimator` becomes an interface: a cheap default heuristic (`chars / 4 * safetyMargin`) plus
  provider-specific implementations that call a real counting endpoint where one exists (e.g. Anthropic's
  `count_tokens`, Gemini's `countTokens`).
- Exact counting is invoked **only near the budget boundary** (inside `fitToBudget`, not per item), so
  accuracy doesn't cost latency on every request.
- Every `TokenBudget` carries an explicit `safetyMarginPercent` (default ~10%) so an estimation miss
  degrades gracefully instead of causing a hard overflow.

```kotlin
interface TokenEstimator {
    fun estimate(text: String): Int
}

class HeuristicTokenEstimator(private val safetyMargin: Float = 1.1f) : TokenEstimator {
    override fun estimate(text: String): Int = ((text.length / 4) * safetyMargin).toInt()
}

class ProviderTokenCounter(private val provider: AiProvider) : TokenEstimator {
    override fun estimate(text: String): Int = provider.countTokens(text)   // only called near budget edge
}
```

### 14.3 Extra moving parts → bugs, cache bugs, silent budget miscalculation

- **Invariants, checked and logged loudly** (reuse the existing Phase 0 logging/error abstractions):
  - P0–P2 items are never dropped by `fitToBudget` or `compact`.
  - `activeContext.tokenUsage` never exceeds `tokenBudget.total`.
  - Any violation logs an error and falls back to a safe minimal context (system + current request +
    last N recent messages) rather than sending something over budget.
- **Unit tests from day one** for `ContextRanker` and `TokenBudgetManager` using fixed fixture item
  sets with known token counts/priorities — these are pure functions, cheap to test exhaustively.
- **Content-addressed cache keys** — `(workspaceId, conversationId, contentHash)` instead of time-based
  keys, so any underlying data change invalidates the right cache entry automatically.
- **Shadow mode for rollout**: run `ContextOrchestrator` in parallel with the current context logic,
  log the two side by side, but keep feeding the model the old context until the new one has been
  verified against real conversations. Flip over only after that comparison looks correct.

### 14.4 Compaction distortion (summarization is itself a model call)

- Before a `StructuredState` summary is trusted, run a cheap keyword-overlap check against the raw
  messages it was built from. If overlap is unexpectedly low, fall back to raw recent messages for that
  turn instead of the summary, and flag the summary for re-generation.
- Raw messages are always retrievable regardless of what the summary says — the summary is a
  convenience layer, never the source of truth.
