package com.blank.anime.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.R
import com.blank.anime.databinding.FragmentAnimeDetailsBinding
import com.blank.anime.repository.AnimeRepository
import kotlinx.coroutines.launch
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

class AnimeDetailsFragment : Fragment() {

    private var _binding: FragmentAnimeDetailsBinding? = null
    private val binding get() = _binding!!

    private var animeId: String = ""
    private var animeTitle: String = ""
    private var totalEpisodes: Int = 0
    private var isWatchMode = false // Default to download mode

    private lateinit var episodeAdapter: EpisodeAdapter
    private val animeRepository = AnimeRepository()

    // Single companion object with constants
    companion object {
        private const val STORAGE_PERMISSION_CODE = 1001
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Extract arguments
        arguments?.let { args ->
            animeId = args.getString("anime_id", "") ?: ""
            animeTitle = args.getString("anime_title", "") ?: ""
            totalEpisodes = args.getInt("total_episodes", 0)
        }

        setupUI()
        setupRecyclerView()
        setupListeners()
    }

    private fun setupUI() {
        binding.animeTitle.text = animeTitle
        binding.totalEpisodesText.text = "Total Episodes: $totalEpisodes"

        // Set default episode range
        binding.startEpisodeInput.setText("1")
        binding.endEpisodeInput.setText(if (totalEpisodes > 5) "5" else totalEpisodes.toString())

        // Initialize mode toggle button
        updateModeButton()

        // Initialize bulk download button visibility
        binding.bulkDownloadButton.visibility = if (isWatchMode) View.GONE else View.VISIBLE
    }

    private fun updateModeButton() {
        binding.modeToggleButton.text = if (isWatchMode) "Switch to Download Mode" else "Switch to Watch Mode"
        binding.modeToggleButton.setCompoundDrawablesWithIntrinsicBounds(
            if (isWatchMode) R.drawable.ic_download else R.drawable.ic_download,
            0, 0, 0
        )
    }

