/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 * @author Juan Carlos Garrote Gascón
 * @author Jorge Aguado Recio
 *
 * Copyright (C) 2025 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.workers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.owncloud.android.R
import com.owncloud.android.domain.UseCaseResult
import com.owncloud.android.domain.automaticuploads.model.FolderBackUpConfiguration
import com.owncloud.android.domain.automaticuploads.model.UploadBehavior
import com.owncloud.android.domain.automaticuploads.usecases.GetAutomaticUploadsConfigurationUseCase
import com.owncloud.android.domain.automaticuploads.usecases.SaveCustomFolderBackupConfigurationUseCase
import com.owncloud.android.domain.automaticuploads.usecases.SavePictureUploadsConfigurationUseCase
import com.owncloud.android.domain.automaticuploads.usecases.SaveVideoUploadsConfigurationUseCase
import com.owncloud.android.domain.automaticuploads.usecases.GetAllFolderBackupConfigurationsStreamUseCase
import com.owncloud.android.domain.automaticuploads.FolderBackupRepository
import com.owncloud.android.domain.transfers.TransferRepository
import com.owncloud.android.domain.transfers.model.OCTransfer
import com.owncloud.android.domain.transfers.model.TransferStatus
import com.owncloud.android.presentation.settings.SettingsActivity
import com.owncloud.android.domain.transfers.model.UploadEnqueuedBy
import com.owncloud.android.usecases.transfers.uploads.UploadFileFromContentUriUseCase
import com.owncloud.android.utils.MimetypeIconUtil
import com.owncloud.android.utils.NotificationUtils
import com.owncloud.android.utils.UPLOAD_NOTIFICATION_CHANNEL_ID
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutomaticUploadsWorker(
    val appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParameters
), KoinComponent {

    enum class SyncType(val prefixForType: String) {
        PICTURE_UPLOADS("image/"), VIDEO_UPLOADS("video/"), CUSTOM_FOLDER("");

        fun getNotificationId(): Int =
            when (this) {
                PICTURE_UPLOADS -> pictureUploadsNotificationId
                VIDEO_UPLOADS -> videoUploadsNotificationId
                CUSTOM_FOLDER -> customFolderNotificationId
            }
    }

    private val getAutomaticUploadsConfigurationUseCase: GetAutomaticUploadsConfigurationUseCase by inject()
    private val folderBackupRepository: FolderBackupRepository by inject()
    private val transferRepository: TransferRepository by inject()

    override suspend fun doWork(): Result {
        Timber.i("Starting AutomaticUploadsWorker with UUID ${this.id}")

        // Handle legacy picture/video uploads
        when (val useCaseResult = getAutomaticUploadsConfigurationUseCase(Unit)) {
            is UseCaseResult.Success -> {
                val cameraUploadsConfiguration = useCaseResult.data
                if (cameraUploadsConfiguration != null && !cameraUploadsConfiguration.areAutomaticUploadsDisabled()) {
                    cameraUploadsConfiguration.pictureUploadsConfiguration?.let { pictureUploadsConfiguration ->
                        try {
                            checkSourcePathIsAValidUriOrThrowException(pictureUploadsConfiguration.sourcePath)
                            syncFolder(pictureUploadsConfiguration)
                        } catch (illegalArgumentException: IllegalArgumentException) {
                            Timber.e(illegalArgumentException, "Source path for picture uploads is not valid")
                            showNotificationToUpdateUri(SyncType.PICTURE_UPLOADS)
                        }
                    }
                    cameraUploadsConfiguration.videoUploadsConfiguration?.let { videoUploadsConfiguration ->
                        try {
                            checkSourcePathIsAValidUriOrThrowException(videoUploadsConfiguration.sourcePath)
                            syncFolder(videoUploadsConfiguration)
                        } catch (illegalArgumentException: IllegalArgumentException) {
                            Timber.e(illegalArgumentException, "Source path for video uploads is not valid")
                            showNotificationToUpdateUri(SyncType.VIDEO_UPLOADS)
                        }
                    }
                }
            }
            is UseCaseResult.Error -> {
                Timber.e(useCaseResult.throwable, "Worker ${useCaseResult.throwable}")
            }
        }

        // Handle custom folder sync configurations
        try {
            val allConfigs = folderBackupRepository.getAllFolderBackupConfigurations()
            val customConfigs = allConfigs.filter { it.isCustomFolderSync && it.enabled }
            Timber.i("Found ${customConfigs.size} enabled custom folder sync configurations")

            for (config in customConfigs) {
                try {
                    checkSourcePathIsAValidUriOrThrowException(config.sourcePath)
                    syncCustomFolder(config)
                } catch (e: IllegalArgumentException) {
                    Timber.e(e, "Source path for custom folder '${config.name}' is not valid")
                    showCustomFolderErrorNotification(config.name)
                } catch (e: Exception) {
                    Timber.e(e, "Error syncing custom folder '${config.name}'")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error fetching custom folder configurations")
        }

        // Cancel worker only if ALL uploads are disabled (legacy + custom)
        val allConfigs = try { folderBackupRepository.getAllFolderBackupConfigurations() } catch (_: Exception) { emptyList() }
        val hasEnabledCustom = allConfigs.any { it.isCustomFolderSync && it.enabled }
        val legacyConfig = try { (getAutomaticUploadsConfigurationUseCase(Unit) as? UseCaseResult.Success)?.data } catch (_: Exception) { null }
        val hasEnabledLegacy = legacyConfig != null && !legacyConfig.areAutomaticUploadsDisabled()

        if (!hasEnabledCustom && !hasEnabledLegacy) {
            cancelWorker()
        }

        Timber.i("Finishing AutomaticUploadsWorker with UUID ${this.id}")
        return Result.success()
    }

    @Throws(IllegalArgumentException::class)
    private fun checkSourcePathIsAValidUriOrThrowException(sourcePath: String) {
        val sourceUri: Uri = sourcePath.toUri()
        DocumentFile.fromTreeUri(applicationContext, sourceUri)
    }

    private fun cancelWorker() {
        WorkManager.getInstance(appContext).cancelUniqueWork(AUTOMATIC_UPLOADS_WORKER)
    }

    private fun syncCustomFolder(config: FolderBackUpConfiguration) {
        val currentTimestamp = System.currentTimeMillis()

        // Update timestamp via save
        val saveCustomFolderBackupConfigurationUseCase: SaveCustomFolderBackupConfigurationUseCase by inject()
        saveCustomFolderBackupConfigurationUseCase(
            SaveCustomFolderBackupConfigurationUseCase.Params(config.copy(lastSyncTimestamp = currentTimestamp))
        )

        val filesToUpload = getCustomFolderFilesReadyToUpload(
            config = config,
            lastSyncTimestamp = config.lastSyncTimestamp,
            currentTimestamp = currentTimestamp,
        )

        if (filesToUpload.isNotEmpty()) {
            showCustomFolderNotification(config.name, filesToUpload.size)
        }

        val dateFormat = SimpleDateFormat("yyyy/MM", Locale.getDefault())

        for (documentFile in filesToUpload) {
            val remotePath = if (config.useSubfolders) {
                val dateSubfolder = dateFormat.format(Date(documentFile.lastModified()))
                config.uploadPath + File.separator + dateSubfolder + File.separator + documentFile.name
            } else {
                config.uploadPath + File.separator + documentFile.name
            }

            val uploadId = storeInUploadsDatabase(
                documentFile = documentFile,
                uploadPath = remotePath,
                accountName = config.accountName,
                behavior = config.behavior,
                createdByWorker = UploadEnqueuedBy.ENQUEUED_AS_AUTOMATIC_UPLOAD_PICTURE,
                spaceId = config.spaceId
            )
            enqueueSingleUpload(
                contentUri = documentFile.uri,
                uploadPath = remotePath,
                lastModified = documentFile.lastModified(),
                behavior = config.behavior.toString(),
                accountName = config.accountName,
                uploadId = uploadId,
                wifiOnly = config.wifiOnly,
                chargingOnly = config.chargingOnly
            )
        }
    }

    private fun getCustomFolderFilesReadyToUpload(
        config: FolderBackUpConfiguration,
        lastSyncTimestamp: Long,
        currentTimestamp: Long,
    ): List<DocumentFile> {
        val sourceUri: Uri = config.sourcePath.toUri()
        val documentTree = DocumentFile.fromTreeUri(applicationContext, sourceUri)
        val arrayOfLocalFiles = documentTree?.listFiles() ?: arrayOf()

        val filteredList: List<DocumentFile> = arrayOfLocalFiles
            .asSequence()
            .filter { file ->
                file.lastModified() in lastSyncTimestamp..<currentTimestamp &&
                        file.isFile &&
                        (!config.excludeHidden || !file.name.orEmpty().startsWith("."))
            }
            .sortedBy { it.lastModified() }
            .toList()

        Timber.i("Custom folder '${config.name}': Last sync ${Date(lastSyncTimestamp)}")
        Timber.i("Custom folder '${config.name}': ${arrayOfLocalFiles.size} files found, ${filteredList.size} ready to upload")

        return filteredList
    }

    private fun syncFolder(folderBackUpConfiguration: FolderBackUpConfiguration) {
        val syncType = when {
            folderBackUpConfiguration.isPictureUploads -> SyncType.PICTURE_UPLOADS
            folderBackUpConfiguration.isVideoUploads -> SyncType.VIDEO_UPLOADS
            else -> SyncType.PICTURE_UPLOADS
        }

        val currentTimestamp = System.currentTimeMillis()
        updateTimestamp(folderBackUpConfiguration, syncType, currentTimestamp)

        val localPicturesDocumentFiles: List<DocumentFile> = getFilesReadyToUpload(
            syncType = syncType,
            sourcePath = folderBackUpConfiguration.sourcePath,
            lastSyncTimestamp = folderBackUpConfiguration.lastSyncTimestamp,
            currentTimestamp = currentTimestamp,
        )

        showNotification(syncType, localPicturesDocumentFiles.size)

        for (documentFile in localPicturesDocumentFiles) {
            val uploadId = storeInUploadsDatabase(
                documentFile = documentFile,
                uploadPath = folderBackUpConfiguration.uploadPath.plus(File.separator).plus(documentFile.name),
                accountName = folderBackUpConfiguration.accountName,
                behavior = folderBackUpConfiguration.behavior,
                createdByWorker = when (syncType) {
                    SyncType.PICTURE_UPLOADS -> UploadEnqueuedBy.ENQUEUED_AS_AUTOMATIC_UPLOAD_PICTURE
                    SyncType.VIDEO_UPLOADS -> UploadEnqueuedBy.ENQUEUED_AS_AUTOMATIC_UPLOAD_VIDEO
                    SyncType.CUSTOM_FOLDER -> UploadEnqueuedBy.ENQUEUED_AS_AUTOMATIC_UPLOAD_PICTURE
                },
                spaceId = folderBackUpConfiguration.spaceId
            )
            enqueueSingleUpload(
                contentUri = documentFile.uri,
                uploadPath = folderBackUpConfiguration.uploadPath.plus(File.separator).plus(documentFile.name),
                lastModified = documentFile.lastModified(),
                behavior = folderBackUpConfiguration.behavior.toString(),
                accountName = folderBackUpConfiguration.accountName,
                uploadId = uploadId,
                wifiOnly = folderBackUpConfiguration.wifiOnly,
                chargingOnly = folderBackUpConfiguration.chargingOnly
            )
        }
    }

    private fun showNotification(
        syncType: SyncType,
        numberOfFilesToUpload: Int
    ) {
        if (numberOfFilesToUpload == 0) return

        val contentText = when (syncType) {
            SyncType.PICTURE_UPLOADS -> R.string.uploader_upload_picture_upload_files
            SyncType.VIDEO_UPLOADS -> R.string.uploader_upload_video_upload_files
            SyncType.CUSTOM_FOLDER -> R.string.uploader_upload_picture_upload_files
        }

        NotificationUtils.createBasicNotification(
            context = appContext,
            contentTitle = appContext.getString(R.string.uploader_upload_camera_upload_files),
            contentText = appContext.getString(contentText, numberOfFilesToUpload),
            notificationChannelId = UPLOAD_NOTIFICATION_CHANNEL_ID,
            notificationId = syncType.getNotificationId(),
            intent = NotificationUtils.composePendingIntentToUploadList(appContext),
            onGoing = false,
            timeOut = 5_000
        )
    }

    private fun showCustomFolderNotification(folderName: String, numberOfFiles: Int) {
        NotificationUtils.createBasicNotification(
            context = appContext,
            contentTitle = appContext.getString(R.string.custom_folder_sync_notification_title),
            contentText = appContext.getString(R.string.custom_folder_sync_notification_text, folderName, numberOfFiles),
            notificationChannelId = UPLOAD_NOTIFICATION_CHANNEL_ID,
            notificationId = customFolderNotificationId + folderName.hashCode() % 1000,
            intent = NotificationUtils.composePendingIntentToUploadList(appContext),
            onGoing = false,
            timeOut = 5_000
        )
    }

    private fun showCustomFolderErrorNotification(folderName: String) {
        NotificationUtils.createBasicNotification(
            context = appContext,
            contentTitle = appContext.getString(R.string.custom_folder_sync_error_title),
            contentText = appContext.getString(R.string.custom_folder_sync_error_text, folderName),
            notificationChannelId = UPLOAD_NOTIFICATION_CHANNEL_ID,
            notificationId = customFolderNotificationId + folderName.hashCode() % 1000,
            intent = NotificationUtils.composePendingIntentToUploadList(appContext),
            onGoing = false,
            timeOut = null
        )
    }

    private fun showNotificationToUpdateUri(
        syncType: SyncType
    ) {
        val contentText: Int = when (syncType) {
            SyncType.PICTURE_UPLOADS -> R.string.uploader_upload_picture_upload_error
            SyncType.VIDEO_UPLOADS -> R.string.uploader_upload_video_upload_error
            SyncType.CUSTOM_FOLDER -> R.string.uploader_upload_picture_upload_error
        }
        val notificationKey: String = when (syncType) {
            SyncType.PICTURE_UPLOADS -> SettingsActivity.NOTIFICATION_INTENT_PICTURES
            SyncType.VIDEO_UPLOADS -> SettingsActivity.NOTIFICATION_INTENT_VIDEOS
            SyncType.CUSTOM_FOLDER -> SettingsActivity.NOTIFICATION_INTENT_PICTURES
        }
        NotificationUtils.createBasicNotification(
            context = appContext,
            contentTitle = appContext.getString(R.string.uploader_upload_camera_upload_source_path_error),
            contentText = appContext.getString(contentText),
            notificationChannelId = UPLOAD_NOTIFICATION_CHANNEL_ID,
            notificationId = syncType.getNotificationId(),
            intent = NotificationUtils.composePendingIntentToAutomaticUploads(appContext, notificationKey),
            onGoing = false,
            timeOut = null
        )
    }

    private fun updateTimestamp(
        folderBackUpConfiguration: FolderBackUpConfiguration,
        syncType: SyncType,
        currentTimestamp: Long,
    ) {
        when (syncType) {
            SyncType.PICTURE_UPLOADS -> {
                val savePictureUploadsConfigurationUseCase: SavePictureUploadsConfigurationUseCase by inject()
                savePictureUploadsConfigurationUseCase(
                    SavePictureUploadsConfigurationUseCase.Params(folderBackUpConfiguration.copy(lastSyncTimestamp = currentTimestamp))
                )
            }
            SyncType.VIDEO_UPLOADS -> {
                val saveVideoUploadsConfigurationUseCase: SaveVideoUploadsConfigurationUseCase by inject()
                saveVideoUploadsConfigurationUseCase(
                    SaveVideoUploadsConfigurationUseCase.Params(folderBackUpConfiguration.copy(lastSyncTimestamp = currentTimestamp))
                )
            }
            SyncType.CUSTOM_FOLDER -> {
                val saveCustomFolderBackupConfigurationUseCase: SaveCustomFolderBackupConfigurationUseCase by inject()
                saveCustomFolderBackupConfigurationUseCase(
                    SaveCustomFolderBackupConfigurationUseCase.Params(folderBackUpConfiguration.copy(lastSyncTimestamp = currentTimestamp))
                )
            }
        }
    }

    private fun getFilesReadyToUpload(
        syncType: SyncType,
        sourcePath: String,
        lastSyncTimestamp: Long,
        currentTimestamp: Long,
    ): List<DocumentFile> {
        val sourceUri: Uri = sourcePath.toUri()
        val documentTree = DocumentFile.fromTreeUri(applicationContext, sourceUri)
        val arrayOfLocalFiles = documentTree?.listFiles() ?: arrayOf()

        val filteredList: List<DocumentFile> = arrayOfLocalFiles
            .asSequence()
            .filter {
                it.lastModified() in lastSyncTimestamp..<currentTimestamp &&
                        MimetypeIconUtil.getBestMimeTypeByFilename(it.name).startsWith(syncType.prefixForType) &&
                        !it.name.orEmpty().startsWith(".")
            }
            .sortedBy { it.lastModified() }
            .toList()

        Timber.i("Last sync ${syncType.name}: ${Date(lastSyncTimestamp)}")
        Timber.i("CurrentTimestamp ${Date(currentTimestamp)}")
        Timber.i("${arrayOfLocalFiles.size} files found in folder: ${sourceUri.path}")
        Timber.i("${filteredList.size} files are ${syncType.name} and were taken after last sync")

        return filteredList
    }

    private fun enqueueSingleUpload(
        contentUri: Uri,
        uploadPath: String,
        lastModified: Long,
        behavior: String,
        accountName: String,
        uploadId: Long,
        wifiOnly: Boolean,
        chargingOnly: Boolean
    ) {
        val lastModifiedInSeconds = (lastModified / 1000L).toString()

        UploadFileFromContentUriUseCase(WorkManager.getInstance(appContext))(
            UploadFileFromContentUriUseCase.Params(
                accountName = accountName,
                contentUri = contentUri,
                lastModifiedInSeconds = lastModifiedInSeconds,
                behavior = behavior,
                uploadPath = uploadPath,
                uploadIdInStorageManager = uploadId,
                wifiOnly = wifiOnly,
                chargingOnly = chargingOnly
            )
        )
    }

    private fun storeInUploadsDatabase(
        documentFile: DocumentFile,
        uploadPath: String,
        accountName: String,
        behavior: UploadBehavior,
        createdByWorker: UploadEnqueuedBy,
        spaceId: String?,
    ): Long {
        val ocTransfer = OCTransfer(
            localPath = documentFile.uri.toString(),
            remotePath = uploadPath,
            accountName = accountName,
            fileSize = documentFile.length(),
            status = TransferStatus.TRANSFER_QUEUED,
            localBehaviour = behavior,
            forceOverwrite = false,
            createdBy = createdByWorker,
            spaceId = spaceId,
        )

        return transferRepository.saveTransfer(ocTransfer)
    }

    companion object {
        const val AUTOMATIC_UPLOADS_WORKER = "AUTOMATIC_UPLOADS_WORKER"
        const val repeatInterval: Long = 15L
        val repeatIntervalTimeUnit: TimeUnit = TimeUnit.MINUTES
        private const val pictureUploadsNotificationId = 101
        private const val videoUploadsNotificationId = 102
        private const val customFolderNotificationId = 200
    }
}
