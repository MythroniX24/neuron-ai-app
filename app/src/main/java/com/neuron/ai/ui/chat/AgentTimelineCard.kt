package com.neuron.ai.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuron.ai.core.conversation.AgentStepRecord
import com.neuron.ai.ui.theme.Spacing
import kotlin.math.max

/**
 * Collapsible "Worked for …" header shown above one agent turn. Tapping it
 * hides/shows the Thought + tool rows; the final answer text stays visible.
 */
@Composable
fun AgentTurnHeader(
    elapsedMs: Long,
    expanded: Boolean,
    isLive: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -180f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "runHeaderChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLive) {
                "Working for ${formatRunDuration(elapsedMs)}"
            } else {
                "Worked for ${formatRunDuration(elapsedMs)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse work" else "Expand work",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

/** "56m 39s" / "1h 2m 3s" — matches the reference's compact duration label. */
fun formatRunDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/** Total span of a persisted turn, falling back to stored generation time. */
fun runDurationMs(
    steps: List<AgentStepRecord>,
    fallbackMs: Long? = null
): Long {
    val starts = steps.mapNotNull { it.startedAtEpochMs.takeIf { time -> time > 0L } }
    val ends = steps.mapNotNull { it.finishedAtEpochMs }
    val span = if (starts.isNotEmpty() && ends.isNotEmpty()) {
        (ends.maxOrNull() ?: 0L) - (starts.minOrNull() ?: 0L)
    } else {
        0L
    }
    return max(fallbackMs ?: 0L, span.coerceAtLeast(0L))
}

/**
 * Borderless agent timeline (Thought blocks + grouped tool calls). No card
 * surface: the rows read as part of the assistant message, exactly like the
 * reference. Every row expands downward; tool output is resolved lazily from
 * the persisted TOOL messages.
 */
@Composable
fun AgentTimelineCard(
    steps: List<AgentStepRecord>,
    modifier: Modifier = Modifier,
    fullResultResolver: (String) -> String? = { null }
) {
    val entries = remember(steps) { buildAgentRunEntries(steps) }
    if (entries.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is AgentRunEntry.Thought -> AgentThoughtBlock(
                    entry = entry,
                    defaultExpanded = index == 0
                )
                is AgentRunEntry.Tools -> AgentToolGroupBlock(
                    group = entry.group,
                    fullResultResolver = fullResultResolver
                )
            }
        }
    }
}

@Composable
private fun AgentThoughtBlock(
    entry: AgentRunEntry.Thought,
    defaultExpanded: Boolean
) {
    var expanded by remember(entry.stepId) { mutableStateOf(defaultExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -180f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "thoughtChevron"
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "✦",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = "Thought",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse thought" else "Expand thought",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        AnimatedVisibility(
            visible = expanded && entry.text.isNotBlank(),
            enter = expandVertically(tween(190)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 26.dp, bottom = Spacing.sm)
            )
        }
    }
}

