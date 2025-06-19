package com.blank.anime.ui

import android.app.DownloadManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for episode entries in the downloads screen
 */
class EpisodeDownloadAdapter(
    private val onPlayClick: (String, String, Int) -> Unit,
    private val onDeleteClick: (Long, String) -> Unit
) : ListAdapter<DownloadItem, EpisodeDownloadAdapter.ViewHolder>(EpisodeDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.download_title)
        val details: TextView = view.findViewById(R.id.download_details)
        val status: TextView = view.findViewById(R.id.download_status)
        val progressBar: ProgressBar = view.findViewById(R.id.watch_progress_bar)
        val progressText: TextView = view.findViewById(R.id.watch_progress_text)
        val playButton: Button = view.findViewById(R.id.play_button)
        val deleteButton: Button = view.findViewById(R.id.delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // Set title
        holder.title.text = "Episode ${item.episodeNumber}"

        // Set details
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val lastModified = dateFormat.format(Date(item.lastModified))

        // Format file size
        val fileSizeText = when {
            item.totalBytes > 1024 * 1024 * 1024 -> String.format("%.2f GB", item.totalBytes.toFloat() / (1024 * 1024 * 1024))
            item.totalBytes > 1024 * 1024 -> String.format("%.2f MB", item.totalBytes.toFloat() / (1024 * 1024))
            item.totalBytes > 1024 -> String.format("%.2f KB", item.totalBytes.toFloat() / 1024)
            else -> "${item.totalBytes} bytes"
        }

        holder.details.text = "$fileSizeText • $lastModified"

        // Set status text and progress
        when (item.status) {
            DownloadManager.STATUS_RUNNING -> {
                val progress = if (item.totalBytes > 0) {
                    (item.downloadedBytes * 100 / item.totalBytes).toInt()
                } else {
                    0
                }

                holder.status.text = "DOWNLOADING"
                holder.progressBar.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
                holder.progressText.text = "Downloading... $progress%"
                holder.progressBar.progress = progress
                holder.playButton.visibility = View.GONE
            }

            DownloadManager.STATUS_SUCCESSFUL -> {
                holder.status.text = "COMPLETED"
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.playButton.visibility = View.VISIBLE
            }

            DownloadManager.STATUS_FAILED -> {
                holder.status.text = "FAILED"
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.playButton.visibility = View.GONE
            }

            DownloadManager.STATUS_PAUSED -> {
                holder.status.text = "PAUSED"
                holder.progressBar.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
                val progress = if (item.totalBytes > 0) {
                    (item.downloadedBytes * 100 / item.totalBytes).toInt()
                } else {
                    0
                }
                holder.progressBar.progress = progress
                holder.progressText.text = "Paused at $progress%"
                holder.playButton.visibility = View.GONE
            }

            DownloadManager.STATUS_PENDING -> {
                holder.status.text = "PENDING"
                holder.progressBar.visibility = View.VISIBLE
                holder.progressText.visibility = View.VISIBLE
                holder.progressText.text = "Waiting to download..."
                holder.progressBar.isIndeterminate = true
                holder.playButton.visibility = View.GONE
            }

            else -> {
                holder.status.text = "UNKNOWN"
                holder.progressBar.visibility = View.GONE
                holder.progressText.visibility = View.GONE
                holder.playButton.visibility = View.GONE
            }
        }

        // Set play button click listener
        holder.playButton.setOnClickListener {
            onPlayClick(item.localUri, item.animeTitle, item.episodeNumber)
        }

        // Set delete button click listener
        holder.deleteButton.setOnClickListener {
            onDeleteClick(item.id, item.localUri)
        }
    }

    class EpisodeDiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Data class to represent a downloaded episode
 */
data class DownloadItem(
    val id: Long,
    val animeTitle: String,
    val episodeNumber: Int,
    val title: String,
    val description: String,
    val status: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val localUri: String,
    val lastModified: Long
)
