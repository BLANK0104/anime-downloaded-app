package com.blank.anime.ui

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.R
import com.blank.anime.databinding.FragmentDownloadsBinding
import com.blank.anime.utils.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.util.Log
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class DownloadsFragment : Fragment(), VideoPlayerFragment.EpisodeNavigationListener {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadManager: DownloadManager
    private lateinit var storageManager: StorageManager
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
        storageManager = StorageManager.getInstance(requireContext())

        // Check if storage directory is set, if not show a message
        if (!storageManager.hasStorageDirectorySet()) {
            showStoragePermissionNeededMessage()
            return
        }

        setupAdapters()
        setupFilterChips()
        setupBackButton()

        // Show anime list by default
        showAnimeList()

        // Load downloads when fragment becomes visible
        loadDownloads()
    }

    private fun showStoragePermissionNeededMessage() {
        binding.downloadsHeader.text = "Storage Access Needed"
        binding.noDownloadsText.visibility = View.VISIBLE
        binding.noDownloadsText.text = "Please set a storage location in the settings to access your downloads."
        binding.downloadsRecycler.visibility = View.GONE
        binding.downloadProgressBar.visibility = View.GONE
        binding.filterChips.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()

        // Check if storage is set
        if (!storageManager.hasStorageDirectorySet()) {
            showStoragePermissionNeededMessage()
            return
        }

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
            onPlayClick = { localUri, animeTitle, episodeNumber ->
                playVideo(localUri, animeTitle, episodeNumber)
            },
            onDeleteClick = { _, localUri ->
                deleteDownload(localUri)
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
                    // If "All" is selected, clear other filters
                    if (status == DownloadStatus.ALL) {
                        activeFilters.clear()
                        activeFilters.add(DownloadStatus.ALL)

                        // Uncheck other chips
                        chips.forEach { (otherChip, otherStatus) ->
                            if (otherStatus != DownloadStatus.ALL) {
                                otherChip.isChecked = false
                            }
                        }
                    } else {
                        // If another filter is selected, remove "All"
                        activeFilters.remove(DownloadStatus.ALL)
                        activeFilters.add(status)

                        // Uncheck "All" chip
                        binding.filterAll.isChecked = false
                    }
                } else {
                    // Remove the filter if unchecked
                    activeFilters.remove(status)

                    // If no filters are selected, default to "All"
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
                val filteredList = if (!activeFilters.contains(DownloadStatus.ALL)) {
                    animeList.filter { animeItem ->
                        val hasDownloading = activeFilters.contains(DownloadStatus.DOWNLOADING) && animeItem.runningCount > 0
                        val hasCompleted = activeFilters.contains(DownloadStatus.COMPLETED) && animeItem.completedCount > 0
                        val hasFailed = activeFilters.contains(DownloadStatus.FAILED) && animeItem.failedCount > 0
                        val hasPaused = activeFilters.contains(DownloadStatus.PAUSED) && animeItem.pausedCount > 0

                        hasDownloading || hasCompleted || hasFailed || hasPaused
                    }
                } else {
                    animeList
                }

                animeAdapter.submitList(filteredList)
                binding.noDownloadsText.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
            }

            ViewMode.EPISODE_LIST -> {
                val filteredEpisodes = if (!activeFilters.contains(DownloadStatus.ALL)) {
                    getEpisodesForAnime(currentAnimeTitle).filter { episode ->
                        val status = mapStatusToEnum(episode.status)
                        activeFilters.contains(status)
                    }
                } else {
                    getEpisodesForAnime(currentAnimeTitle)
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
        if (!storageManager.hasStorageDirectorySet()) {
            showStoragePermissionNeededMessage()
            return
        }

        binding.downloadProgressBar.visibility = View.VISIBLE
        binding.downloadsRecycler.visibility = View.GONE
        binding.noDownloadsText.visibility = View.GONE
        binding.filterChips.visibility = View.VISIBLE

        allDownloads.clear()

        // Get the storage directory URI
        val storageUri = storageManager.getStorageDirectoryUri()
        if (storageUri == null) {
            binding.noDownloadsText.visibility = View.VISIBLE
            binding.noDownloadsText.text = "Storage location not found"
            binding.downloadProgressBar.visibility = View.GONE
            return
        }

        // Load downloads from the storage directory instead of DownloadManager
        try {
            // Get all anime titles from storage
            val animeTitles = getAnimeFromStorage()

            // For each anime, get all episodes
            for (animeTitle in animeTitles) {
                val episodes = storageManager.getAnimeEpisodes(animeTitle)

                for (episode in episodes) {
                    val downloadItem = DownloadItem(
                        id = -1, // No download ID for storage manager files
                        animeTitle = animeTitle,
                        episodeNumber = episode.episodeNumber,
                        title = "Episode ${episode.episodeNumber}",
                        description = "${animeTitle} - Episode ${episode.episodeNumber} - ${episode.quality}p",
                        status = DownloadManager.STATUS_SUCCESSFUL, // All storage manager files are completed
                        totalBytes = episode.fileSize,
                        downloadedBytes = episode.fileSize,
                        localUri = episode.uri.toString(),
                        lastModified = episode.lastModified
                    )

                    allDownloads.add(downloadItem)
                }
            }

            // Also check DownloadManager for any in-progress downloads
            checkDownloadManagerForActiveDownloads()

        } catch (e: Exception) {
            Log.e("DownloadsFragment", "Error loading downloads: ${e.message}")
            binding.noDownloadsText.visibility = View.VISIBLE
            binding.noDownloadsText.text = "Error loading downloads: ${e.message}"
            binding.downloadProgressBar.visibility = View.GONE
            return
        }

        // Group downloads by anime title
        updateAnimeList()

        // Check if there are any downloads to show
        if (allDownloads.isEmpty()) {
            binding.noDownloadsText.visibility = View.VISIBLE
        } else {
            binding.downloadsRecycler.visibility = View.VISIBLE
        }

        binding.downloadProgressBar.visibility = View.GONE
    }

    private fun getAnimeFromStorage(): List<String> {
        try {
            // Get the storage directory URI
            val storageUri = storageManager.getStorageDirectoryUri() ?: return emptyList()

            // Get the "Anime" directory from the storage directory
            val animeDir = storageManager.getAnimeDirectory() ?: return emptyList()

            // Get all subdirectories (anime titles)
            val animeTitles = mutableListOf<String>()

            animeDir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    file.name?.let { name ->
                        animeTitles.add(name)
                    }
                }
            }

            return animeTitles
        } catch (e: Exception) {
            Log.e("DownloadsFragment", "Error getting anime from storage: ${e.message}")
            return emptyList()
        }
    }

    private fun checkDownloadManagerForActiveDownloads() {
        // Query download manager for in-progress anime downloads
        val query = DownloadManager.Query()
        query.setFilterByStatus(
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_PAUSED
        )

        val cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            do {
                // Get download ID and status
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                // Get download title and description
                val titleColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val descriptionColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_DESCRIPTION)

                val title = cursor.getString(titleColumn) ?: ""
                val description = cursor.getString(descriptionColumn) ?: ""

                // Skip if not an anime download (based on title format)
                if (!title.contains(" - Episode ")) continue

                // Parse anime title and episode number
                val parts = title.split(" - Episode ")
                if (parts.size != 2) continue

                val animeTitle = parts[0].trim()
                val episodeNumber = parts[1].toIntOrNull() ?: 0

                // Get file size information
                val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))

                // Get local URI
                val localUriString = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: ""

                // Get last modified timestamp
                val lastModified = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))

                // Create download item
                val downloadItem = DownloadItem(
                    id = id,
                    animeTitle = animeTitle,
                    episodeNumber = episodeNumber,
                    title = title,
                    description = description,
                    status = status,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                    localUri = localUriString,
                    lastModified = lastModified
                )

                allDownloads.add(downloadItem)
            } while (cursor.moveToNext())
        }

        cursor.close()
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

    @OptIn(UnstableApi::class)
    private fun playVideo(localUri: String, animeTitle: String, episodeNumber: Int) {
        if (localUri.isNotEmpty()) {
            try {
                val uri = Uri.parse(localUri)
                val videoPlayerFragment = VideoPlayerFragment.newInstance(
                    uri,
                    "Episode $episodeNumber",
                    animeTitle,
                    episodeNumber
                )

                // Set this fragment as the navigation listener
                videoPlayerFragment.setEpisodeNavigationListener(this)

                // Add as an overlay on top of current UI
                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .add(android.R.id.content, videoPlayerFragment)
                    .addToBackStack(null)
                    .commit()
            } catch (e: Exception) {
                Log.e("DownloadsFragment", "Error playing video: ${e.message}")
                Toast.makeText(requireContext(), "Error playing video: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "No file URI available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteDownload(localUri: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Download")
            .setMessage("Are you sure you want to delete this download? This will remove the file from your device.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val uri = Uri.parse(localUri)

                    // Use StorageManager to delete the file
                    val deleted = storageManager.deleteEpisodeFile(uri)

                    if (deleted) {
                        Toast.makeText(requireContext(), "Download deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Failed to delete file", Toast.LENGTH_SHORT).show()
                    }

                    // Refresh the list
                    loadDownloads()
                } catch (e: Exception) {
                    Log.e("DownloadsFragment", "Error deleting file: ${e.message}")
                    Toast.makeText(requireContext(), "Error deleting file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // VideoPlayerFragment.EpisodeNavigationListener implementation
    override fun onNextEpisode(currentAnime: String, currentEpisode: Int) {
        // Find next episode
        val nextEpisode = storageManager.findNextEpisode(currentAnime, currentEpisode)

        if (nextEpisode != null) {
            playVideo(nextEpisode.uri.toString(), currentAnime, nextEpisode.episodeNumber)
        } else {
            Toast.makeText(requireContext(), "No next episode found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPreviousEpisode(currentAnime: String, currentEpisode: Int) {
        // Find previous episode
        val previousEpisode = storageManager.findPreviousEpisode(currentAnime, currentEpisode)

        if (previousEpisode != null) {
            playVideo(previousEpisode.uri.toString(), currentAnime, previousEpisode.episodeNumber)
        } else {
            Toast.makeText(requireContext(), "No previous episode found", Toast.LENGTH_SHORT).show()
        }
    }

    enum class ViewMode {
        ANIME_LIST,
        EPISODE_LIST
    }

    enum class DownloadStatus {
        ALL,
        DOWNLOADING,
        COMPLETED,
        FAILED,
        PAUSED
    }
}