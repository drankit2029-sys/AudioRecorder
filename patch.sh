#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Adding explicit RecyclerView dependency to app/build.gradle.kts..."
cat << 'KOTLIN_APP' > app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.audiorecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.audiorecorder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // MVVM & Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
KOTLIN_APP

echo "==> 2. Patching RecordingAdapter.kt..."
cat << 'ADAPTER' > "$BASE/ui/library/RecordingAdapter.kt"
package com.example.audiorecorder.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.databinding.ItemRecordingCardBinding
import com.example.audiorecorder.util.TimecodeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingAdapter(
    private val onPlayClick: (RecordingEntity) -> Unit,
    private val onExportClick: (RecordingEntity) -> Unit,
    private val onEditClick: (RecordingEntity) -> Unit,
    private val onRenameClick: (RecordingEntity) -> Unit,
    private val onDeleteClick: (RecordingEntity) -> Unit,
    private val onCardLongClick: (RecordingEntity) -> Unit,
    private val onCardSelectToggle: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingAdapter.ViewHolder>(DiffCallback) {

    private var expandedCardId: Long? = null
    private var isMultiSelectMode = false
    private var selectedIds: Set<Long> = emptySet()

    fun setMultiSelectState(active: Boolean, selected: Set<Long>) {
        isMultiSelectMode = active
        selectedIds = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecordingCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingEntity) {
            binding.tvTitle.text = item.title
            val rateKhz = item.sampleRate / 1000
            val channelText = if (item.channelCount == 2) "Stereo" else "Mono"
            binding.tvSpecs.text = "${rateKhz}kHz • ${item.bitDepth}b • $channelText • ${item.format}"

            val durationText = TimecodeFormatter.formatMillis(item.durationMs)
            val sizeMb = String.format(Locale.US, "%.1f MB", item.fileSize / (1024.0 * 1024.0))
            val dateText = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(item.createdAt))
            binding.tvDetails.text = "$durationText • $sizeMb • $dateText"

            // Multi-select CheckBox handling
            if (isMultiSelectMode) {
                binding.cbSelect.visibility = View.VISIBLE
                binding.cbSelect.isChecked = selectedIds.contains(item.id)
                binding.btnPlayPause.visibility = View.GONE
            } else {
                binding.cbSelect.visibility = View.GONE
                binding.btnPlayPause.visibility = View.VISIBLE
            }

            // Expanded Drawer State
            val isExpanded = expandedCardId == item.id
            binding.layoutDrawer.visibility = if (isExpanded && !isMultiSelectMode) View.VISIBLE else View.GONE

            // Clicks
            binding.root.setOnClickListener {
                if (isMultiSelectMode) {
                    onCardSelectToggle(item)
                } else {
                    expandedCardId = if (isExpanded) null else item.id
                    notifyDataSetChanged()
                }
            }

            binding.root.setOnLongClickListener {
                onCardLongClick(item)
                true
            }

            binding.cbSelect.setOnClickListener { onCardSelectToggle(item) }
            binding.btnPlayPause.setOnClickListener { onPlayClick(item) }
            binding.btnExport.setOnClickListener { onExportClick(item) }
            binding.btnEdit.setOnClickListener { onEditClick(item) }
            binding.btnRename.setOnClickListener { onRenameClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RecordingEntity>() {
        override fun areItemsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) = oldItem == newItem
    }
}
ADAPTER

echo "==> 3. Deploying and rebuilding..."
./deploy.sh "Add explicit RecyclerView 1.3.2 and resolve adapter position in RecordingAdapter"
