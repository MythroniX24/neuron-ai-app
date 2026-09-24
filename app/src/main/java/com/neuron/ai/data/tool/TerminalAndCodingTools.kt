package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.data.terminal.TerminalLine
import com.neuron.ai.data.terminal.TerminalManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Last-resort guard for obviously catastrophic commands coming from the model.
 * The REAL authority remains the permission system (ELEVATED risk + user
 * approval); sh is not statically analyzable, so this is defense-in-depth
 * against device-bricking patterns only — not a general sandbox.
 */
object CommandGuard {
    private val catastrophic = listOf(
        Regex("\\brm\\s+(-\\S+\\s+)+/\\s*$"),                     // rm -rf / (any flags)
        Regex("\\brm\\s+(-\\S+\\s+)+/\\*\\s*$"),                 // rm -rf /*
        Regex("\\bmkfs\\b"),                                     // format filesystem (incl. mkfs.ext4)
        Regex("\\bdd\\b[^|;&]*\\bof=/dev/(block|mmcblk|sd[a-z])"),  // raw device write
        Regex(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:"),                  // fork bomb
        Regex("\\bchmod\\s+(-\\S+\\s+)*0?0?0\\s+/\\s*$")                   // chmod 000 /
    )

    fun isCatastrophic(command: String): Boolean =
        catastrophic.any { it.containsMatchIn(command) }
}

/**
 * AI Terminal tool — uses the SAME TerminalManager/session as the user's
 * terminal panel. Gated by the per-conversation Terminal capability:
 * - ENABLED  → the user's toggle IS the standing consent; commands run
 *   directly without another prompt (guard checks still apply).
 * - DISABLED → the tool raises a real permission request (popup); only an
 *   explicit grant executes the command. It is never silently enabled.
 */
class TerminalTool(
    private val env: WorkspaceToolEnv,
    private val terminalManager: TerminalManager,
    private val permissions: com.neuron.ai.core.permissions.PermissionManager
) : Tool {

    override val id = "terminal.run"
    override val title = "Running command"
    override val description =
        "Executes a shell command in the conversation's terminal session " +
            "(workspace working directory). Requires Terminal capability enabled."
    // Permission flow is handled INSIDE execute() so the per-conversation
    // toggle can act as standing consent; an empty set means the executor
    // never prompts ahead of the capability check.
    override val requiredCapabilities = emptySet<com.neuron.ai.core.permissions.Capability>()
    override val riskLevel = RiskLevel.ELEVATED
    override val timeoutMs = 180_000L
    override val parametersSchemaJson =
        """{"type":"object","properties":{"command":{"type":"string"},"timeoutSec":{"type":"integer"}},"required":["command"]}"""

    override suspend fun execute(argumentsJson: String): ToolResult {
        val command = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject["command"]?.jsonPrimitive?.content
        }.getOrNull()
            ?: return ToolResult.Failure("Missing required argument: command")
        // Model may send the timeout as JSON integer or string — accept both.
        val timeoutSec = runCatching {
            val el = Json.parseToJsonElement(argumentsJson).jsonObject["timeoutSec"]
            when {
                el == null -> null
                el is kotlinx.serialization.json.JsonPrimitive && el.isString -> el.content.toLong()
                else -> (el as kotlinx.serialization.json.JsonPrimitive).content.toLong()
            }
        }.getOrNull() ?: 120L
        return runCommand(command, timeoutSec)
    }

