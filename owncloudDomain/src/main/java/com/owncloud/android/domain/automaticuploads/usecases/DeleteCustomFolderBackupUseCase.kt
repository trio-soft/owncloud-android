package com.owncloud.android.domain.automaticuploads.usecases

import com.owncloud.android.domain.BaseUseCase
import com.owncloud.android.domain.automaticuploads.FolderBackupRepository

class DeleteCustomFolderBackupUseCase(
    private val folderBackupRepository: FolderBackupRepository
) : BaseUseCase<Unit, DeleteCustomFolderBackupUseCase.Params>() {

    override fun run(params: Params) =
        folderBackupRepository.resetFolderBackupConfigurationByName(params.name)

    data class Params(
        val name: String
    )
}
