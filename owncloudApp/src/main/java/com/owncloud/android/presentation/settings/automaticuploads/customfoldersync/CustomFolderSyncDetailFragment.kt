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

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.appcompat.widget.SwitchCompat
import com.owncloud.android.R
import com.owncloud.android.extensions.showAlertDialog
import com.owncloud.android.ui.activity.FolderPickerActivity
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class CustomFolderSyncDetailFragment : Fragment() {

    private val viewModel by activityViewModel<CustomFolderSyncViewModel>()

    private val selectSourcePathLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val contentUriForTree = result.data!!.data!!
            requireContext().contentResolver.takePersistableUriPermission(contentUriForTree, takeFlags)
            viewModel.handleSelectSourcePath(contentUriForTree)
        }

    private val selectUploadPathLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            viewModel.handleSelectUploadPath(result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_custom_folder_sync_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val switchEnabled = view.findViewById<SwitchCompat>(R.id.switch_enabled)
        val textSourcePath = view.findViewById<TextView>(R.id.text_source_path)
        val textUploadPath = view.findViewById<TextView>(R.id.text_upload_path)
        val checkWifiOnly = view.findViewById<CheckBox>(R.id.check_wifi_only)
        val checkChargingOnly = view.findViewById<CheckBox>(R.id.check_charging_only)
        val checkUseSubfolders = view.findViewById<CheckBox>(R.id.check_use_subfolders)
        val checkExcludeHidden = view.findViewById<CheckBox>(R.id.check_exclude_hidden)
        val checkUploadExisting = view.findViewById<CheckBox>(R.id.check_upload_existing)
        val btnSave = view.findViewById<View>(R.id.btn_save)
        val btnDelete = view.findViewById<View>(R.id.btn_delete)

        view.findViewById<View>(R.id.row_source_path).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    viewModel.editingConfig.value?.sourcePath?.takeIf { it.isNotEmpty() }?.let { path ->
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, path)
                    }
                }
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
            selectSourcePathLauncher.launch(intent)
        }

        view.findViewById<View>(R.id.row_upload_path).setOnClickListener {
            val config = viewModel.editingConfig.value
            if (config == null || config.accountName.isBlank()) {
                timber.log.Timber.w("Cannot open FolderPicker: config=$config accountName=${config?.accountName}")
                return@setOnClickListener
            }
            val intent = Intent(activity, FolderPickerActivity::class.java).apply {
                putExtra(FolderPickerActivity.EXTRA_PICKER_MODE, FolderPickerActivity.PickerMode.CAMERA_FOLDER)
                if (config.spaceId != null) {
                    putExtra(FolderPickerActivity.KEY_SPACE_ID, config.spaceId)
                }
                putExtra(FolderPickerActivity.KEY_ACCOUNT_NAME, config.accountName)
            }
            selectUploadPathLauncher.launch(intent)
        }

        switchEnabled.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleEnabled(isChecked) }
        checkWifiOnly.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleWifiOnly(isChecked) }
        checkChargingOnly.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleChargingOnly(isChecked) }
        checkUseSubfolders.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleUseSubfolders(isChecked) }
        checkExcludeHidden.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleExcludeHidden(isChecked) }
        checkUploadExisting.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleUploadExisting(isChecked) }

        btnSave.setOnClickListener {
            viewModel.saveCurrentConfig()
            parentFragmentManager.popBackStack()
        }

        btnDelete.setOnClickListener {
            showAlertDialog(
                title = getString(R.string.custom_folder_sync_delete_confirm_title),
                message = getString(R.string.custom_folder_sync_delete_confirm_message),
                positiveButtonText = getString(R.string.common_yes),
                positiveButtonListener = { _, _ ->
                    viewModel.editingConfig.value?.let { viewModel.deleteConfig(it) }
                    parentFragmentManager.popBackStack()
                },
                negativeButtonText = getString(R.string.common_no)
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.editingConfig.collect { config ->
                        config ?: return@collect
                        switchEnabled.isChecked = config.enabled
                        // Show readable source path if available, else parse from URI, else placeholder
                        val srcDisplay = viewModel.displaySourcePath.value
                            ?: config.sourcePath.takeIf { it.isNotEmpty() }
                            ?: getString(R.string.custom_folder_sync_source_path)
                        textSourcePath.text = srcDisplay
                        textUploadPath.text = config.uploadPath
                        checkWifiOnly.isChecked = config.wifiOnly
                        checkChargingOnly.isChecked = config.chargingOnly
                        checkUseSubfolders.isChecked = config.useSubfolders
                        checkExcludeHidden.isChecked = config.excludeHidden
                    }
                }
                launch {
                    viewModel.displaySourcePath.collect { path ->
                        if (path != null) {
                            textSourcePath.text = path
                        }
                    }
                }
            }
        }
    }
}