    /** Shared execution path for the AI tool and build/test tools. */
    suspend fun runCommand(command: String, timeoutSec: Long): ToolResult {
        // Last-resort guard: refuse obviously device-destroying commands outright.
        if (CommandGuard.isCatastrophic(command)) {
            return ToolResult.Failure(
                "Command refused: this pattern can destroy the device's filesystem. " +
                    "Run it manually if you truly intend it."
            )
        }
        // Capability gate: the per-conversation toggle is the user's standing
        // consent (no second prompt). When OFF, ask the user now — a popup,
        // never a silent enable and never a silent execution.
        if (!env.terminalEnabled()) {
            val granted = permissions.request(
                capability = Capability.TERMINAL,
                reason = "Terminal access is off for this chat. Allow \"$title\" to run this command once?",
                requestedBy = title,
                riskLevel = RiskLevel.ELEVATED
            )
            if (!granted) {
                return ToolResult.Failure(
                    "Terminal access was not granted. Tell the user to enable it via + → Terminal " +
                        "if they want terminal commands in this chat."
                )
            }
        }
        val contextKey = env.terminalSessionKey()
            ?: return ToolResult.Failure("No conversation context for a terminal session.")
        val workspace = env.activeWorkspace()

        val session = terminalManager.sessionFor(contextKey, workspace?.workspace?.id, workspace?.root)
        // Snapshot the buffer before running so the model only sees output
        // produced by THIS command, not the session's whole history.
        val linesBefore = session.output.first().size
        val exitCode = session.execute(
            command = command,
            timeoutMs = timeoutSec.coerceIn(5, 600) * 1_000,
            // AI runs are pinned to the workspace root: the user panel's `cd`
            // must never redirect where the agent executes commands.
            workingDir = workspace?.root
        )
        val output = session.output.first()
            .drop(linesBefore)
            .takeLast(100)
            .joinToString("\n") { line ->
                when (line.stream) {
                    TerminalLine.Stream.STDERR -> "[err] " + line.text
                    else -> line.text
                }
            }

        return if (exitCode == 0) {
            ToolResult.Success("exit=$exitCode\n$output")
        } else {
            // Structured failure so the model can read the output and react
            // (read the error, fix code, retry) instead of dead-ending.
            ToolResult.Failure("exit=$exitCode\n$output\n(command failed — inspect [err] lines above)")
        }
    }
}

/**
 * Coding tools: edit, patch, diff, and build/test via the shared terminal.
 * All operate only inside the attached workspace boundary.
 */
object CodingTools {

    private val json = Json { ignoreUnknownKeys = true }

    private fun arg(args: String, key: String): String? = runCatching {
        json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    // ---- EditCode ------------------------------------------------------------------------
    class EditCode(private val env: WorkspaceToolEnv) : Tool {
        override val id = "code.edit"
        override val title = "Updating code"
        override val description =
            "Replaces an exact old snippet with new text in a workspace file. " +
                "The old snippet must match exactly and uniquely."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val riskLevel = RiskLevel.ELEVATED
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val path = arg(argumentsJson, "path")
                ?: return ToolResult.Failure("Missing required argument: path")
            val oldText = arg(argumentsJson, "oldText")
                ?: return ToolResult.Failure("Missing required argument: oldText")
            val newText = arg(argumentsJson, "newText")
                ?: return ToolResult.Failure("Missing required argument: newText")

            return WorkspaceTools.withWorkspace(env) { ws ->
                // Refuse to edit oversized files: a truncated read followed by a
                // write would silently corrupt the tail of the file.
                if (ws.isOversized(path)) {
                    return@withWorkspace ToolResult.Failure(
                        "$path is too large to edit safely (limit 2 MB). Edit it in smaller parts."
                    )
                }
                val original = ws.readText(path, maxBytes = 2_000_000)
                    ?: return@withWorkspace ToolResult.Failure("File not found: $path")
                val matches = countOccurrences(original, oldText)
                when {
                    matches == 0 -> ToolResult.Failure(
                        "oldText not found in $path — read the file first and match exactly (whitespace matters)."
                    )
                    matches > 1 -> ToolResult.Failure(
                        "oldText matches $matches places in $path — add more surrounding context to make it unique."
                    )
                    else -> {
                        val updated = original.replace(oldText, newText)
                        if (ws.writeText(path, updated)) {
                            ToolResult.Success(
                                "Edited $path.\n" + DiffEngine.unifiedDiff(path, original, updated).take(4_000)
                            )
                        } else {
                            ToolResult.Failure("Could not write: $path")
                        }
                    }
                }
            }
        }
    }

