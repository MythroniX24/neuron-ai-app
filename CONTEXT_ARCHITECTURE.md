# Neuron-AI — Context Management Architecture

How Neuron-AI decides **what the model actually sees** on every turn.

Three concepts stay strictly separate:

- **Conversation history** — the complete raw chat log in Room, never deleted.
- **Memory** — intentionally retained, scope-isolated facts (preferences,
  project notes).
- **Active context** — the dynamic subset assembled for *this* request only.

This document describes both what is **implemented today** (✅) and what the
remaining pieces of the pipeline are designed to be (🔜). Nothing here replaces
a working component: the new pipeline wraps existing managers as providers, and
runs alongside the legacy engine until it has been verified.

---

## 1. Two layers, one carrier

```
ChatViewModel.buildHistory()
 ├── ChatContextEngine (legacy)        ✅ used for the request today
 └── ContextOrchestrator (Phase 3)    ✅ built in SHADOW mode (logged, not used)
        ↓
   AgentGoal.history  →  ToolUsingAgent  →  provider request
```

- `AgentGoal.history` is the single carrier into the agent loop. **No second
  context-building path exists**; the orchestrator replaces the call site, not
  the loop.
- The selected model's window comes from `ModelCapabilities` (cloud) or the
  GGUF-declared `context_length` (on-device), so both paths see one number.
- `ChatViewModel.USE_ORCHESTRATOR_CONTEXT = false` today. When the shadow log
  comparison looks right, flipping it returns the orchestrator's messages and
  the legacy engine becomes the fallback.

## 2. Flow

```
UserRequest
    ↓
ContextOrchestrator.buildContext(
    query, conversationId, currentUserId, workspaceId, taskId,
    contextWindowTokens, excludeCurrentRequest)
    ↓
[providers fetch candidates in parallel]
    ↓
ContextRanker            (hard priority tiers → relevance → recency)
    ↓
TokenBudgetManager       (model-aware budget, P0–P2 protected)
    ↓
CompactionManager        (only when the ranked fit is over budget)
    ↓
ActiveContext → List<ChatMessage>   (+ shadow metrics)
```

Failure handling is part of the contract: a provider that throws contributes no
candidates, and an impossible fit (protected tiers alone exceed the budget) falls
back to the **safe minimal context** — system + current request + last 8 turns.

## 3. Packages

```
com.neuron.ai.context/
├── model/          ContextItem, ContextPriority, ContextSourceType      ✅
├── providers/      Conversation / Memory / Task context providers        ✅
├── ranker/         ContextRanker                                         ✅
├── budget/         TokenBudgetManager, TokenBudget                       ✅
├── compaction/     CompactionManager, StructuredState                    ✅
├── compression/    ToolResultProcessor                                   ✅
└── orchestrator/   ContextOrchestrator                                   ✅

Planned (not in the codebase yet):
├── retrieval/      HybridRetriever, LexicalIndex (SQLite FTS5), Reranker  🔜
├── cache/          ContextCacheManager                                   🔜
└── providers/      Workspace / Tool / Attachment / Connector providers    🔜
```

Providers are **thin adapters** over existing managers (`MemoryManager`,
`TaskManager`, `ConversationRepository`); they translate results into
`ContextItem`s and never duplicate business logic.

## 4. Core models ✅

```kotlin
enum class ContextPriority(val level: Int) {
    SYSTEM(0), CURRENT_REQUEST(1), TASK_STATE(2), RECENT_CHAT(3),
    WORKSPACE(4), MEMORY(5), OLDER_HISTORY(6), LOW_VALUE(7)
}

enum class ContextSourceType {
    SYSTEM, CONVERSATION, MEMORY, WORKSPACE, TASK, TOOL_RESULT,
    CONNECTOR, ATTACHMENT
}

data class ContextItem(
    val id: String,
    val sourceType: ContextSourceType,
    val sourceId: String,
    val priority: ContextPriority,
    val relevanceScore: Float = 0f,   // 0f when not applicable
    val content: String,              // TOOL rows enter pre-compressed
    val timestampMs: Long = 0L,
    val workspaceId: String? = null,
    val conversationId: String = "",
    val taskId: String? = null,
    /** Wire role for chat assembly; null = derived from priority. */
    val wireRole: ChatMessage.Role? = null,
    /** TOOL rows must reference the assistant tool-call id on the wire. */
    val toolCallId: String? = null,
    /** Images/files of the original message (vision-input parity). */
    val attachments: List<Attachment> = emptyList()
) {
    /** Cheap char-based estimate; the budgeter converts it to tokens. */
    val tokenEstimate: Int get() = content.length / 4
}
```

