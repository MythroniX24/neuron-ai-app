package com.neuron.ai.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.neuron.ai.core.update.AppUpdateCheckState
import com.neuron.ai.core.update.AppUpdateInfo
import com.neuron.ai.core.update.DownloadState
import com.neuron.ai.core.update.InstallResult
import com.neuron.ai.data.update.AppUpdateManager
import com.neuron.ai.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class AppUpdateViewModel(
    private val updateManager: AppUpdateManager
) : ViewModel() {

    val checkState: StateFlow<AppUpdateCheckState> = updateManager.checkState
    val downloadState: StateFlow<DownloadState> = updateManager.downloadState

    private val _installResult = MutableStateFlow<InstallResult?>(null)
    val installResult: StateFlow<InstallResult?> = _installResult.asStateFlow()

    private val _cachedApkFile = MutableStateFlow<File?>(null)
    val cachedApkFile: StateFlow<File?> = _cachedApkFile.asStateFlow()

    init {
        // Initial check if idle
        if (checkState.value is AppUpdateCheckState.Idle) {
            checkForUpdate(force = false)
        }
    }

    fun checkForUpdate(force: Boolean = true) {
        viewModelScope.launch {
            _installResult.value = null
            val result = updateManager.checkForUpdate(notifyIfAvailable = false)
            result.getOrNull()?.let { info ->
                checkCachedApk(info)
            }
        }
    }

    fun checkCachedApk(info: AppUpdateInfo) {
        val existing = updateManager.getDownloadedApk(info.releaseTag, info.apkSizeBytes)
        _cachedApkFile.value = existing
    }

    fun startDownload(info: AppUpdateInfo) {
        viewModelScope.launch {
            _installResult.value = null
            val result = updateManager.downloadApk(info, viewModelScope)
            result.onSuccess { apkFile ->
                _cachedApkFile.value = apkFile
            }.onFailure {
                _cachedApkFile.value = null
            }
        }
    }

    fun cancelDownload() {
        updateManager.cancelDownload()
    }

    fun installApk(file: File) {
        val result = updateManager.installApk(file)
        _installResult.value = result
    }

    fun requestUnknownSourcesPermission() {
        updateManager.requestUnknownSourcesPermission()
    }

    fun dismissInstallResult() {
        _installResult.value = null
    }
}

class AppUpdateViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppUpdateViewModel(container.appUpdateManager) as T
    }
}