    // ---- ApplyPatch -------------------------------------------------------------------------
    class ApplyPatch(private val env: WorkspaceToolEnv) : Tool {
        override val id = "code.patch"
        override val title = "Applying patch"
        override val description =
            "Applies a change to a workspace file with conflict detection: if the file's current " +
                "content no longer contains expectedText, the patch is rejected — no blind overwrites."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val riskLevel = RiskLevel.ELEVATED
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"},"expectedText":{"type":"string"},"replacement":{"type":"string"}},"required":["path","expectedText","replacement"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val path = arg(argumentsJson, "path")
                ?: return ToolResult.Failure("Missing required argument: path")
            val expectedText = arg(argumentsJson, "expectedText")
                ?: return ToolResult.Failure("Missing required argument: expectedText")
            val replacement = arg(argumentsJson, "replacement")
                ?: return ToolResult.Failure("Missing required argument: replacement")

            return WorkspaceTools.withWorkspace(env) { ws ->
                if (ws.isOversized(path)) {
                    return@withWorkspace ToolResult.Failure(
                        "$path is too large to edit safely (limit 2 MB). Edit it in smaller parts."
                    )
                }
                val original = ws.readText(path, maxBytes = 2_000_000)
                    ?: return@withWorkspace ToolResult.Failure("File not found: $path")
                // Conflict detection: the expected anchor must exist EXACTLY once —
                // 0 matches means the file changed since it was read (never blind-
                // overwrite), >1 means the anchor is ambiguous. An empty anchor is
                // always rejected: replaceFirst("") would prepend and corrupt the file.
                val anchorCount = countOccurrences(original, expectedText)
                when {
                    anchorCount == 0 -> return@withWorkspace ToolResult.Failure(
                        "Conflict: $path changed since it was read — expectedText not found. " +
                            "Re-read the file and retry."
                    )
                    anchorCount > 1 -> return@withWorkspace ToolResult.Failure(
                        "expectedText matches $anchorCount places in $path — add more surrounding context to make it unique."
                    )
                }
                val updated = original.replaceFirst(expectedText, replacement)
                if (ws.writeText(path, updated)) {
                    ToolResult.Success(
                        "Patched $path.\n" + DiffEngine.unifiedDiff(path, original, updated).take(4_000)
                    )
                } else {
                    ToolResult.Failure("Could not write: $path")
                }
            }
        }
    }

    // ---- GenerateDiff -------------------------------------------------------------------------
    class Diff(private val env: WorkspaceToolEnv) : Tool {
        override val id = "code.diff"
        override val title = "Generating diff"
        override val description =
            "Shows a unified diff between expected (old) and replacement (new) text for review."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"}},"required":["path","oldText","newText"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val path = arg(argumentsJson, "path") ?: return ToolResult.Failure("Missing required argument: path")
            val oldText = arg(argumentsJson, "oldText") ?: return ToolResult.Failure("Missing required argument: oldText")
            val newText = arg(argumentsJson, "newText") ?: return ToolResult.Failure("Missing required argument: newText")
            return ToolResult.Success(DiffEngine.unifiedDiff(path, oldText, newText))
        }
    }

    // ---- RunBuild / RunTests (via shared terminal) ---------------------------------------------
    // Like terminal.run, these rely on the per-conversation Terminal toggle as
    // standing consent; with the toggle off, runCommand's popup flow asks.
    class RunBuild(private val env: WorkspaceToolEnv, terminalManager: TerminalManager, permissions: com.neuron.ai.core.permissions.PermissionManager) : Tool {
        private val terminal = TerminalTool(env, terminalManager, permissions)

        override val id = "code.build"
        override val title = "Building project"
        override val description =
            "Detects the project type and runs its build in the workspace terminal."
        override val requiredCapabilities = emptySet<Capability>()
        override val riskLevel = RiskLevel.ELEVATED
        override val timeoutMs = 300_000L
        override val parametersSchemaJson = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val workspace = env.activeWorkspace()
                ?: return ToolResult.Failure("No workspace attached to this chat.")
            val command = BuildDetector.buildCommand(workspace)
                ?: return ToolResult.Failure(
                    "No recognized build system found (gradle, maven, npm, make, cargo, pip)."
                )
            return terminal.runCommand(command, 300)
        }
    }

    class RunTests(private val env: WorkspaceToolEnv, terminalManager: TerminalManager, permissions: com.neuron.ai.core.permissions.PermissionManager) : Tool {
        private val terminal = TerminalTool(env, terminalManager, permissions)

        override val id = "code.test"
        override val title = "Running tests"
        override val description =
            "Detects the project type and runs its test suite in the workspace terminal."
        override val requiredCapabilities = emptySet<Capability>()
        override val riskLevel = RiskLevel.ELEVATED
        override val timeoutMs = 300_000L
        override val parametersSchemaJson = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val workspace = env.activeWorkspace()
                ?: return ToolResult.Failure("No workspace attached to this chat.")
            val command = BuildDetector.testCommand(workspace)
                ?: return ToolResult.Failure("No recognized build system with tests found.")
            return terminal.runCommand(command, 300)
        }
    }

    internal fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

