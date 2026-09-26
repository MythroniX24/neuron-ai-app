package com.neuron.ai.context.compaction

import com.neuron.ai.context.model.ContextItem
import com.neuron.ai.context.model.ContextPriority
import com.neuron.ai.context.model.ContextSourceType
import com.neuron.ai.context.providers.ConversationContextProvider
import com.neuron.ai.core.conversation.Message

/**
 * Structured compaction state (CONTEXT_ARCHITECTURE.md §8) — a cheap,
 * EXTRACTIVE digest of an older conversation segment. Never deletes the raw
 * messages; the summary is a convenience layer, the raw log stays the source
 * of truth (§14.4).
 *
 * Extractive by design (v1): every digest line quotes the conversation
 * verbatim, so no model round-trip is needed, nothing can be hallucinated,
 * and the §14.4 validation has a meaningful guarantee to check.
 */
data class StructuredState(
    /** First user message of the segment — the goal it was pursuing. */
    val goal: String,
    /** Final assistant answers — the decisions/replies reached. */
    val decisions: List<String>,
    /** Tool outcomes compressed through the Phase-1 processor. */
    val toolOutcomes: List<String>,
    /** Count of raw messages the state was built from. */
    val sourceCount: Int
) {
    fun render(): String = buildString {
        append("Conversation so far (compacted summary of ")
        append(sourceCount).append(" earlier messages):\n")
        append("- goal: ").append(goal).append('\n')
        decisions.take(3).forEach { append("- outcome: ").append(it).append('\n') }
        toolOutcomes.take(6).forEach { append("- tool: ").append(it).append('\n') }
    }.trimEnd()

    /** Keyword set used by the §14.4 distortion check. */
    val keywords: Set<String> = (goal + decisions.joinToString(" ") + toolOutcomes.joinToString(" "))
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 }
        .toSet()
}

/**
 * Builds and validates [StructuredState] compactions (§8, §14.4).
 *
 * Validation: before a summary is trusted, a keyword-overlap check runs
 * against the raw messages it was built from. Overlap below
 * [MIN_OVERLAP_RATIO] means the digest distorts the segment — the caller
 * falls back to raw recent messages for that turn and the summary is not
 * used.
 */
