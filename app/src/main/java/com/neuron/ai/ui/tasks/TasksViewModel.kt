package com.neuron.ai.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.coroutines.DispatcherProvider
import com.neuron.ai.core.task.Task
import com.neuron.ai.data.task.DefaultTaskManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(
    private val taskManager: DefaultTaskManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    val tasks: StateFlow<List<Task>> = taskManager.tasks.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun stop(taskId: String) {
        viewModelScope.launch(dispatchers.io) { taskManager.cancel(taskId) }
    }

    /** Re-queues and re-runs a FAILED task. */
    fun retry(taskId: String) {
        viewModelScope.launch(dispatchers.io) { taskManager.retry(taskId) { } }
    }

    fun clearFinished() {
        viewModelScope.launch(dispatchers.io) { taskManager.clearFinished() }
    }
}
