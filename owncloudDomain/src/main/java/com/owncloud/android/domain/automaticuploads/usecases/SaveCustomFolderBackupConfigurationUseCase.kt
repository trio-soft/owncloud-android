package com.owncloud.android.domain.automaticuploads.usecases

import com.owncloud.android.domain.BaseUseCaseWithResult
import com.owncloud.android.domain.automaticuploads.FolderBackupRepository
import com.owncloud.android.domain.automaticuploads.model.FolderBackUpConfiguration

class SaveCustomFolderBackupConfigurationUseCase(
    private val folderBackupRepository: FolderBackupRepository
) : BaseUseCaseWithResult<Unit, SaveCustomFolderBackupConfigurationUseCase.Params>() {

    override fun run(params: Params) =
        folderBackupRepository.saveFolderBackupConfiguration(params.folderBackUpConfiguration)

    data class Params(
        val folderBackUpConfiguration: FolderBackUpConfiguration
    )
}
