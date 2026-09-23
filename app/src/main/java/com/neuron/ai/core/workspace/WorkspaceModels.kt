package com.neuron.ai.core.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A development workspace: an isolated root directory the agent may operate
 * on. Workspaces live under the app's private storage — the device
 * filesystem is never exposed wholesale, which makes boundary validation
 * airtight without dangerous broad-storage permissions.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    /** Absolute path of the root directory (inside app-private storage). */
    val rootPath: String,
    val createdAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long
)

/**
 * Owns workspace lifecycle: create/open/rename/delete and lookup.
 * Root directories are validated at creation; every file operation must
 * resolve through the workspace root (path-traversal safe).
 */
interface WorkspaceManager {
    val workspaces: Flow<List<Workspace>>

    suspend fun create(name: String): Workspace

    /** Marks recently-used order and returns the workspace, or null. */
    suspend fun open(workspaceId: String): Workspace?

    suspend fun rename(workspaceId: String, name: String)

    /** Deletes metadata AND the workspace directory tree. */
    suspend fun delete(workspaceId: String)

    suspend fun get(workspaceId: String): Workspace?

    /** Most recently opened workspace, or null. */
    suspend fun mostRecent(): Workspace?
}
