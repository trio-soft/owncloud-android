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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.widget.SwitchCompat
import com.owncloud.android.R
import com.owncloud.android.domain.automaticuploads.model.FolderBackUpConfiguration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class CustomFolderSyncListFragment : Fragment() {

    private val viewModel by activityViewModel<CustomFolderSyncViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_custom_folder_sync_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_custom_folders)
        val emptyView = view.findViewById<TextView>(R.id.text_empty)
        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fab_add_folder)

        val adapter = CustomFolderAdapter(
            onItemClick = { config ->
                viewModel.editConfig(config)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, CustomFolderSyncDetailFragment())
                    .addToBackStack(null)
                    .commit()
            },
            onToggleEnabled = { config, enabled ->
                viewModel.editConfig(config)
                viewModel.toggleEnabled(enabled)
                viewModel.saveCurrentConfig()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener {
            viewModel.createNewConfig()
            // Navigate once the config is ready (async DB call)
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.editingConfig.first { it != null }
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settings_container, CustomFolderSyncDetailFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.customFolderConfigs.collect { configs ->
                    adapter.submitList(configs)
                    emptyView.visibility = if (configs.isEmpty()) View.VISIBLE else View.GONE
                    recyclerView.visibility = if (configs.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }
}

class CustomFolderAdapter(
    private val onItemClick: (FolderBackUpConfiguration) -> Unit,
    private val onToggleEnabled: (FolderBackUpConfiguration, Boolean) -> Unit,
) : ListAdapter<FolderBackUpConfiguration, CustomFolderAdapter.ViewHolder>(DIFF_CALLBACK) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_folder_name)
        val sourcePath: TextView = view.findViewById(R.id.text_source_path)
        val uploadPath: TextView = view.findViewById(R.id.text_upload_path)
        val enabledSwitch: SwitchCompat = view.findViewById(R.id.switch_enabled)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_custom_folder_sync, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = getItem(position)
        holder.name.text = config.name
        // Parse content URI to human-readable path
        holder.sourcePath.text = try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(config.sourcePath))
            docId?.replace("primary:", "/storage/emulated/0/")?.replace(":", "/") ?: config.sourcePath
        } catch (_: Exception) {
            config.sourcePath
        }
        holder.uploadPath.text = config.uploadPath
        holder.enabledSwitch.isChecked = config.enabled
        holder.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggleEnabled(config, isChecked)
        }
        holder.itemView.setOnClickListener { onItemClick(config) }
    }

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FolderBackUpConfiguration>() {
            override fun areItemsTheSame(old: FolderBackUpConfiguration, new: FolderBackUpConfiguration) = old.name == new.name
            override fun areContentsTheSame(old: FolderBackUpConfiguration, new: FolderBackUpConfiguration) = old == new
        }
    }
}
