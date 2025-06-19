package com.blank.anime.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Adapter for anime entries in the downloads screen
 */
class AnimeDownloadAdapter(
    private val onItemClick: (String) -> Unit
) : ListAdapter<AnimeDownloadItem, AnimeDownloadAdapter.ViewHolder>(AnimeDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.download_title)
        val details: TextView = view.findViewById(R.id.download_details)
        val status: TextView = view.findViewById(R.id.download_status)
        val progressBar: ProgressBar = view.findViewById(R.id.watch_progress_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // Set title
        holder.title.text = item.title

        // Set details (episode count and last modified date)
        val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        val lastModified = dateFormat.format(Date(item.lastModified))
        holder.details.text = "${item.episodeCount} episodes • Last updated: $lastModified"

        // Set status text
        val statusText = buildStatusText(item)
        holder.status.text = statusText

        // Set progress if there are any downloading episodes
        if (item.runningCount > 0) {
            holder.progressBar.visibility = View.VISIBLE
            // Calculate an approximate progress
            val totalProgress = (item.completedCount.toFloat() / item.episodeCount.toFloat()) * 100
            holder.progressBar.progress = totalProgress.toInt()
        } else {
            holder.progressBar.visibility = View.GONE
        }

        // Set click listener
        holder.itemView.setOnClickListener {
            onItemClick(item.title)
        }
    }

    private fun buildStatusText(item: AnimeDownloadItem): String {
        val statusParts = mutableListOf<String>()

        if (item.completedCount > 0) {
            statusParts.add("${item.completedCount} completed")
        }

        if (item.runningCount > 0) {
            statusParts.add("${item.runningCount} downloading")
        }

        if (item.failedCount > 0) {
            statusParts.add("${item.failedCount} failed")
        }

        if (item.pausedCount > 0) {
            statusParts.add("${item.pausedCount} paused")
        }

        return statusParts.joinToString(" • ")
    }

    class AnimeDiffCallback : DiffUtil.ItemCallback<AnimeDownloadItem>() {
        override fun areItemsTheSame(oldItem: AnimeDownloadItem, newItem: AnimeDownloadItem): Boolean {
            return oldItem.title == newItem.title
        }

        override fun areContentsTheSame(oldItem: AnimeDownloadItem, newItem: AnimeDownloadItem): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * Data class to represent an anime in the download list
 */
data class AnimeDownloadItem(
    val title: String,
    val episodeCount: Int,
    val completedCount: Int,
    val runningCount: Int,
    val failedCount: Int,
    val pausedCount: Int,
    val lastModified: Long
)
