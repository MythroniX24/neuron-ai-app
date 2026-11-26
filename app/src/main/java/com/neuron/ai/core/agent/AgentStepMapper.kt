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
        val effectiveTitle = if (!isThinking && activity.toolId != null) {
            resolveActivityTitle(activity.toolId, "", toolTitle ?: activity.title)
        } else {
            toolTitle
        }
        val type = typeOverride ?: classify(activity.toolId, isThinking, activity.actionDetail)
        val label = buildLabel(activity, effectiveTitle, isThinking)
        return com.neuron.ai.core.conversation.AgentStepRecord(
            stepId = activity.stepId,
            type = type,
            label = label,
            // CoT guard: thinking steps NEVER carry detail.
            detail = if (isThinking) null else activity.actionDetail?.take(200),
            toolId = activity.toolId,
            // Same CoT guard as [detail]: thinking steps carry no raw context.
            actionDetail = if (isThinking) null else activity.actionDetail?.take(200),
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
    fun typeOf(toolId: String?, stepId: String = "", actionDetail: String? = null): String {
        val isThinking = toolId == null || stepId.startsWith("think-")
        return classify(toolId, isThinking, actionDetail)
    }

    private fun classify(toolId: String?, isThinking: Boolean, actionDetail: String? = null): String = when {
        isThinking || toolId == null -> com.neuron.ai.core.conversation.AgentStepRecord.TYPE_THINKING
        toolId.startsWith("terminal.") -> {
            val d = (actionDetail ?: "").lowercase()
            when {
                d.contains("git commit") && d.contains("git push") ->
                    com.neuron.ai.core.conversation.AgentStepRecord.TYPE_GIT_COMMIT_PUSH
                d.contains("git commit") ->
                    com.neuron.ai.core.conversation.AgentStepRecord.TYPE_GIT_COMMIT
                d.contains("git push") ->
                    com.neuron.ai.core.conversation.AgentStepRecord.TYPE_GIT_PUSH
                else ->
                    com.neuron.ai.core.conversation.AgentStepRecord.TYPE_COMMAND
            }
        }
        toolId.startsWith("github.") ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_GITHUB
        toolId.startsWith("google.") ->
            com.neuron.ai.core.conversation.AgentStepRecord.TYPE_GOOGLE
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
     * Resolves human-friendly activity titles for connectors (GitHub, Git commit/push, Google).
     */
    fun resolveActivityTitle(
        toolId: String,
        argumentsJson: String,
        fallbackTitle: String
    ): String {
        if (toolId.startsWith("terminal.")) {
            val cmd = runCatching {
                Json.parseToJsonElement(argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
            }.getOrNull()?.trim().orEmpty()
            val isCommit = cmd.contains("git commit", ignoreCase = true)
            val isPush = cmd.contains("git push", ignoreCase = true)
            return when {
                isCommit && isPush -> "Commit & push"
                isCommit -> "Commit"
                isPush -> "Push"
                else -> fallbackTitle
            }
        }
        return when (toolId) {
            "github.issues.create" -> "Create issue"
            "github.issues.list" -> "List issues"
            "github.pulls.list" -> "Pull requests"
            "github.repo.search" -> "Search GitHub"
            "github.repo.get" -> "GitHub repo"
            "github.repo.readme" -> "Read README"
            "github.file.get" -> "Read GitHub file"
            "github.user.get" -> "View GitHub profile"
            "google.gmail.send" -> "Send email"
            "google.gmail.draft" -> "Save draft"
            "google.gmail.read", "google.gmail.thread" -> "Read email"
            "google.gmail.search" -> "Search Gmail"
            "google.drive.list", "google.drive.search" -> "Google Drive"
            "google.drive.read" -> "Read Drive file"
            "google.drive.move" -> "Move Drive file"
            "google.drive.delete" -> "Delete Drive file"
            "google.docs.create" -> "Create Google Doc"
            "google.docs.read" -> "Read Google Doc"
            "google.docs.write" -> "Edit Google Doc"
            "google.sheets.create" -> "Create Sheet"
            "google.sheets.read" -> "Read Sheet"
            "google.sheets.update", "google.sheets.append" -> "Update Sheet"
            "google.calendar.create" -> "Create event"
            "google.calendar.delete" -> "Delete event"
            "google.calendar.list" -> "Calendar events"
            "google.slides.get" -> "Google Slides"
            "google.slides.create" -> "Create presentation"
            "google.slides.addSlide" -> "Add slide"
            else -> when {
                toolId.startsWith("github.") -> "GitHub"
                toolId.startsWith("google.gmail.") -> "Gmail"
                toolId.startsWith("google.drive.") -> "Google Drive"
                toolId.startsWith("google.docs.") -> "Google Docs"
                toolId.startsWith("google.sheets.") -> "Google Sheets"
                toolId.startsWith("google.calendar.") -> "Google Calendar"
                toolId.startsWith("google.slides.") -> "Google Slides"
                toolId.startsWith("google.") -> "Google"
                else -> fallbackTitle
            }
        }
    }

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
            base.contains(detail, ignoreCase = true) -> base
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

        if (toolId.startsWith("terminal.")) {
            val cmd = obj["command"]?.jsonPrimitive?.content?.trim().orEmpty()
            val isCommit = cmd.contains("git commit", ignoreCase = true)
            val isPush = cmd.contains("git push", ignoreCase = true)
            if (isCommit || isPush) {
                val commitMsgRegex = Regex("""-m\s+["']([^"']+)["']""")
                val commitMsg = commitMsgRegex.find(cmd)?.groupValues?.get(1)
                val pushTarget = if (isPush) {
                    val pushPart = cmd.substringAfter("git push").trim().substringBefore("&&").substringBefore(";").trim()
                    pushPart.ifBlank { "origin" }
                } else null

                return when {
                    isCommit && isPush -> {
                        val msg = commitMsg ?: "commit"
                        "$msg ($pushTarget)"
                    }
                    isCommit -> commitMsg ?: cmd
                    isPush -> pushTarget ?: cmd
                    else -> cmd
                }.take(120)
            }
            return cmd.take(120)
        }

        if (toolId.startsWith("github.")) {
            val owner = obj["owner"]?.jsonPrimitive?.content.orEmpty()
            val repo = obj["repo"]?.jsonPrimitive?.content.orEmpty()
            val repoContext = if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else ""
            val title = obj["title"]?.jsonPrimitive?.content
            val path = obj["path"]?.jsonPrimitive?.content
            val query = obj["query"]?.jsonPrimitive?.content

            return when {
                !title.isNullOrBlank() && repoContext.isNotBlank() -> "$title ($repoContext)"
                !title.isNullOrBlank() -> title
                !path.isNullOrBlank() && repoContext.isNotBlank() -> "$path ($repoContext)"
                !path.isNullOrBlank() -> path
                !query.isNullOrBlank() -> query
                repoContext.isNotBlank() -> repoContext
                else -> obj["user"]?.jsonPrimitive?.content
            }?.take(120)
        }

        if (toolId.startsWith("google.")) {
            val to = obj["to"]?.jsonPrimitive?.content.orEmpty()
            val subject = obj["subject"]?.jsonPrimitive?.content.orEmpty()
            val title = obj["title"]?.jsonPrimitive?.content
            val summary = obj["summary"]?.jsonPrimitive?.content
            val query = obj["query"]?.jsonPrimitive?.content
            val range = obj["range"]?.jsonPrimitive?.content
            val name = obj["name"]?.jsonPrimitive?.content
            val docId = obj["documentId"]?.jsonPrimitive?.content
                ?: obj["spreadsheetId"]?.jsonPrimitive?.content
                ?: obj["fileId"]?.jsonPrimitive?.content

            return when {
                to.isNotBlank() && subject.isNotBlank() -> "$to — $subject"
                to.isNotBlank() -> to
                !summary.isNullOrBlank() -> summary
                !title.isNullOrBlank() -> title
                !range.isNullOrBlank() -> range
                !name.isNullOrBlank() -> name
                !query.isNullOrBlank() -> query
                !docId.isNullOrBlank() -> docId
                else -> null
            }?.take(120)
        }

        val keys = when {
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
