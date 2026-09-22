package com.neuron.ai.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.neuron.ai.data.task.DefaultTaskManager
import com.neuron.ai.di.AppContainer

class TasksViewModelFactory(private val container: AppContainer) :
    ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TasksViewModel::class.java)) {
            "Unknown ViewModel: $modelClass"
        }
        return TasksViewModel(
            taskManager = container.taskManager as DefaultTaskManager,
            dispatchers = container.dispatchers
        ) as T
    }
}