@Composable
private fun AgentToolGroupBlock(
    group: AgentToolGroup,
    fullResultResolver: (String) -> String?
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -180f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "toolChevron"
    )
    val count = group.calls.size
    val header = if (count > 1) {
        "${group.kind.groupLabel} · $count calls"
    } else {
        group.kind.singleLabel
    }
    val path = group.calls.firstOrNull()?.path
    val failed = group.calls.any { it.step.status == AgentStepRecord.STATUS_FAILED }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = group.kind.icon,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else group.kind.tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = header,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (count == 1 && !path.isNullOrBlank()) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse tool call" else "Expand tool call",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(160)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier.padding(start = 26.dp, bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                group.calls.forEach { call ->
                    AgentToolCallDetail(
                        call = call,
                        fullResultResolver = fullResultResolver
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentToolCallDetail(
    call: AgentToolCall,
    fullResultResolver: (String) -> String?
) {
    val detail = call.step.actionDetail ?: call.path
    if (!detail.isNullOrBlank()) {
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
    val output = fullResultResolver(call.step.stepId) ?: call.step.detail
    if (!output.isNullOrBlank()) {
        Text(
            text = output.take(4_000),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(10.dp)
                )
                .padding(8.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Pure mapping: persisted/live steps → renderable Thought and tool groups
// ---------------------------------------------------------------------------

internal sealed interface AgentRunEntry {
    data class Thought(
        val stepId: String,
        val text: String
    ) : AgentRunEntry

    data class Tools(val group: AgentToolGroup) : AgentRunEntry
}

internal data class AgentToolGroup(
    val key: String,
    val kind: AgentToolKind,
    val calls: List<AgentToolCall>
)

internal data class AgentToolCall(
    val step: AgentStepRecord,
    val title: String,
    val path: String?
)

internal enum class AgentToolKind(
    val groupLabel: String,
    val singleLabel: String,
    val icon: ImageVector,
    val tint: Color
) {
    SEARCH("Search", "Search", Icons.Outlined.Search, Color(0xFFE6B450)),
    READ("Read", "Read files", Icons.Outlined.Description, Color(0xFF7BC47F)),
    LIST("List", "List", Icons.Outlined.FolderOpen, Color(0xFF7BC47F)),
    FIND("Find files", "Find files", Icons.Outlined.Search, Color(0xFFE6B450)),
    EDIT("Edit", "Edit files", Icons.Outlined.Edit, Color(0xFF9AA4B2)),
    COMMAND("Run", "Run", Icons.Outlined.Terminal, Color(0xFF9AA4B2)),
    BUILD("Build", "Build", Icons.Outlined.Build, Color(0xFF9AA4B2)),
    GIT_COMMIT("Commit", "Commit", Icons.Outlined.CheckCircle, Color(0xFFF97316)),
    GIT_PUSH("Push", "Push", Icons.Outlined.Upload, Color(0xFF3B82F6)),
    GIT_COMMIT_AND_PUSH("Commit & push", "Commit & push", Icons.Outlined.CheckCircle, Color(0xFF10B981)),
    GITHUB("GitHub", "GitHub", Icons.Outlined.Code, Color(0xFF8B5CF6)),
    GMAIL("Gmail", "Gmail", Icons.Outlined.MailOutline, Color(0xFFEA4335)),
    GOOGLE_DRIVE("Google Drive", "Google Drive", Icons.Outlined.Folder, Color(0xFF4285F4)),
    GOOGLE_DOCS("Google Docs", "Google Docs", Icons.Outlined.Description, Color(0xFF4285F4)),
    GOOGLE_SHEETS("Google Sheets", "Google Sheets", Icons.Outlined.TableChart, Color(0xFF0F9D58)),
    GOOGLE_CALENDAR("Google Calendar", "Google Calendar", Icons.Outlined.CalendarToday, Color(0xFFFBBC04)),
    GOOGLE_SLIDES("Google Slides", "Google Slides", Icons.Outlined.Description, Color(0xFFF4B400)),
    OTHER("Tool", "Tool", Icons.Outlined.Build, Color(0xFF9AA4B2))
}

private val GENERIC_THOUGHT_LABELS = setOf(
    "Thinking", "Understanding request", "Continuing", "Thought"
)

internal fun buildAgentRunEntries(steps: List<AgentStepRecord>): List<AgentRunEntry> {
    val visible = steps.filterNot { it.type == AgentStepRecord.TYPE_ROUTING }
    val entries = mutableListOf<AgentRunEntry>()
    var index = 0
    while (index < visible.size) {
        val step = visible[index]
        if (isThought(step)) {
            // Generic loop bookkeeping without a body is noise after the run.
            val canShow = step.detail?.isNotBlank() == true ||
                step.label !in GENERIC_THOUGHT_LABELS
            if (canShow) {
                val text = step.detail?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: step.label.takeIf { it !in GENERIC_THOUGHT_LABELS }.orEmpty()
                if (text.isNotBlank()) {
                    entries += AgentRunEntry.Thought(step.stepId, text)
                }
            }
            index++
            continue
        }

        var kind = kindOf(step)
        val calls = mutableListOf<AgentToolCall>()

        // Merge commit + push sequence into a unified "Commit & push" group
        val nextStep = visible.getOrNull(index + 1)
        val isCommitPushPair = nextStep != null && !isThought(nextStep) && (
            (kind == AgentToolKind.GIT_COMMIT && kindOf(nextStep) == AgentToolKind.GIT_PUSH) ||
            (kind == AgentToolKind.GIT_PUSH && kindOf(nextStep) == AgentToolKind.GIT_COMMIT)
        )

        if (isCommitPushPair) {
            kind = AgentToolKind.GIT_COMMIT_AND_PUSH
            calls += AgentToolCall(step, displayTitle(step), actionPath(step))
            index++
            val secondStep = visible[index]
            calls += AgentToolCall(secondStep, displayTitle(secondStep), actionPath(secondStep))
            index++
        } else {
            while (index < visible.size && !isThought(visible[index])) {
                val callStep = visible[index]
                if (kindOf(callStep) != kind) break
                calls += AgentToolCall(
                    step = callStep,
                    title = displayTitle(callStep),
                    path = actionPath(callStep)
                )
                index++
            }
        }

        entries += AgentRunEntry.Tools(
            AgentToolGroup(
                key = "${kind.name}-${calls.first().step.stepId}",
                kind = kind,
                calls = calls
            )
        )
    }
    return entries
}

private fun isThought(step: AgentStepRecord): Boolean =
    step.type == AgentStepRecord.TYPE_THINKING ||
        step.type == AgentStepRecord.TYPE_INTERMEDIATE

private fun kindOf(step: AgentStepRecord): AgentToolKind {
    val tool = step.toolId.orEmpty()
    val label = step.label.lowercase()
    val detail = (step.actionDetail ?: "").lowercase()

    return when {
        step.type == AgentStepRecord.TYPE_GIT_COMMIT_PUSH ||
            ("commit" in label && "push" in label) ||
            ("git commit" in detail && "git push" in detail) ->
            AgentToolKind.GIT_COMMIT_AND_PUSH

        step.type == AgentStepRecord.TYPE_GIT_COMMIT ||
            label.startsWith("commit") ||
            ("git commit" in detail) ->
            AgentToolKind.GIT_COMMIT

        step.type == AgentStepRecord.TYPE_GIT_PUSH ||
            label.startsWith("push") ||
            ("git push" in detail) ->
            AgentToolKind.GIT_PUSH

        tool.startsWith("github.") || step.type == AgentStepRecord.TYPE_GITHUB || "github" in label ->
            AgentToolKind.GITHUB

        tool.startsWith("google.gmail.") || "gmail" in label || "email" in label ->
            AgentToolKind.GMAIL

        tool.startsWith("google.drive.") || "drive" in label ->
            AgentToolKind.GOOGLE_DRIVE

        tool.startsWith("google.docs.") || "doc" in label ->
            AgentToolKind.GOOGLE_DOCS

        tool.startsWith("google.sheets.") || "sheet" in label ->
            AgentToolKind.GOOGLE_SHEETS

        tool.startsWith("google.calendar.") || "calendar" in label ->
            AgentToolKind.GOOGLE_CALENDAR

        tool.startsWith("google.slides.") || "slide" in label ->
            AgentToolKind.GOOGLE_SLIDES

        tool.startsWith("web.search") || tool.startsWith("browser.") -> AgentToolKind.SEARCH
        tool == "web.read" || tool == "fs.read" || tool == "fs.readfile" -> AgentToolKind.READ
        tool == "fs.listdir" || tool == "fs.list" -> AgentToolKind.LIST
        tool == "fs.search" || tool == "fs.glob" || tool == "fs.find" -> AgentToolKind.FIND
        tool in setOf("fs.writefile", "fs.mkdir", "fs.rename", "fs.move", "fs.copy", "code.edit", "code.patch") ->
            AgentToolKind.EDIT
        tool.startsWith("terminal.") -> AgentToolKind.COMMAND
        tool == "code.build" || tool == "code.test" -> AgentToolKind.BUILD
        else -> when (step.type) {
            AgentStepRecord.TYPE_WEB_SEARCH -> AgentToolKind.SEARCH
            AgentStepRecord.TYPE_FILE_READ -> AgentToolKind.READ
            AgentStepRecord.TYPE_FILE_EDIT -> AgentToolKind.EDIT
            AgentStepRecord.TYPE_COMMAND -> AgentToolKind.COMMAND
            AgentStepRecord.TYPE_BUILD_TEST -> AgentToolKind.BUILD
            // Legacy activity rows have no toolId: classify by their public
            // title so old runs still get the right icon/group.
            else -> {
                val l = step.label.lowercase()
                when {
                    "search" in l || "find" in l -> AgentToolKind.SEARCH
                    "list" in l -> AgentToolKind.LIST
                    "read" in l -> AgentToolKind.READ
                    "edit" in l || "write" in l -> AgentToolKind.EDIT
                    "run" in l || "terminal" in l -> AgentToolKind.COMMAND
                    "build" in l || "test" in l -> AgentToolKind.BUILD
                    else -> AgentToolKind.OTHER
                }
            }
        }
    }
}

private fun displayTitle(step: AgentStepRecord): String {
    val label = step.label
    val kind = kindOf(step)
    return when (kind) {
        AgentToolKind.GIT_COMMIT -> "Commit"
        AgentToolKind.GIT_PUSH -> "Push"
        AgentToolKind.GIT_COMMIT_AND_PUSH -> "Commit & push"
        AgentToolKind.GITHUB -> if (label.contains(": ")) label.substringBefore(": ") else "GitHub"
        AgentToolKind.GMAIL -> if (label.contains(": ")) label.substringBefore(": ") else "Gmail"
        AgentToolKind.GOOGLE_DRIVE -> if (label.contains(": ")) label.substringBefore(": ") else "Google Drive"
        AgentToolKind.GOOGLE_DOCS -> if (label.contains(": ")) label.substringBefore(": ") else "Google Docs"
        AgentToolKind.GOOGLE_SHEETS -> if (label.contains(": ")) label.substringBefore(": ") else "Google Sheets"
        AgentToolKind.GOOGLE_CALENDAR -> if (label.contains(": ")) label.substringBefore(": ") else "Google Calendar"
        AgentToolKind.GOOGLE_SLIDES -> if (label.contains(": ")) label.substringBefore(": ") else "Google Slides"
        else -> label.substringBefore(": ").ifBlank { "Tool" }
    }
}

private fun actionPath(step: AgentStepRecord): String? {
    val detail = step.actionDetail?.trim()
    if (!detail.isNullOrBlank()) return detail
    val fromLabel = step.label.substringAfter(": ", "").trim()
    return fromLabel.takeIf { it.isNotBlank() }
}
