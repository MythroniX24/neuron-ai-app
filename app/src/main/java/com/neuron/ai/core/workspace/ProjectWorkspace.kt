package com.neuron.ai.core.workspace

import kotlinx.coroutines.flow.Flow

/**
 * A user project: a root folder plus conversation context.
 * The filesystem of the device is never exposed wholesale — only workspaces.
 */
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val createdAtEpochMs: Long
)

/**
 * Read/write access confined to a [Project] root.
 * Path traversal outside the root must be rejected by implementations.
 */
interface Workspace {
    val project: Project

    suspend fun list(path: String): List<Entry>
    suspend fun readText(path: String): String
    suspend fun writeText(path: String, content: String)
    suspend fun delete(path: String)

    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long? = null
    )
}

/** Creates, lists and removes projects. */
interface ProjectRepository {
    val projects: Flow<List<Project>>
    suspend fun create(name: String, rootPath: String): Project
    suspend fun remove(projectId: String)
}