/** Builds unified diffs: −old / +new with context headers. */
object DiffEngine {

    /** Line-based unified diff (LCS-free, sequential per changed hunk). */
    fun unifiedDiff(path: String, oldText: String, newText: String): String {
        val oldLines = oldText.lines()
        val newLines = newText.lines()
        val out = StringBuilder("--- $path\n+++ $path\n")
        var i = 0
        var j = 0
        var changes = 0
        while (i < oldLines.size || j < newLines.size) {
            if (i < oldLines.size && j < newLines.size && oldLines[i] == newLines[j]) {
                i++; j++; continue
            }
            // Find the next alignment point.
            var lookahead = 1
            while (lookahead <= 50) {
                if (i + lookahead < oldLines.size && j < newLines.size &&
                    oldLines[i + lookahead] == newLines[j]
                ) {
                    // Lines were removed.
                    repeat(lookahead) { k ->
                        out.appendLine("-" + oldLines[i + k])
                    }
                    i += lookahead
                    changes++
                    break
                }
                if (j + lookahead < newLines.size && i < oldLines.size &&
                    oldLines[i] == newLines[j + lookahead]
                ) {
                    // Lines were added.
                    repeat(lookahead) { k ->
                        out.appendLine("+" + newLines[j + k])
                    }
                    j += lookahead
                    changes++
                    break
                }
                lookahead++
            }
            if (lookahead > 50) {
                // Diverged — emit remaining as one changed hunk and stop.
                out.appendLine("@@ remainder @@")
                oldLines.drop(i).take(50).forEach { out.appendLine("-$it") }
                newLines.drop(j).take(50).forEach { out.appendLine("+$it") }
                changes++
                break
            }
        }
        if (changes == 0) out.appendLine("(no changes)")
        return out.toString()
    }
}

/** Detects build systems present in the workspace root (no hardcoding one). */
object BuildDetector {

    fun buildCommand(workspace: com.neuron.ai.data.workspace.WorkspaceContext): String? =
        when {
            workspace.resolve("gradlew")?.isFile == true -> "./gradlew assembleDebug -q"
            workspace.resolve("pom.xml")?.isFile == true -> "mvn -q package"
            workspace.resolve("package.json")?.isFile == true -> "npm run build"
            workspace.resolve("Makefile")?.isFile == true -> "make -j2"
            workspace.resolve("Cargo.toml")?.isFile == true -> "cargo build"
            workspace.resolve("requirements.txt")?.isFile == true -> "pip install -r requirements.txt"
            else -> null
        }

    fun testCommand(workspace: com.neuron.ai.data.workspace.WorkspaceContext): String? =
        when {
            workspace.resolve("gradlew")?.isFile == true -> "./gradlew testDebugUnitTest -q"
            workspace.resolve("pom.xml")?.isFile == true -> "mvn -q test"
            workspace.resolve("package.json")?.isFile == true -> "npm test"
            workspace.resolve("Cargo.toml")?.isFile == true -> "cargo test"
            workspace.resolve("Makefile")?.isFile == true -> "make test"
            else -> null
        }
}
