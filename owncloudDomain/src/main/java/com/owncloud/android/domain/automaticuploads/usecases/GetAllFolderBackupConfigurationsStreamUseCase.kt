package com.owncloud.android.domain.automaticuploads.usecases

import com.owncloud.android.domain.BaseUseCase
import com.owncloud.android.domain.automaticuploads.FolderBackupRepository
import com.owncloud.android.domain.automaticuploads.model.FolderBackUpConfiguration
import kotlinx.coroutines.flow.Flow

class GetAllFolderBackupConfigurationsStreamUseCase(
    private val folderBackupRepository: FolderBackupRepository
) : BaseUseCase<Flow<List<FolderBackUpConfiguration>>, Unit>() {

    override fun run(params: Unit): Flow<List<FolderBackUpConfiguration>> =
        folderBackupRepository.getAllFolderBackupConfigurationsAsFlow()
}
