package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.RiskLevel
import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import com.neuron.ai.data.workspace.WorkspaceContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runtime environment the workspace tools resolve at execution time: the
 * conversation's active workspace, its terminal capability and its terminal
 * session key. Keeps tools stateless and conversation-scoped.
 */
interface WorkspaceToolEnv {
    /** Workspace context of the current conversation, or null when none attached. */
    suspend fun activeWorkspace(): WorkspaceContext?
    /** Whether AI Terminal access is enabled for the current conversation. */
    suspend fun terminalEnabled(): Boolean
    /** Stable session key (conversation id) for terminal session binding. */
    suspend fun terminalSessionKey(): String?
}

/**
 * Milestone 2 filesystem tools. Every tool validates arguments, resolves the
 * workspace, canonicalizes the path and stays inside the workspace boundary
 * before touching the filesystem. Errors are ToolResult values.
 */
object WorkspaceTools {

    private val json = Json { ignoreUnknownKeys = true }

    private fun arg(args: String, key: String): String? = runCatching {
        json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    private fun opt(args: String, key: String): String? =
        arg(args, key)?.takeIf { it.isNotBlank() }

    private fun stringList(args: String, key: String): List<String> = runCatching {
        json.parseToJsonElement(args).jsonObject[key]?.let { el ->
            kotlinx.serialization.json.jsonArray(el)
                .map { it.jsonPrimitive.content }
        }
    }.getOrNull() ?: emptyList()

    // helper used by all tools
    internal suspend fun withWorkspace(
        env: WorkspaceToolEnv,
        block: suspend (WorkspaceContext) -> ToolResult
    ): ToolResult {
        val workspace = env.activeWorkspace()
            ?: return ToolResult.Failure(
                "No workspace attached to this chat. Attach one with + → Workspace."
            )
        return block(workspace)
    }

    // ---- ListDirectory -----------------------------------------------------------------
    class ListDir(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.list"
        override val title = "Listing directory"
        override val description = "Lists entries of a directory in the workspace (default: root)."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}}}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = opt(argumentsJson, "path") ?: "."
            val entries = ws.list(path)
                ?: return@withWorkspace ToolResult.Failure("Directory not found: $path")
            if (entries.isEmpty()) {
                ToolResult.Success("(empty)")
            } else {
                ToolResult.Success(
                    entries.take(200).joinToString("\n") {
                        (if (it.isDirectory) "📁 " else "📄 ") + it.relativePath +
                            (it.sizeBytes?.let { s -> " (${s}B)" } ?: "")
                    } + if (entries.size > 200) "\n… and ${entries.size - 200} more" else ""
                )
            }
        }
    }

    // ---- ReadFile ------------------------------------------------------------------------
    class ReadFile(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.readfile"
        override val title = "Reading file"
        override val description = "Reads a text file from the workspace by relative path."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            val content = ws.readText(path)
                ?: return@withWorkspace ToolResult.Failure("File not found or unreadable: $path")
            ToolResult.Success(content)
        }
    }

    // ---- WriteFile -------------------------------------------------------------------------
    class WriteFile(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.writefile"
        override val title = "Writing file"
        override val description =
            "Creates or overwrites a text file in the workspace with the given content."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val riskLevel = RiskLevel.ELEVATED
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            val content = arg(argumentsJson, "content")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: content")
            if (ws.writeText(path, content)) {
                ToolResult.Success("Written: $path (${content.length} chars)")
            } else {
                ToolResult.Failure("Could not write: $path")
            }
        }
    }

    // ---- CreateDirectory ----------------------------------------------------------------------
    class MakeDir(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.mkdir"
        override val title = "Creating directory"
        override val description = "Creates a directory (and parents) inside the workspace."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            if (ws.mkdirs(path)) ToolResult.Success("Created directory: $path")
            else ToolResult.Failure("Could not create directory: $path")
        }
    }

    // ---- RenameFile -----------------------------------------------------------------------------
    class Rename(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.rename"
        override val title = "Renaming"
        override val description = "Renames a file or directory within its parent folder."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val riskLevel = RiskLevel.ELEVATED
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"},"newName":{"type":"string"}},"required":["path","newName"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            val newName = arg(argumentsJson, "newName")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: newName")
            if (ws.rename(path, newName)) ToolResult.Success("Renamed $path → $newName")
            else ToolResult.Failure("Could not rename $path to $newName")
        }
    }

    // ---- MoveFile / CopyFile ----------------------------------------------------------------------
    class Move(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.move"
        override val title = "Moving file"
        override val description = "Moves a file into another workspace directory."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val riskLevel = RiskLevel.ELEVATED
        override val parametersSchemaJson =
            """{"type":"object","properties":{"from":{"type":"string"},"toDir":{"type":"string"}},"required":["from","toDir"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val from = arg(argumentsJson, "from")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: from")
            val toDir = arg(argumentsJson, "toDir")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: toDir")
            if (ws.move(from, toDir)) ToolResult.Success("Moved $from → $toDir/")
            else ToolResult.Failure("Could not move $from to $toDir")
        }
    }

    class Copy(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.copy"
        override val title = "Copying file"
        override val description = "Copies a file into another workspace directory."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_WRITE)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"from":{"type":"string"},"toDir":{"type":"string"}},"required":["from","toDir"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val from = arg(argumentsJson, "from")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: from")
            val toDir = arg(argumentsJson, "toDir")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: toDir")
            if (ws.copy(from, toDir)) ToolResult.Success("Copied $from → $toDir/")
            else ToolResult.Failure("Could not copy $from to $toDir")
        }
    }

    // ---- DeleteFile ---------------------------------------------------------------------------------
    class Delete(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.delete"
        override val title = "Deleting"
        override val description = "Deletes a file or directory from the workspace. Always asks for confirmation."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_DELETE)
        override val riskLevel = RiskLevel.DESTRUCTIVE
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            if (ws.delete(path)) ToolResult.Success("Deleted: $path")
            else ToolResult.Failure("Could not delete: $path")
        }
    }

    // ---- SearchFiles ---------------------------------------------------------------------------------
    class Search(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.searchfiles"
        override val title = "Searching files"
        override val description =
            "Searches workspace file names and contents. Optional extensions filter like [\"kt\",\"java\"]."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val timeoutMs = 30_000
        override val parametersSchemaJson =
            """{"type":"object","properties":{"query":{"type":"string"},"extensions":{"type":"array","items":{"type":"string"}}},"required":["query"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val query = arg(argumentsJson, "query")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: query")
            val extensions = stringList(argumentsJson, "extensions")

            val hits = ws.search(query, extensions)
            if (hits.isEmpty()) {
                ToolResult.Success("No matches for \"$query\".")
            } else {
                ToolResult.Success(
                    hits.joinToString("\n") {
                        (if (it.kind == WorkspaceContext.SearchHit.Kind.NAME) "name: " else "content: ") + it.relativePath
                    }
                )
            }
        }
    }

    // ---- GetFileMetadata ---------------------------------------------------------------------------------
    class Metadata(private val env: WorkspaceToolEnv) : Tool {
        override val id = "fs.metadata"
        override val title = "Inspecting file"
        override val description = "Returns size, type and modification info of a workspace path."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult = withWorkspace(env) { ws ->
            val path = arg(argumentsJson, "path")
                ?: return@withWorkspace ToolResult.Failure("Missing required argument: path")
            val meta = ws.metadata(path)
                ?: return@withWorkspace ToolResult.Failure("Path not found: $path")
            ToolResult.Success(
                "${meta.relativePath}: " +
                    (if (meta.isDirectory) "directory" else "file") +
                    (meta.sizeBytes?.let { ", ${it}B" } ?: "") +
                    ", modified ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(meta.lastModifiedEpochMs))}" +
                    (if (!meta.canWrite) ", read-only" else "")
            )
        }
    }
}
