package com.neuron.ai.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mapping layer for the live activity timeline (Claude-style): turns internal
 * agent steps into UI-SAFE, human-readable summaries.
 *
 * HARD CONSTRAINT (spec § Security): never expose raw chain-of-thought.
 * Thinking steps render ONLY their short generic intent label; any detail on
 * a thinking step is dropped at the mapper level so a future caller bug can
 * never leak reasoning text into the timeline.
 *
 * Pure functions, no Android deps — trivially unit-testable.
 */
object AgentStepMapper {

    /**
     * Maps one agent activity to a persistable, UI-safe step record.
     * The caller supplies the tool's own public title as [toolTitle] when
     * known — labels stay in the tool's own words ("Searching web").
     */
    fun toRecord(
        activity: AgentActivity,
        toolTitle: String? = null,
        /** Overrides the type (used for mid-loop narration steps). */
        typeOverride: String? = null
    ): com.neuron.ai.core.conversation.AgentStepRecord {
        val isThinking = activity.toolId == null || activity.stepId.startsWith("think-")
        val type = typeOverride ?: classify(activity.toolId, isThinking)
        val label = buildLabel(activity, toolTitle, isThinking)
        return com.neuron.ai.core.conversation.AgentStepRecord(
            stepId = activity.stepId,
            type = type,
            label = label,
            // CoT guard: thinking steps NEVER carry detail.
            detail = if (isThinking) null else activity.actionDetail?.take(200),
            toolId = activity.toolId,
            status = when (activity.state) {
                AgentActivity.State.PENDING, AgentActivity.State.WAITING_FOR_PERMISSION ->
                    com.neuron.ai.core.conversation.AgentStepRecord.STATUS_RUNNING
                AgentActivity.State.RUNNING ->
                    com.neuron.ai.core.conversation.AgentStepRecord.STATUS_RUNNING
                AgentActivity.State.DONE ->
                    com.neuron.ai.core.conversation.AgentStepRecord.STATUS_DONE
                AgentActivity.State.FAILED ->
                    com.neuron.ai.core.conversation.AgentStepRecord.STATUS_FAILED
            },
            startedAtEpochMs = activity.startedAtEpochMs,
            finishedAtEpochMs = activity.finishedAtEpochMs
        )
    }

    /**
     * Timeline step type: each gets a distinct icon + label style in the UI.
     */
    fun typeOf(toolId: String?, stepId: String = ""): String {
        val isThinking = toolId == null || stepId.startsWith("think-")
        return classify(toolId, isThinking)
    }

    private fun classify(toolId: String?, isThinking: Boolean): String = when {
        isThinking -> com.neuron.ai.core.conversation.AgentStepRecord.TYPE_THINKING
        toolId == null -> com.neuron.ai.core.conversation.AgentStepRecord.TYPE_THINKING
        toolId.startsWith("terminal.") ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_COMMAND
        toolId.startsWith("web.search") || toolId.startsWith("browser.") ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_WEB_SEARCH
        toolId == "web.read" || toolId == "fs.read" || toolId == "fs.readfile" ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_FILE_READ
        toolId in FILE_WRITE_TOOLS ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_FILE_EDIT
        toolId in BUILD_TOOLS ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_BUILD_TEST
        else -> com.neuron.ai.core.conversation.AgentStepRecord.TYPE_TOOL_CALL
    }

    private val FILE_WRITE_TOOLS = setOf(
        "code.edit", "code.patch", "fs.writefile", "fs.mkdir", "fs.rename"
    )

    private val BUILD_TOOLS = setOf("code.build", "code.test")

    /**
     * Short human label. Tool steps use the tool's own title + the concrete
     * action ("Reading file: ChatScreen.kt", "Running ./gradlew test");
     * thinking steps use generic intent wording only.
     */
    private fun buildLabel(
        activity: AgentActivity,
        toolTitle: String?,
        isThinking: Boolean
    ): String {
        if (isThinking) {
            // Generic intent — never the underlying reasoning trace.
            return if (activity.title.startsWith("think", ignoreCase = true)) {
                "Thinking"
            } else {
                activity.title.take(60)
            }
        }
        val base = toolTitle ?: activity.title
        val detail = activity.actionDetail
        return when {
            detail.isNullOrBlank() -> base
            else -> "$base: ${detail.take(80)}"
        }
    }

    /**
     * Extracts the concrete action context from raw tool arguments (the
     * actual query / command / path) — model OUTPUT metadata, never its
     * internal reasoning. Returns null when nothing recognizable exists.
     */
    fun extractActionDetail(toolId: String?, argumentsJson: String): String? {
        if (toolId == null || argumentsJson.isBlank()) return null
        val obj = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject
        }.getOrNull() ?: return null
        val keys = when {
            toolId.startsWith("terminal.") -> listOf("command")
            toolId.startsWith("web.search") -> listOf("query")
            toolId == "web.read" || toolId.startsWith("browser.") -> listOf("url")
            toolId.startsWith("fs.") || toolId.startsWith("code.") ->
                listOf("path", "task", "pattern")
            else -> listOf("query", "command", "url", "path", "task", "pattern", "expression", "text")
        }
        for (key in keys) {
            val value = obj[key] ?: continue
            val text = runCatching { value.jsonPrimitive.content }.getOrNull()
            if (!text.isNullOrBlank()) return text.take(120)
        }
        return null
    }
}