    private fun setupRecyclerView() {
        episodeAdapter = EpisodeAdapter()
        binding.episodesRecycler.apply {
            adapter = episodeAdapter
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupListeners() {
        binding.loadEpisodesButton.setOnClickListener {
            loadEpisodes()
        }

        binding.modeToggleButton.setOnClickListener {
            isWatchMode = !isWatchMode
            updateModeButton()
            // Update adapter to refresh buttons
            episodeAdapter.notifyDataSetChanged()
            // Show or hide bulk download button based on mode
            binding.bulkDownloadButton.visibility = if (isWatchMode) View.GONE else View.VISIBLE
        }

        // Add bulk download button listener
        binding.bulkDownloadButton.setOnClickListener {
            if (episodeAdapter.itemCount > 0) {
                startBulkDownload()
            } else {
                Toast.makeText(requireContext(), "Please load episodes first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadEpisodes() {
        val startEpisode = binding.startEpisodeInput.text.toString().toIntOrNull()
        val endEpisode = binding.endEpisodeInput.text.toString().toIntOrNull()

        if (startEpisode == null || endEpisode == null) {
            Toast.makeText(requireContext(), "Please enter valid episode numbers", Toast.LENGTH_SHORT).show()
            return
        }

        if (startEpisode < 1 || endEpisode > totalEpisodes || startEpisode > endEpisode) {
            Toast.makeText(
                requireContext(),
                "Please enter a valid range (1-$totalEpisodes)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Show loading state
        binding.progressBar.visibility = View.VISIBLE
        binding.episodesRecycler.visibility = View.GONE

        lifecycleScope.launch {
            try {
                // For demo/fallback, generate episodes
                val episodes = (startEpisode..endEpisode).map { episodeNumber ->
                    Episode(episodeNumber, "Episode $episodeNumber", "Description for episode $episodeNumber of $animeTitle")
                }

                episodeAdapter.submitList(episodes)

                // Hide loading, show results
                binding.progressBar.visibility = View.GONE
                binding.episodesRecycler.visibility = View.VISIBLE

                // After successfully loading episodes, show bulk download button if in download mode
                if (!isWatchMode) {
                    binding.bulkDownloadButton.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Permission result handler
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE && grantResults.isNotEmpty()
            && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Permission granted, you can download now", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Storage permission required for downloads", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleEpisodeAction(episode: Episode) {
        if (isWatchMode) {
            streamEpisode(episode)
        } else {
            downloadEpisode(episode)
        }
    }

    @OptIn(UnstableApi::class)
    private fun streamEpisode(episode: Episode) {
        // Show loading dialog while fetching episode data
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Checking available options...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // First get available options from the API
                val response = animeRepository.getEpisodes(
                    animeId = animeId,
                    startEpisode = episode.number,
                    endEpisode = episode.number
                )

                progressDialog.dismiss()

                // Extract available languages and qualities
                val episodeStr = episode.number.toString()
                val availableOptions = response.episodes[episodeStr]

                if (availableOptions.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "No streaming options available", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Get available languages
                val availableLanguages = availableOptions.keys.toList()
                val languageNames = availableLanguages.map {
                    when (it) {
                        "eng" -> "English"
                        "jpn" -> "Japanese"
                        else -> it.capitalize()
                    }
                }.toTypedArray()

                // Show language selection dialog with available options
                AlertDialog.Builder(requireContext())
                    .setTitle("Select Language")
                    .setItems(languageNames) { _, langIndex ->
                        val selectedLang = availableLanguages[langIndex]

                        // Get available qualities for this language
                        val availableQualities = availableOptions[selectedLang]?.keys?.toList() ?: emptyList()
                        val qualityLabels = availableQualities.map { "${it}p" }.toTypedArray()

                        // Show quality selection dialog with available options
                        AlertDialog.Builder(requireContext())
                            .setTitle("Select Quality")
                            .setItems(qualityLabels) { _, qualityIndex ->
                                val selectedQuality = availableQualities[qualityIndex].toInt()

                                // Try to get stream link using the download API
                                getStreamLinkFromEpisodesApi(episode, selectedLang, selectedQuality)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun getStreamLinkFromEpisodesApi(episode: Episode, lang: String, quality: Int) {
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Getting stream link...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // Use download API to get a direct link
                val downloadResponse = animeRepository.getDownloadLink(
                    animeId = animeId,
                    episodeNum = episode.number,
                    lang = lang,
                    quality = quality,
                    animeTitle = animeTitle
                )

                progressDialog.dismiss()

                if (downloadResponse.download_link.isNotEmpty()) {
                    // Launch video player with the direct link
                    val episodeTitle = "$animeTitle - Episode ${episode.number}"
                    val videoPlayerFragment = VideoPlayerFragment.newInstance(
                        Uri.parse(downloadResponse.download_link),
                        episodeTitle
                    )

                    requireActivity().supportFragmentManager
                        .beginTransaction()
                        .add(android.R.id.content, videoPlayerFragment)
                        .addToBackStack("videoPlayer")
                        .commit()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No stream available for this episode",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            }
        }
    }

    private fun downloadEpisode(episode: Episode) {
        // Check for storage permission
        if (android.os.Build.VERSION.SDK_INT <= 29 &&
            requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
            return
        }

        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Checking available options...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // First get available options from the API
                val response = animeRepository.getEpisodes(
                    animeId = animeId,
                    startEpisode = episode.number,
                    endEpisode = episode.number
                )

                progressDialog.dismiss()

                // Extract available languages and qualities
                val episodeStr = episode.number.toString()
                val availableOptions = response.episodes[episodeStr]

                if (availableOptions.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "No download options available", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Get available languages
                val availableLanguages = availableOptions.keys.toList()
                val languageNames = availableLanguages.map {
                    when (it) {
                        "eng" -> "English"
                        "jpn" -> "Japanese"
                        else -> it.capitalize()
                    }
                }.toTypedArray()

                // Show language selection dialog with available options
                AlertDialog.Builder(requireContext())
                    .setTitle("Select Language")
                    .setItems(languageNames) { _, langIndex ->
                        val selectedLang = availableLanguages[langIndex]

                        // Get available qualities for this language
                        val availableQualities = availableOptions[selectedLang]?.keys?.toList() ?: emptyList()
                        val qualityLabels = availableQualities.map { "${it}p" }.toTypedArray()

                        // Show quality selection dialog with available options
                        AlertDialog.Builder(requireContext())
                            .setTitle("Select Quality")
                            .setItems(qualityLabels) { _, qualityIndex ->
                                val selectedQuality = availableQualities[qualityIndex].toInt()

                                // Show loading while getting download link
                                val downloadProgressDialog = android.app.ProgressDialog(requireContext()).apply {
                                    setMessage("Getting download link...")
                                    setCancelable(false)
                                    show()
                                }

                                // Use the download API
                                lifecycleScope.launch {
                                    try {
                                        val downloadResponse = animeRepository.getDownloadLink(
                                            animeId = animeId,
                                            episodeNum = episode.number,
                                            lang = selectedLang,
                                            quality = selectedQuality,
                                            animeTitle = animeTitle
                                        )

                                        downloadProgressDialog.dismiss()

                                        if (downloadResponse.download_link.isNotEmpty()) {
                                            // Start download with the direct link
                                            startDownload(
                                                url = downloadResponse.download_link,
                                                episodeNum = episode.number,
                                                lang = selectedLang,
                                                quality = selectedQuality
                                            )
                                        } else {
                                            Toast.makeText(requireContext(),
                                                "No download link available",
                                                Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        downloadProgressDialog.dismiss()
                                        Toast.makeText(
                                            requireContext(),
                                            "Error: ${e.message ?: "Unknown error"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Handle bulk download
    private fun startBulkDownload() {
        // Check for storage permission on older Android versions
        if (android.os.Build.VERSION.SDK_INT <= 29 &&
            requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_CODE
            )
            return
        }

        // Get the first episode to check available options
        val episodes = episodeAdapter.getEpisodes()
        if (episodes.isEmpty()) {
            Toast.makeText(requireContext(), "No episodes available", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Checking available options...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                // Get available options from the first episode
                val response = animeRepository.getEpisodes(
                    animeId = animeId,
                    startEpisode = episodes.first().number,
                    endEpisode = episodes.first().number
                )

                progressDialog.dismiss()

                // Extract available languages and qualities
                val episodeStr = episodes.first().number.toString()
                val availableOptions = response.episodes[episodeStr]

                if (availableOptions.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "No download options available", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Get available languages
                val availableLanguages = availableOptions.keys.toList()
                val languageNames = availableLanguages.map {
                    when (it) {
                        "eng" -> "English"
                        "jpn" -> "Japanese"
                        else -> it.capitalize()
                    }
                }.toTypedArray()

                // Show language selection dialog with available options
                AlertDialog.Builder(requireContext())
                    .setTitle("Select Language")
                    .setItems(languageNames) { _, langIndex ->
                        val selectedLang = availableLanguages[langIndex]

                        // Get available qualities for this language
                        val availableQualities = availableOptions[selectedLang]?.keys?.toList() ?: emptyList()
                        val qualityLabels = availableQualities.map { "${it}p" }.toTypedArray()

                        // Show quality selection dialog with available options
                        AlertDialog.Builder(requireContext())
                            .setTitle("Select Quality")
                            .setItems(qualityLabels) { _, qualityIndex ->
                                val selectedQuality = availableQualities[qualityIndex].toInt()

                                // Confirm dialog for bulk download
                                val episodeCount = episodeAdapter.itemCount
                                AlertDialog.Builder(requireContext())
                                    .setTitle("Confirm Bulk Download")
                                    .setMessage("Download all $episodeCount episodes in ${selectedQuality}p ${languageNames[langIndex]}?\n\nThis will queue multiple downloads.")
                                    .setPositiveButton("Download") { _, _ ->
                                        processBulkDownload(selectedLang, selectedQuality)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message ?: "Unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun processBulkDownload(lang: String, quality: Int) {
        val episodes = episodeAdapter.getEpisodes()

        // Progress dialog for bulk download
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Starting bulk download (0/${episodes.size} episodes)...")
            setCancelable(false)
            show()
        }

        // Counter for successful downloads
        var successCount = 0
        var failCount = 0

        // Launch coroutine to process all episodes
        lifecycleScope.launch {
            episodes.forEachIndexed { index, episode ->
                try {
                    // Update progress message
                    progressDialog.setMessage("Processing episode ${episode.number} (${index+1}/${episodes.size})...")

                    // Get download link using the download API
                    try {
                        val response = animeRepository.getDownloadLink(
                            animeId = animeId,
                            episodeNum = episode.number,
                            lang = lang,
                            quality = quality,
                            animeTitle = animeTitle
                        )

                        if (response.download_link.isNotEmpty()) {
                            // Start download with the link
                            startDownload(
                                url = response.download_link,
                                episodeNum = episode.number,
                                lang = lang,
                                quality = quality
                            )
                            successCount++
                        } else {
                            failCount++
                            Log.e("DownloadDebug", "Empty download link received for episode ${episode.number}")
                        }
                    } catch (e: Exception) {
                        failCount++
                        Log.e("DownloadDebug", "Error getting download link for episode ${episode.number}", e)
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e("DownloadDebug", "Error processing episode ${episode.number}", e)
                }
            }

            // Dismiss progress dialog when done
            progressDialog.dismiss()

            // Show completion message
            Toast.makeText(
                requireContext(),
                "Bulk download: $successCount episodes queued, $failCount failed",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Improved download method in AnimeDetailsFragment.kt
    private fun startDownload(url: String, episodeNum: Int, lang: String, quality: Int) {
        try {
            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            // Log the URL for debugging
            Log.d("DownloadDebug", "Starting download from URL: $url")

            // Create a clean filename
            val safeTitle = animeTitle.replace("[^a-zA-Z0-9]".toRegex(), "_")
            val fileName = "${safeTitle}_E${episodeNum}_${lang}_${quality}p.mp4"

            // Create request with minimal settings to avoid issues
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // Use only essential headers
                addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

                // Set title and description for notification
                setTitle("${animeTitle} - Episode ${episodeNum}")
                setDescription("${lang.uppercase()}, ${quality}p")

                // Set MIME type
                setMimeType("video/mp4")

                // Make download visible in UI
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // Media scanner visibility
                allowScanningByMediaScanner()

                // Set destination - use Downloads directory
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                // Allow network types
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            // Enqueue the download
            val downloadId = downloadManager.enqueue(request)
            Log.d("DownloadDebug", "Download ID: $downloadId")

            // Show toast message
            Toast.makeText(
                requireContext(),
                "Download started for Episode $episodeNum. Check notification area.",
                Toast.LENGTH_LONG
            ).show()

            // On Android 13+ we need to specify export flags for receivers
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id == downloadId) {
                                // Check download status
                                val query = DownloadManager.Query().setFilterById(downloadId)
                                val cursor = downloadManager.query(query)

                                if (cursor.moveToFirst()) {
                                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                    if (statusIndex >= 0) {
                                        val status = cursor.getInt(statusIndex)
                                        when (status) {
                                            DownloadManager.STATUS_SUCCESSFUL ->
                                                Toast.makeText(context, "Download completed for Episode $episodeNum", Toast.LENGTH_SHORT).show()
                                            DownloadManager.STATUS_FAILED -> {
                                                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                                val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
                                                Toast.makeText(context, "Download failed: ${getDownloadErrorReason(reason)}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                cursor.close()
                                context?.unregisterReceiver(this)
                            }
                        }
                    },
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                // Older Android versions don't need the export flag
                requireContext().registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            // Same implementation as above
                            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id == downloadId) {
                                context?.unregisterReceiver(this)
                            }
                        }
                    },
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }

        } catch (e: Exception) {
            Log.e("DownloadDebug", "Download error", e)
            Toast.makeText(requireContext(), "Download error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Helper function to translate download error codes to human-readable messages
    private fun getDownloadErrorReason(reason: Int): String {
        return when(reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage device not found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
            DownloadManager.ERROR_FILE_ERROR -> "File error"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code"
            DownloadManager.ERROR_UNKNOWN -> "Unknown error"
            else -> "Error code: $reason"
        }
    }

    // Episode model class
    data class Episode(
        val number: Int,
        val title: String,
        val description: String
    )

    // Adapter for episode list
    inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.EpisodeViewHolder>() {

        private var episodes: List<Episode> = emptyList()

        fun submitList(newList: List<Episode>) {
            episodes = newList
            notifyDataSetChanged()
        }

        fun getEpisodes(): List<Episode> {
            return episodes
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return EpisodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
            holder.bind(episodes[position])
        }

        override fun getItemCount() = episodes.size

        inner class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val titleView: TextView = itemView.findViewById(R.id.episode_title)
            private val descriptionView: TextView = itemView.findViewById(R.id.episode_description)
            private val actionButton: Button = itemView.findViewById(R.id.download_button)

            fun bind(episode: Episode) {
                titleView.text = episode.title
                descriptionView.text = episode.description

                // Update button text based on current mode
                actionButton.text = if (isWatchMode) "Watch" else "Download"
                actionButton.setCompoundDrawablesWithIntrinsicBounds(
                    if (isWatchMode) R.drawable.ic_download else R.drawable.ic_download,
                    0, 0, 0
                )

                actionButton.setOnClickListener {
                    handleEpisodeAction(episode)
                }
            }
        }
    }
}