`ContextPriority` doubles as the tier used by both the ranker and the budget
manager, which keeps ranking and fitting consistent by construction.

## 5. Orchestrator ✅

```kotlin
class ContextOrchestrator(
    private val conversationProvider: ConversationContextProvider,
    private val memoryProvider: MemoryContextProvider,
    private val taskProvider: TaskContextProvider,
    private val messagesProvider: suspend (String) -> List<Message>,
    private val budgetManager: TokenBudgetManager = TokenBudgetManager(),
    private val compactionManager: CompactionManager = CompactionManager(),
    private val minimalRecentCount: Int = 8
) {
    suspend fun buildContext(
        query: String,
        conversationId: String,
        currentUserId: String?,
        workspaceId: String?,
        taskId: String?,
        contextWindowTokens: Int,
        excludeCurrentRequest: Boolean = false
    ): BuiltContext
}
```

`BuiltContext` reports `messages`, `tokenUsage`, `tokenBudgetTotal`,
`candidateCount`, `keptCount`, `droppedCount`, `usedMinimalFallback`,
`compacted` and `compactionSummaryIncluded` — exactly the numbers the shadow log
prints next to the legacy engine's output via `ContextOrchestrator.builtFrom`.

The conversation log is read **once** per turn and shared by both paths, so the
shadow comparison can never diverge because of a race.

`excludeCurrentRequest = true` keeps parity with the legacy engine: the current
user request travels as the agent goal's instruction, so P1 rows are left out of
the history and the model never sees the request twice.

### Provider mapping ✅

| Source | Priority |
|---|---|
| `Message.Role.SYSTEM` | SYSTEM (P0) |
| current user message (`currentUserId`) | CURRENT_REQUEST (P1) |
| `Message.Role.TOOL` | TASK_STATE (P2), compressed |
| everything else | RECENT_CHAT (P3) |
| persisted error rows (`metadata.isError`) | dropped — noise |

Memory items are ranked MEMORY (P5) and tasks TASK_STATE (P2); both are filtered
strictly by `workspaceId` / `conversationId` (see §10).

## 6. Token budgeting ✅

```kotlin
data class TokenBudget(
    val total: Int,                 // selected model's window
    val reservedOutput: Int,        // min(2048, window / 4)
    val safetyMarginPercent: Int = 10
) {
    val usableForInput: Int get() = ((total - reservedOutput) * 100) / (100 + safetyMarginPercent)
}
```

- `computeBudget(window)` clamps the window to a 4 000-token floor so a
  mis-declared window cannot produce a zero budget.
- `fitToBudget` fills greedily by tier and returns `Fits`, `OverBudget` (with the
  dropped items) or `Impossible` when P0–P2 alone do not fit.
- **Invariants:** P0–P2 are never dropped by fitting or compaction, and the
  fitted result never exceeds `usableForInput`; a violation logs loudly and the
  caller falls back to the safe minimal context.
- Budget is recomputed **per request** from the selected model — never a global
  constant. Local models feed their GGUF-declared window into the same path; the
  on-device trimmer (`data/local/ContextTrimmer.kt`) applies the phone-specific
  cap on top.

## 7. Ranking ✅

`ContextRanker` scores `f(priority tier, relevance, recency)`:

- priority is a **hard tier** — a SYSTEM item always outranks a hugely relevant
  LOW_VALUE one;
- within a tier, keyword overlap with the current query lifts relevance;
- recency is an additive term (items newer than 60 s count as fresh), so two
  items with no relevance signal still order newest-first;
- the sort is stable, which keeps prompts and tests deterministic.

## 8. Compaction ✅

`CompactionManager` never deletes raw messages. It produces an **extractive**
`StructuredState` for an older segment:

