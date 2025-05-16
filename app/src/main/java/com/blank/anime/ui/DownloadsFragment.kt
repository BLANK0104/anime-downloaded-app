package com.blank.anime.ui

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.R
import com.blank.anime.databinding.FragmentDownloadsBinding
import com.google.android.material.chip.Chip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import androidx.core.content.FileProvider

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadManager: DownloadManager
    private lateinit var animeAdapter: AnimeDownloadAdapter
    private lateinit var episodeAdapter: EpisodeDownloadAdapter

    // Track current view state
    private var currentViewMode = ViewMode.ANIME_LIST
    private var currentAnimeTitle = ""
    private var activeFilters = mutableSetOf(DownloadStatus.ALL)

    // Keep track of downloads grouped by anime
    private val allDownloads = mutableListOf<DownloadItem>()
    private val animeList = mutableListOf<AnimeDownloadItem>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        setupAdapters()
        setupFilterChips()
        setupBackButton()

        // Show anime list by default
        showAnimeList()

        // Load downloads when fragment becomes visible
        loadDownloads()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list when returning to this fragment
        loadDownloads()
    }

    private fun setupAdapters() {
        // Setup anime adapter
        animeAdapter = AnimeDownloadAdapter { animeTitle ->
            showEpisodeList(animeTitle)
        }

        // Setup episode adapter
        episodeAdapter = EpisodeDownloadAdapter(
            onPlayClick = { localUri ->
                playVideo(localUri)
            },
            onDeleteClick = { downloadId, localUri ->
                deleteDownload(downloadId, localUri)
            }
        )

        binding.downloadsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupBackButton() {
        binding.backButton.setOnClickListener {
            showAnimeList()
        }
    }

    private fun setupFilterChips() {
        // Set click listener for all filter chips
        val chips = listOf(
            binding.filterAll to DownloadStatus.ALL,
            binding.filterDownloading to DownloadStatus.DOWNLOADING,
            binding.filterCompleted to DownloadStatus.COMPLETED,
            binding.filterFailed to DownloadStatus.FAILED,
            binding.filterPaused to DownloadStatus.PAUSED
        )

        chips.forEach { (chip, status) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (status == DownloadStatus.ALL) {
                        // If ALL is selected, clear other filters
                        activeFilters.clear()
                        activeFilters.add(DownloadStatus.ALL)

                        // Uncheck other chips
                        chips.forEach { (otherChip, otherStatus) ->
                            if (otherStatus != DownloadStatus.ALL) {
                                otherChip.isChecked = false
                            }
                        }
                    } else {
                        // If a specific filter is selected, remove ALL filter
                        activeFilters.remove(DownloadStatus.ALL)
                        activeFilters.add(status)
                        binding.filterAll.isChecked = false
                    }
                } else {
                    // Remove this filter
                    activeFilters.remove(status)

                    // If no filters are active, activate ALL
                    if (activeFilters.isEmpty()) {
                        activeFilters.add(DownloadStatus.ALL)
                        binding.filterAll.isChecked = true
                    }
                }

                // Apply the updated filters
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        when (currentViewMode) {
            ViewMode.ANIME_LIST -> {
                var filteredList = animeList

                // If ALL is not active, filter by specific statuses
                if (!activeFilters.contains(DownloadStatus.ALL)) {
                    filteredList = animeList.filter { animeItem ->
                        // Include the anime if it has any episodes matching the filter
                        val hasDownloading = activeFilters.contains(DownloadStatus.DOWNLOADING) &&
                                (animeItem.runningCount > 0)
                        val hasCompleted = activeFilters.contains(DownloadStatus.COMPLETED) &&
                                (animeItem.completedCount > 0)
                        val hasFailed = activeFilters.contains(DownloadStatus.FAILED) &&
                                (animeItem.failedCount > 0)
                        val hasPaused = activeFilters.contains(DownloadStatus.PAUSED) &&
                                (animeItem.pausedCount > 0)

                        hasDownloading || hasCompleted || hasFailed || hasPaused
                    }.toMutableList()
                }

                animeAdapter.submitList(filteredList)
                binding.noDownloadsText.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            }

            ViewMode.EPISODE_LIST -> {
                var filteredEpisodes = getEpisodesForAnime(currentAnimeTitle)

                // If ALL is not active, filter by specific statuses
                if (!activeFilters.contains(DownloadStatus.ALL)) {
                    filteredEpisodes = filteredEpisodes.filter { episode ->
                        val status = mapStatusToEnum(episode.status)
                        activeFilters.contains(status)
                    }
                }

                episodeAdapter.submitList(filteredEpisodes)
                binding.noDownloadsText.visibility = if (filteredEpisodes.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAnimeList() {
        currentViewMode = ViewMode.ANIME_LIST

        // Update UI
        binding.backButton.visibility = View.GONE
        binding.currentAnimeTitle.visibility = View.GONE
        binding.downloadsHeader.text = "Downloads"

        // Set the appropriate adapter
        binding.downloadsRecycler.adapter = animeAdapter

        // Apply filters
        applyFilters()
    }

    private fun showEpisodeList(animeTitle: String) {
        currentViewMode = ViewMode.EPISODE_LIST
        currentAnimeTitle = animeTitle

        // Update UI
        binding.backButton.visibility = View.VISIBLE
        binding.currentAnimeTitle.visibility = View.VISIBLE
        binding.currentAnimeTitle.text = animeTitle
        binding.downloadsHeader.text = "Episodes"

        // Set the appropriate adapter
        binding.downloadsRecycler.adapter = episodeAdapter

        // Apply filters
        applyFilters()
    }

    private fun loadDownloads() {
        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.downloadsRecycler.visibility = View.GONE
        binding.noDownloadsText.visibility = View.GONE

        // Query download manager for anime downloads
        val query = DownloadManager.Query()
        val cursor = downloadManager.query(query)

        allDownloads.clear()

        if (cursor.moveToFirst()) {
            do {
                // Get column indices
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val descriptionIndex = cursor.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION)
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val totalBytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val downloadedBytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val lastModifiedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)

                // Check if indices are valid
                if (idIndex < 0 || titleIndex < 0 || statusIndex < 0) {
                    continue
                }

                // Extract values
                val id = cursor.getLong(idIndex)
                val title = cursor.getString(titleIndex) ?: "Unknown title"
                val description = cursor.getString(descriptionIndex) ?: ""
                val status = cursor.getInt(statusIndex)
                val totalBytes = if (totalBytesIndex >= 0) cursor.getLong(totalBytesIndex) else 0
                val downloadedBytes = if (downloadedBytesIndex >= 0) cursor.getLong(downloadedBytesIndex) else 0
                val localUri = if (localUriIndex >= 0) cursor.getString(localUriIndex) ?: "" else ""
                val lastModified = if (lastModifiedIndex >= 0) cursor.getLong(lastModifiedIndex) else 0

                // Parse anime title and episode number from the download title
                // Expected format: "Anime Title - Episode X"
                val parts = title.split(" - Episode ")
                if (parts.size >= 2) {
                    val animeTitle = parts[0]
                    val episodeNumber = parts[1].toIntOrNull() ?: 0

                    // Create a download item and add it to the list
                    val downloadItem = DownloadItem(
                        id = id,
                        animeTitle = animeTitle,
                        episodeNumber = episodeNumber,
                        title = "Episode $episodeNumber",
                        details = description,
                        status = status,
                        bytesTotal = totalBytes,
                        bytesDownloaded = downloadedBytes,
                        localUri = localUri,
                        lastModified = lastModified
                    )

                    allDownloads.add(downloadItem)
                }
            } while (cursor.moveToNext())
        }

        cursor.close()

        // Group downloads by anime title
        updateAnimeList()

        // Check if there are any downloads to show
        if (allDownloads.isEmpty()) {
            binding.noDownloadsText.visibility = View.VISIBLE
        } else {
            binding.downloadsRecycler.visibility = View.VISIBLE
            // Refresh the current view
            when (currentViewMode) {
                ViewMode.ANIME_LIST -> applyFilters()
                ViewMode.EPISODE_LIST -> {
                    // If the current anime no longer exists, go back to the anime list
                    if (animeList.none { it.title == currentAnimeTitle }) {
                        showAnimeList()
                    } else {
                        applyFilters()
                    }
                }
            }
        }

        binding.downloadProgressBar.visibility = View.GONE
    }

    private fun updateAnimeList() {
        // Group downloads by anime title
        val animeMap = allDownloads.groupBy { it.animeTitle }

        animeList.clear()

        animeMap.forEach { (animeTitle, episodes) ->
            // Count episodes by status
            val completedCount = episodes.count { it.status == DownloadManager.STATUS_SUCCESSFUL }
            val runningCount = episodes.count {
                it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PENDING
            }
            val failedCount = episodes.count { it.status == DownloadManager.STATUS_FAILED }
            val pausedCount = episodes.count { it.status == DownloadManager.STATUS_PAUSED }

            // Find the most recent modification timestamp
            val lastModified = episodes.maxOfOrNull { it.lastModified } ?: 0

            val animeItem = AnimeDownloadItem(
                title = animeTitle,
                episodeCount = episodes.size,
                completedCount = completedCount,
                runningCount = runningCount,
                failedCount = failedCount,
                pausedCount = pausedCount,
                lastModified = lastModified
            )

            animeList.add(animeItem)
        }

        // Sort by last modified (newest first)
        animeList.sortByDescending { it.lastModified }
    }

    private fun getEpisodesForAnime(animeTitle: String): List<DownloadItem> {
        val episodes = allDownloads.filter { it.animeTitle == animeTitle }

        // Sort episodes by number
        return episodes.sortedBy { it.episodeNumber }
    }

    private fun mapStatusToEnum(status: Int): DownloadStatus {
        return when (status) {
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadStatus.DOWNLOADING
            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
            DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
            else -> DownloadStatus.ALL
        }
    }

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playVideo(localUri: String) {
        if (localUri.isNotEmpty()) {
            try {
                // Parse the URI and get the file
                val uri = Uri.parse(localUri)
                val file = File(uri.path ?: "")

                if (!file.exists()) {
                    Toast.makeText(requireContext(), "File not found", Toast.LENGTH_SHORT).show()
                    return
                }

                // Extract episode title from the filename
                val filename = file.name
                val title = filename.substringBeforeLast('.').replace('_', ' ')

                // Create a content URI using FileProvider
                val contentUri = FileProvider.getUriForFile(
                    requireContext(),
                    "com.blank.anime.fileprovider",
                    file
                )

                // Navigate to VideoPlayerFragment
                val videoPlayerFragment = VideoPlayerFragment.newInstance(contentUri, title)

                // Add as an overlay on top of current UI instead of replacing the NavHostFragment
                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .add(android.R.id.content, videoPlayerFragment)
                    .addToBackStack("videoPlayer")
                    .commit()

            } catch (e: Exception) {
                Log.e("DownloadsFragment", "Error playing video", e)
                Toast.makeText(
                    requireContext(),
                    "Error playing video: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(requireContext(), "No file URI available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownload(downloadId: Long, localUri: String) {
        try {
            // Remove from download manager
            val deletedRows = downloadManager.remove(downloadId)

            // Try to delete the actual file too
            if (localUri.isNotEmpty()) {
                try {
                    val path = Uri.parse(localUri).path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DownloadsFragment", "Error deleting file", e)
                }
            }

            // Show status and refresh the list
            if (deletedRows > 0) {
                Toast.makeText(
                    requireContext(),
                    "Download deleted successfully",
                    Toast.LENGTH_SHORT
                ).show()

                // Refresh the list
                loadDownloads()
            }
        } catch (e: Exception) {
            Log.e("DownloadsFragment", "Error deleting download", e)
            Toast.makeText(
                requireContext(),
                "Error deleting download: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // View modes
    enum class ViewMode {
        ANIME_LIST,
        EPISODE_LIST
    }

    // Download status for filtering
    enum class DownloadStatus {
        ALL,
        DOWNLOADING,  // Combines PENDING and RUNNING
        COMPLETED,
        FAILED,
        PAUSED
    }

    // Data classes
    data class DownloadItem(
        val id: Long,
        val animeTitle: String,
        val episodeNumber: Int,
        val title: String,
        val details: String,
        val status: Int,
        val bytesTotal: Long,
        val bytesDownloaded: Long,
        val localUri: String,
        val lastModified: Long
    )

    data class AnimeDownloadItem(
        val title: String,
        val episodeCount: Int,
        val completedCount: Int,
        val runningCount: Int,
        val failedCount: Int,
        val pausedCount: Int,
        val lastModified: Long
    )

    // Adapters
    inner class AnimeDownloadAdapter(
        private val onAnimeClick: (String) -> Unit
    ) : RecyclerView.Adapter<AnimeDownloadAdapter.AnimeViewHolder>() {

        private var animeItems: List<AnimeDownloadItem> = emptyList()

        fun submitList(newList: List<AnimeDownloadItem>) {
            animeItems = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_anime_download, parent, false)
            return AnimeViewHolder(view)
        }

        override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
            holder.bind(animeItems[position])
        }

        override fun getItemCount() = animeItems.size

        inner class AnimeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.anime_title)
            private val episodeCountView: TextView = itemView.findViewById(R.id.episode_count)
            private val statusSummaryView: TextView = itemView.findViewById(R.id.status_summary)
            private val viewEpisodesButton: Button = itemView.findViewById(R.id.view_episodes_button)

            fun bind(animeItem: AnimeDownloadItem) {
                titleView.text = animeItem.title
                episodeCountView.text = "${animeItem.episodeCount} Episodes"

                // Create status summary
                val statusSummary = StringBuilder()
                if (animeItem.completedCount > 0) {
                    statusSummary.append("${animeItem.completedCount} Complete")
                }
                if (animeItem.runningCount > 0) {
                    if (statusSummary.isNotEmpty()) statusSummary.append(" • ")
                    statusSummary.append("${animeItem.runningCount} Running")
                }
                if (animeItem.failedCount > 0) {
                    if (statusSummary.isNotEmpty()) statusSummary.append(" • ")
                    statusSummary.append("${animeItem.failedCount} Failed")
                }
                if (animeItem.pausedCount > 0) {
                    if (statusSummary.isNotEmpty()) statusSummary.append(" • ")
                    statusSummary.append("${animeItem.pausedCount} Paused")
                }

                statusSummaryView.text = statusSummary

                // Set click listeners
                viewEpisodesButton.setOnClickListener {
                    onAnimeClick(animeItem.title)
                }

                // Make the whole item clickable
                itemView.setOnClickListener {
                    onAnimeClick(animeItem.title)
                }
            }
        }
    }

    inner class EpisodeDownloadAdapter(
        private val onPlayClick: (String) -> Unit,
        private val onDeleteClick: (Long, String) -> Unit
    ) : RecyclerView.Adapter<EpisodeDownloadAdapter.EpisodeViewHolder>() {

        private var episodes: List<DownloadItem> = emptyList()

        fun submitList(newList: List<DownloadItem>) {
            episodes = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return EpisodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            holder.bind(episodes[position])
        }

        override fun getItemCount() = episodes.size

        inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.download_title)
            private val detailsView: TextView = itemView.findViewById(R.id.download_details)
            private val statusView: TextView = itemView.findViewById(R.id.download_status)
            private val playButton: Button = itemView.findViewById(R.id.play_button)
            private val deleteButton: Button = itemView.findViewById(R.id.delete_button)

            fun bind(downloadItem: DownloadItem) {
                titleView.text = downloadItem.title

                // Format file size
                val sizeText = when {
                    downloadItem.bytesTotal <= 0 -> ""
                    downloadItem.bytesTotal < 1024 * 1024 -> "${downloadItem.bytesTotal / 1024} KB"
                    else -> String.format("%.1f MB", downloadItem.bytesTotal / (1024.0 * 1024.0))
                }

                // Format date
                val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val dateText = dateFormat.format(Date(downloadItem.lastModified))

                // Combine details with quality/language from original description
                detailsView.text = "${downloadItem.details} • $sizeText • $dateText"

                // Set status text and color
                when (downloadItem.status) {
                    DownloadManager.STATUS_PENDING -> {
                        statusView.text = "PENDING"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light))
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        val progress = if (downloadItem.bytesTotal > 0) {
                            (downloadItem.bytesDownloaded * 100 / downloadItem.bytesTotal).toInt()
                        } else {
                            0
                        }
                        statusView.text = "$progress%"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        statusView.text = "COMPLETED"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                    }
                    DownloadManager.STATUS_FAILED -> {
                        statusView.text = "FAILED"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        statusView.text = "PAUSED"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    }
                    else -> {
                        statusView.text = "UNKNOWN"
                        statusView.background.setTint(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                    }
                }

                // Handle play button
                playButton.isEnabled = downloadItem.status == DownloadManager.STATUS_SUCCESSFUL
                playButton.setOnClickListener {
                    onPlayClick(downloadItem.localUri)
                }

                // Handle delete button
                deleteButton.setOnClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete Download")
                        .setMessage("Are you sure you want to delete this download?")
                        .setPositiveButton("Delete") { _, _ ->
                            onDeleteClick(downloadItem.id, downloadItem.localUri)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }
}