open class CompactionManager(
    private val conversationProvider: ConversationContextProvider =
        ConversationContextProvider(),
    /** Cap for one raw message's contribution to the digest. */
    private val maxLineChars: Int = 200,
    /** §14.4 threshold — half the digest keywords must exist in the source. */
    private val minOverlapRatio: Double = 0.5
) {

    /**
     * Extractive digest of the OLDEST [maxSourceMessages] raw messages.
     * The most recent turns are deliberately left out — they stay raw in
     * context anyway; compaction only buys back older space.
     */
    open fun buildStructuredState(
        messages: List<Message>,
        conversationId: String,
        maxSourceMessages: Int = 40
    ): StructuredState {
        val segment = messages
            .filter { it.metadata?.isError != true }
            .takeLast(maxSourceMessages)
            .dropLast(KEEP_RECENT_RAW)
        if (segment.isEmpty()) {
            return StructuredState(goal = "", decisions = emptyList(), toolOutcomes = emptyList(), sourceCount = 0)
        }

        val firstUser = segment.firstOrNull { it.role == Message.Role.USER }
        val goal = firstUser?.content?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(maxLineChars)
            .orEmpty()

        val decisions = segment
            .filter { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }
            .map { it.content.replace(Regex("\\s+"), " ").trim().take(maxLineChars) }
            .takeLast(3)

        val toolOutcomes = segment
            .filter { it.role == Message.Role.TOOL }
            .map { msg ->
                conversationProvider.fetch(listOf(msg), conversationId, null)
                    .firstOrNull()?.content
                    ?: msg.content
            }
            .map { it.replace(Regex("\\s+"), " ").trim().take(maxLineChars) }
            .takeLast(6)

        return StructuredState(
            goal = goal,
            decisions = decisions,
            toolOutcomes = toolOutcomes,
            sourceCount = segment.size
        )
    }

    /**
     * §14.4 distortion check: at least [minOverlapRatio] of the digest's
     * keywords must appear in the raw source text. An empty or degenerate
     * digest fails, so callers keep the raw messages instead.
     */
    open fun validate(state: StructuredState, sourceMessages: List<Message>): Boolean {
        if (state.sourceCount == 0 || state.keywords.isEmpty()) return false
        val sourceKeywords = sourceMessages
            .filter { it.metadata?.isError != true }
            .takeLast(state.sourceCount + KEEP_RECENT_RAW)
            .joinToString(" ") { it.content }
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()
        if (sourceKeywords.isEmpty()) return false
        val overlap = state.keywords.count { it in sourceKeywords }.toDouble() /
            state.keywords.size
        return overlap >= minOverlapRatio
    }

    /** Outcome of one [compact] pass — what the orchestrator logs (§11). */
    data class CompactionResult(
        val items: List<ContextItem>,
        val evictedCount: Int,
        /** Tokens of the [StructuredState] block, 0 when not included. */
        val summaryTokens: Int,
        /** False = §14.4 validation failed and only raw eviction ran. */
        val summaryIncluded: Boolean
    )

    /**
     * One compaction pass over a fitted context: evicts oldest non-protected
     * items and prepends their [StructuredState] as a single OLDER_HISTORY
     * item (wireRole SYSTEM — guidance, not a fabricated chat turn).
     *
     * Invariants (§14.3): P0–P2 items are never evicted; the result never
     * exceeds the usable budget (the summary's own cost is included in the
     * eviction math); if the summary fails §14.4 validation, the raw eviction
     * result is returned unchanged (summary skipped, never silently
     * distorting).
     *
     * Returns null when nothing useful can be compacted (callers keep the
     * incoming list as-is). Mapping to [ChatMessage] stays the orchestrator's
     * job — one mapping logic, one place.
     */
    open fun compact(
        items: List<ContextItem>,
        usableForInput: Int,
        sourceMessages: List<Message>
    ): CompactionResult? {
        val evictable = items.filter {
            it.priority.level > ContextPriority.TASK_STATE.level
        }
        if (evictable.isEmpty()) return null

        val usage = items.sumOf { it.tokenEstimate }

        // Build + validate the digest FIRST: the summary's own cost reserves
        // space in the eviction math (§8 + §14.4).
        val state = buildStructuredState(
            sourceMessages,
            conversationId = items.firstOrNull()?.conversationId.orEmpty()
        )
        val trusted = validate(state, sourceMessages)
        val summaryTokens = if (trusted) state.render().length / 4 else 0
        val target = usableForInput - summaryTokens

        // Evict oldest non-protected items until the result — WITH the
        // summary when trusted — fits. Protected tiers are never evicted.
        val evict = mutableSetOf<String>()
        var projected = usage
        if (projected > target) {
            for (item in evictable.sortedWith(compareBy({ it.timestampMs }, { it.id }))) {
                if (projected <= target) break
                evict += item.id
                projected -= item.tokenEstimate
            }
            if (projected > target) return null // cannot fit even fully evicted
        } else if (!trusted) {
            // Nothing needs evicting and the summary is untrusted — the
            // incoming list is already the best raw result.
            return null
        }

        val kept = items.filter { it.id !in evict }
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))

        if (!trusted) {
            // §14.4: summary not trusted — raw eviction result only.
            return CompactionResult(kept, evict.size, 0, summaryIncluded = false)
        }

        val summaryItem = ContextItem(
            id = SUMMARY_ID,
            sourceType = ContextSourceType.CONVERSATION,
            sourceId = SUMMARY_ID,
            priority = ContextPriority.OLDER_HISTORY,
            relevanceScore = 0f,
            content = state.render(),
            timestampMs = 0L,
            conversationId = items.firstOrNull()?.conversationId.orEmpty(),
            // Guidance block, never a fabricated assistant/user turn.
            wireRole = com.neuron.ai.core.provider.ChatMessage.Role.SYSTEM
        )
        return CompactionResult(
            items = (listOf(summaryItem) + kept),
            evictedCount = evict.size,
            summaryTokens = summaryItem.tokenEstimate,
            summaryIncluded = true
        )
    }

    companion object {
        const val SUMMARY_ID = "compaction-summary"

        /** Newest raw turns never enter the digest — they stay in context raw. */
        const val KEEP_RECENT_RAW = 4
    }
}
