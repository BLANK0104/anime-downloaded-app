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
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import androidx.media3.common.util.UnstableApi

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
        // Create quality and language selection dialogs
        val qualities = arrayOf("360p", "480p", "720p", "1080p")
        val languages = arrayOf("English", "Japanese")

        AlertDialog.Builder(requireContext())
            .setTitle("Select Language")
            .setItems(languages) { _, langIndex ->
                val lang = if (langIndex == 0) "eng" else "jpn"

                AlertDialog.Builder(requireContext())
                    .setTitle("Select Quality")
                    .setItems(qualities) { _, qualityIndex ->
                        val quality = when (qualityIndex) {
                            0 -> 360
                            1 -> 480
                            2 -> 720
                            else -> 1080
                        }

                        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                            setMessage("Preparing stream...")
                            setCancelable(false)
                            show()
                        }

                        lifecycleScope.launch {
                            try {
                                val response = animeRepository.getDownloadLink(
                                    animeId = animeId,
                                    episodeNum = episode.number,
                                    lang = lang,
                                    quality = quality,
                                    animeTitle = animeTitle
                                )

                                progressDialog.dismiss()

                                if (response.download_link.isNotEmpty()) {
                                    // Launch video player with the stream URL
                                    val episodeTitle = "$animeTitle - Episode ${episode.number}"
                                    val videoPlayerFragment = VideoPlayerFragment.newInstance(
                                        Uri.parse(response.download_link),
                                        episodeTitle
                                    )

                                    // Add as an overlay on top of current UI
                                    requireActivity().supportFragmentManager
                                        .beginTransaction()
                                        .add(android.R.id.content, videoPlayerFragment)
                                        .addToBackStack("videoPlayer")
                                        .commit()
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        "No stream available: ${response.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: retrofit2.HttpException) {
                                progressDialog.dismiss()
                                // Try fallback method
                                getStreamLinkFromEpisodesApi(episode, lang, quality)
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
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun getStreamLinkFromEpisodesApi(episode: Episode, lang: String, quality: Int) {
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Trying alternative stream method...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val response = animeRepository.getEpisodes(
                    animeId = animeId,
                    startEpisode = episode.number,
                    endEpisode = episode.number
                )

                progressDialog.dismiss()

                // Extract the link from the nested structure
                val episodeStr = episode.number.toString()
                val qualityStr = quality.toString()

                if (response.episodes.containsKey(episodeStr) &&
                    response.episodes[episodeStr]?.containsKey(lang) == true &&
                    response.episodes[episodeStr]?.get(lang)?.containsKey(qualityStr) == true) {

                    val links = response.episodes[episodeStr]?.get(lang)?.get(qualityStr)

                    if (links != null && links.isNotEmpty()) {
                        // Launch video player with the stream URL
                        val episodeTitle = "$animeTitle - Episode ${episode.number}"
                        val videoPlayerFragment = VideoPlayerFragment.newInstance(
                            Uri.parse(links[0]),
                            episodeTitle
                        )

                        // Add as an overlay on top of current UI
                        requireActivity().supportFragmentManager
                            .beginTransaction()
                            .add(android.R.id.content, videoPlayerFragment)
                            .addToBackStack("videoPlayer")
                            .commit()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "No stream available for this quality/language",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No stream available for Episode $episodeStr ($lang, ${quality}p)",
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

        // Create quality and language selection dialogs
        val qualities = arrayOf("360p", "480p", "720p", "1080p")
        val languages = arrayOf("English", "Japanese")

        AlertDialog.Builder(requireContext())
            .setTitle("Select Language")
            .setItems(languages) { _, langIndex ->
                val lang = if (langIndex == 0) "eng" else "jpn"

                AlertDialog.Builder(requireContext())
                    .setTitle("Select Quality")
                    .setItems(qualities) { _, qualityIndex ->
                        val quality = when (qualityIndex) {
                            0 -> 360
                            1 -> 480
                            2 -> 720
                            else -> 1080
                        }

                        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                            setMessage("Fetching download link...")
                            setCancelable(false)
                            show()
                        }

                        lifecycleScope.launch {
                            try {
                                val response = animeRepository.getDownloadLink(
                                    animeId = animeId,
                                    episodeNum = episode.number,
                                    lang = lang,
                                    quality = quality,
                                    animeTitle = animeTitle
                                )

                                progressDialog.dismiss()

                                if (response.download_link.isNotEmpty()) {
                                    startDownload(
                                        url = response.download_link,
                                        episodeNum = episode.number,
                                        lang = lang,
                                        quality = quality
                                    )
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        "No download link available: ${response.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: retrofit2.HttpException) {
                                progressDialog.dismiss()
                                // Try fallback method
                                getDownloadLinkFromEpisodesApi(episode, lang, quality)
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
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

            // Create request with proper user agent and referrer headers
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                // Add headers that streaming sites require
                addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                addRequestHeader("Referer", "https://anime-downloader-six.vercel.app/")  // Use your API domain here
                addRequestHeader("Accept", "video/mp4,video/*,*/*")
                addRequestHeader("Range", "bytes=0-")  // Some servers require range requests

                // Set title and description for notification
                setTitle("${animeTitle} - Episode ${episodeNum}")
                setDescription("${lang.uppercase()}, ${quality}p")

                // Set proper MIME type
                setMimeType("video/mp4")

                // Make download visible in UI
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // Media scanner visibility
                allowScanningByMediaScanner()

                // Set destination
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
                "Download started. Check notification area and Downloads app.",
                Toast.LENGTH_LONG
            ).show()

            // Register receiver for download completion (rest of your existing code)
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            // Your existing broadcast receiver code...
        } catch (e: Exception) {
            Log.e("DownloadDebug", "Download error", e)
            Toast.makeText(requireContext(), "Download error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Fallback method using episodes API if download API fails
    private fun getDownloadLinkFromEpisodesApi(episode: Episode, lang: String, quality: Int) {
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("Trying alternative download method...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val response = animeRepository.getEpisodes(
                    animeId = animeId,
                    startEpisode = episode.number,
                    endEpisode = episode.number
                )

                progressDialog.dismiss()

                // Extract the link from the nested structure
                val episodeStr = episode.number.toString()
                val qualityStr = quality.toString()

                if (response.episodes.containsKey(episodeStr) &&
                    response.episodes[episodeStr]?.containsKey(lang) == true &&
                    response.episodes[episodeStr]?.get(lang)?.containsKey(qualityStr) == true) {

                    val links = response.episodes[episodeStr]?.get(lang)?.get(qualityStr)

                    if (links != null && links.isNotEmpty()) {
                        // Start download using the extracted link
                        startDownload(
                            url = links[0],
                            episodeNum = episode.number,
                            lang = lang,
                            quality = quality
                        )
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "No download link available for this quality/language",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No download link available for Episode $episodeStr ($lang, ${quality}p)",
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