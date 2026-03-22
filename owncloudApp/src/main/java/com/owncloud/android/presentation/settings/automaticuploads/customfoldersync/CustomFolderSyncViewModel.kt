/**
 * ownCloud Android client application
 *
 * Copyright (C) 2026 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 */

package com.owncloud.android.presentation.settings.automaticuploads.customfoldersync

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owncloud.android.domain.automaticuploads.model.FolderBackUpConfiguration
import com.owncloud.android.domain.automaticuploads.model.UploadBehavior
import com.owncloud.android.domain.automaticuploads.usecases.DeleteCustomFolderBackupUseCase
import com.owncloud.android.domain.automaticuploads.usecases.GetAllFolderBackupConfigurationsStreamUseCase
import com.owncloud.android.domain.automaticuploads.usecases.SaveCustomFolderBackupConfigurationUseCase
import com.owncloud.android.domain.files.model.OCFile
import com.owncloud.android.domain.spaces.usecases.GetPersonalSpaceForAccountUseCase
import com.owncloud.android.providers.AccountProvider
import com.owncloud.android.providers.CoroutinesDispatcherProvider
import com.owncloud.android.providers.WorkManagerProvider
import com.owncloud.android.ui.activity.FolderPickerActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class CustomFolderSyncViewModel(
    private val accountProvider: AccountProvider,
    private val getAllFolderBackupConfigurationsStreamUseCase: GetAllFolderBackupConfigurationsStreamUseCase,
    private val saveCustomFolderBackupConfigurationUseCase: SaveCustomFolderBackupConfigurationUseCase,
    private val deleteCustomFolderBackupUseCase: DeleteCustomFolderBackupUseCase,
    private val getPersonalSpaceForAccountUseCase: GetPersonalSpaceForAccountUseCase,
    private val workManagerProvider: WorkManagerProvider,
    private val coroutinesDispatcherProvider: CoroutinesDispatcherProvider,
) : ViewModel() {

    val customFolderConfigs: StateFlow<List<FolderBackUpConfiguration>> =
        getAllFolderBackupConfigurationsStreamUseCase(Unit)
            .map { allConfigs -> allConfigs.filter { it.isCustomFolderSync } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently editing config
    private val _editingConfig = MutableStateFlow<FolderBackUpConfiguration?>(null)
    val editingConfig: StateFlow<FolderBackUpConfiguration?> = _editingConfig

    fun createNewConfig() {
        val accountName = accountProvider.getCurrentOwnCloudAccount()?.name ?: return
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            val spaceId = getPersonalSpaceForAccountUseCase(
                GetPersonalSpaceForAccountUseCase.Params(accountName = accountName)
            )?.id

            _editingConfig.value = FolderBackUpConfiguration(
                accountName = accountName,
                behavior = UploadBehavior.COPY,
                sourcePath = "",
                uploadPath = "/CustomSync",
                wifiOnly = true,
                chargingOnly = false,
                lastSyncTimestamp = System.currentTimeMillis(),
                name = "Custom-${UUID.randomUUID().toString().take(8)}",
                spaceId = spaceId,
                enabled = true,
                useSubfolders = false,
                excludeHidden = true,
            )
        }
    }

    fun editConfig(config: FolderBackUpConfiguration) {
        _editingConfig.value = config
    }

    fun updateEditingConfig(config: FolderBackUpConfiguration) {
        _editingConfig.value = config
    }

    fun saveCurrentConfig() {
        val config = _editingConfig.value ?: return
        val finalConfig = if (_uploadExisting.value) {
            config.copy(lastSyncTimestamp = 0L)
        } else {
            config
        }
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            saveCustomFolderBackupConfigurationUseCase(
                SaveCustomFolderBackupConfigurationUseCase.Params(finalConfig)
            )
            // Ensure periodic worker is scheduled
            workManagerProvider.enqueueAutomaticUploadsWorker()
            // Also trigger an immediate one-time run
            val oneTimeRequest = androidx.work.OneTimeWorkRequestBuilder<com.owncloud.android.workers.AutomaticUploadsWorker>()
                .addTag("custom_folder_sync_immediate")
                .build()
            androidx.work.WorkManager.getInstance(workManagerProvider.context)
                .enqueue(oneTimeRequest)
        }
        // Reset upload existing flag after save
        _uploadExisting.value = false
    }

    fun deleteConfig(config: FolderBackUpConfiguration) {
        viewModelScope.launch(coroutinesDispatcherProvider.io) {
            deleteCustomFolderBackupUseCase(
                DeleteCustomFolderBackupUseCase.Params(name = config.name)
            )
        }
    }

    fun handleSelectSourcePath(contentUriForTree: Uri) {
        // Store the content URI (needed for access), but also derive a readable path for display
        val docId = try { DocumentsContract.getTreeDocumentId(contentUriForTree) } catch (_: Exception) { null }
        val displayPath = docId?.replace("primary:", "/storage/emulated/0/")?.replace(":", "/") ?: contentUriForTree.toString()
        _editingConfig.value = _editingConfig.value?.copy(
            sourcePath = contentUriForTree.toString()
        )
        _displaySourcePath.value = displayPath
    }

    private val _displaySourcePath = MutableStateFlow<String?>(null)
    val displaySourcePath: StateFlow<String?> = _displaySourcePath

    private val _uploadExisting = MutableStateFlow(false)
    val uploadExisting: StateFlow<Boolean> = _uploadExisting

    fun toggleUploadExisting(upload: Boolean) {
        _uploadExisting.value = upload
    }

    fun updateName(name: String) {
        _editingConfig.value = _editingConfig.value?.copy(name = name)
    }

    fun handleSelectUploadPath(data: Intent?) {
        val folderToUpload = data?.getParcelableExtra<OCFile>(FolderPickerActivity.EXTRA_FOLDER)
        folderToUpload?.remotePath?.let { remotePath ->
            _editingConfig.value = _editingConfig.value?.copy(
                uploadPath = remotePath,
                spaceId = folderToUpload.spaceId,
            )
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        _editingConfig.value = _editingConfig.value?.copy(enabled = enabled)
    }

    fun toggleWifiOnly(wifiOnly: Boolean) {
        _editingConfig.value = _editingConfig.value?.copy(wifiOnly = wifiOnly)
    }

    fun toggleChargingOnly(chargingOnly: Boolean) {
        _editingConfig.value = _editingConfig.value?.copy(chargingOnly = chargingOnly)
    }

    fun toggleUseSubfolders(useSubfolders: Boolean) {
        _editingConfig.value = _editingConfig.value?.copy(useSubfolders = useSubfolders)
    }

    fun toggleExcludeHidden(excludeHidden: Boolean) {
        _editingConfig.value = _editingConfig.value?.copy(excludeHidden = excludeHidden)
    }

    fun scheduleSync() {
        workManagerProvider.enqueueAutomaticUploadsWorker()
    }
}