```kotlin
data class StructuredState(
    val goal: String,              // first user message of the segment
    val decisions: List<String>,   // final assistant answers
    val toolOutcomes: List<String>,// TOOL rows, compressed
    val sourceCount: Int
)
```

Extractive by design (v1): every line quotes the conversation, so no model
round-trip is needed, nothing can be hallucinated, and the distortion check has a
real guarantee to verify. §14.4 applies: a keyword-overlap test against the raw
messages is run before the summary is trusted; if overlap is too low the summary
is **not** included (`compactionSummaryIncluded = false`) and the turn falls back
to raw eviction. Raw messages stay retrievable regardless.

## 9. Tool-result compression ✅

`ToolResultProcessor` is the highest-payoff piece: large tool payloads never
re-enter context raw on every later turn.

```kotlin
data class ProcessedToolResult(
    val toolName: String,
    val callId: String,
    val summary: String,          // one line: exit=0, error text…
    val relevantLines: List<String>,
    val originalChars: Int
)
```

- Applied to every TOOL row by the conversation provider and by the legacy
  engine alike.
- Compression threshold: **1 200 chars**. Above it the model sees a
  `(full output N chars — ask to read the full tool output if needed)` hint; the
  complete text stays in the persisted TOOL message.
- Key lines kept: exit codes, `[err]`/error markers, SOURCE/TITLE/URL and paths.

## 10. Isolation ✅

- Every `ContextItem` carries `conversationId` and optional `workspaceId` /
  `taskId`; providers filter strictly by them — no cross-chat leakage.
- Memory retrieval respects project scope (PROJECT memories are workspace-bound;
  USER_PREFERENCE is global by definition).
- Attachments are addressed by id from the current message only.
- Connector data (Gmail, Drive, Calendar…) has no subsystem in the app yet, so no
  connector provider ships — by design, not by omission.

## 11. Shadow observability (partial) ✅ / 🔜

Today: every assembled context is compared against the legacy engine and both
sides are logged (`logger.d("ContextShadow", …)`) in debug builds, with candidate
/ kept / dropped / token numbers and the minimal-fallback and compaction flags.

Planned: a `ContextDebugSnapshot` data class plus a developer panel (token split
per source, compaction/retrieval counters). The Debug console that ships today
covers local-engine diagnostics (engine build, memory, model files, llama.cpp
log, crash reports), not context usage.

## 12. Failure handling ✅

- Retrieval/provider failure → contribute no items, keep the rest.
- Repository failure (no messages) → safe minimal context.
- Compaction failure or rejected summary → raw eviction only.
- Impossible fit → safe minimal context and a loud log.
- Token estimation is a conservative character heuristic with an explicit safety
  margin, so an estimation miss degrades quality instead of overflowing.

## 13. Remaining work 🔜

1. **Retrieval.** SQLite FTS5 lexical index over messages (porter + trigram,
   zero extra dependency), BM25 + recency + source priority, behind a `Reranker`
   so a future on-device semantic index can be plugged in without touching the
   orchestrator.
2. **Cache.** Content-addressed keys `(workspaceId, conversationId, contentHash)`
   instead of time-based ones, so any data change invalidates exactly the right
   entry.
3. **Wider providers.** Workspace, attachment and tool providers on top of the
   modules that already exist.
4. **Verification.** Flip `USE_ORCHESTRATOR_CONTEXT` after real multi-step
   conversations show parity or better against the legacy engine.
5. **Context usage UI.** Surface the shadow numbers to the user only after the
   pipeline itself is verified.

## 14. Hardening notes

- **Semantic recall** is keyword-only until FTS5 lands; the synonym map and
  semantic index stay pluggable so no orchestrator change is needed later.
- **Token estimation** stays heuristic; provider-specific counters (Anthropic
  `count_tokens`, Gemini `countTokens`) would only be called near the budget
  boundary, never per item.
- **Compaction distortion** is guarded by the keyword-overlap check above;
  the raw log remains the source of truth.
- **Deterministic tests.** `ContextRanker`, `TokenBudgetManager`,
  `CompactionManager`, `ToolResultProcessor` and `ContextOrchestrator` are all
  covered by the JVM suite (`context/Context*Test.kt`) using fixed fixture sets.
