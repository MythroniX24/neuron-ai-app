package com.neuron.ai.data.workspace

import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.workspace.Workspace
import com.neuron.ai.core.workspace.WorkspaceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-backed workspace manager (JSON index in app-private storage).
 * All workspace roots live under filesDir/workspaces/<id> — a hard boundary
 * that removes whole-device filesystem exposure by construction.
 */
class WorkspaceManagerImpl(
    private val filesDir: File,
    private val dispatchers: DispatcherProvider
) : WorkspaceManager {

    private val io = dispatchers.io
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(filesDir, "workspaces.json")
    private val mutex = Mutex()

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    override val workspaces: Flow<List<Workspace>> = _workspaces.asStateFlow()

    /** Root of all workspace roots; single point of boundary truth. */
    val workspaceRoot: File get() = File(filesDir, "workspaces")

    val recentOrder = MutableStateFlow<List<String>>(emptyList())

    init {
        runCatching { load() }
    }

    override suspend fun create(name: String): Workspace = withContext(io) {
        mutex.withLock {
            val id = "ws-" + UUID.randomUUID().toString().take(8)
            val root = File(workspaceRoot, id).apply { mkdirs() }
            val now = System.currentTimeMillis()
            val workspace = Workspace(
                id = id,
                name = name.trim().ifBlank { "Workspace" },
                rootPath = root.canonicalPath,
                createdAtEpochMs = now,
                lastOpenedAtEpochMs = now
            )
            _workspaces.value = _workspaces.value + workspace
            touchRecent(id)
            persist()
            workspace
        }
    }

    override suspend fun open(workspaceId: String): Workspace? = withContext(io) {
        mutex.withLock {
            val current = _workspaces.value.find { it.id == workspaceId } ?: return@withLock null
            val updated = current.copy(lastOpenedAtEpochMs = System.currentTimeMillis())
            _workspaces.value = _workspaces.value.map { if (it.id == workspaceId) updated else it }
            touchRecent(workspaceId)
            persist()
            updated
        }
    }

    override suspend fun rename(workspaceId: String, name: String) = withContext(io) {
        mutex.withLock {
            _workspaces.value = _workspaces.value.map {
                if (it.id == workspaceId) it.copy(name = name.trim().ifBlank { it.name }) else it
            }
            persist()
            Unit
        }
    }

    override suspend fun delete(workspaceId: String) = withContext(io) {
        mutex.withLock {
            val target = _workspaces.value.find { it.id == workspaceId }
            if (target != null) {
                // Defense in depth: only delete inside our workspace root.
                val root = workspaceRoot.canonicalFile
                if (File(target.rootPath).canonicalFile.path.startsWith(root.path)) {
                    File(target.rootPath).deleteRecursively()
                }
                _workspaces.value = _workspaces.value.filterNot { it.id == workspaceId }
                recentOrder.value = recentOrder.value - workspaceId
                persist()
            }
            Unit
        }
    }

    override suspend fun get(workspaceId: String): Workspace? =
        _workspaces.value.find { it.id == workspaceId }

    override suspend fun mostRecent(): Workspace? =
        recentOrder.value.firstOrNull()?.let { id -> _workspaces.value.find { it.id == id } }

    private fun touchRecent(id: String) {
        recentOrder.value = listOf(id) + recentOrder.value.filterNot { it == id }
    }

    private fun load() {
        if (indexFile.exists()) {
            val raw = indexFile.readText()
            if (raw.isNotBlank()) {
                val data = json.decodeFromString<WorkspacesData>(raw)
                _workspaces.value = data.workspaces
                recentOrder.value = data.recentOrder
            }
        }
    }

    private fun persist() {
        runCatching {
            indexFile.writeText(
                json.encodeToString(WorkspacesData(_workspaces.value, recentOrder.value))
            )
        }
    }

    @kotlinx.serialization.Serializable
    private data class WorkspacesData(
        val workspaces: List<Workspace>,
        val recentOrder: List<String>
    )
}

/** Sorted: pinned recents first, then by last-opened. */
fun List<Workspace>.sortedForDisplay(): List<Workspace> =
    sortedByDescending { it.lastOpenedAtEpochMs }
