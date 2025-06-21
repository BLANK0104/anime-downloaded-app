package com.blank.anime.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.blank.anime.R
import com.blank.anime.adapters.AnimeAdapter
import com.blank.anime.adapters.EpisodeAdapter
import com.blank.anime.api.AnimeApiService
import com.blank.anime.api.RetrofitClient
import com.blank.anime.databinding.FragmentAnimeDetailsEnhancedBinding
import com.blank.anime.model.AniListMedia
import com.blank.anime.model.AniListMediaStatus
import com.blank.anime.model.AniListUserMediaStatus
import com.blank.anime.model.Episode
import com.blank.anime.utils.StorageManager
import com.blank.anime.viewmodel.AniListViewModel
import com.blank.anime.viewmodel.AnimeViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class AnimeDetailsFragment : Fragment() {

    private var _binding: FragmentAnimeDetailsEnhancedBinding? = null
    private val binding get() = _binding!!

    private lateinit var animeViewModel: AnimeViewModel
    private lateinit var aniListViewModel: AniListViewModel
    private lateinit var storageManager: StorageManager
    private lateinit var apiService: AnimeApiService

    private lateinit var recommendationsAdapter: AnimeAdapter
    private lateinit var episodesAdapter: EpisodeAdapter

    // Get animeId from arguments directly instead of using Safe Args
    private val animeId: String
        get() {
            val id = arguments?.getString("anime_id") ?: ""
            Log.d("AnimeDetailsFragment", "Getting animeId from arguments: $id")
            return id
        }

    // Get anime title from arguments
    private val animeTitle: String
        get() {
            val title = arguments?.getString("anime_title") ?: ""
            Log.d("AnimeDetailsFragment", "Getting animeTitle from arguments: $title")
            return title
        }

    // Current anime details
    private var currentAnime: AniListMedia? = null

    // Store session_id for later use
    private var animeSessionId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d("AnimeDetailsFragment", "onCreateView called")
        _binding = FragmentAnimeDetailsEnhancedBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Add a clear method to reset the state when fragment is reused
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Reset state variables to prevent issues when reusing the fragment
        animeSessionId = null
        currentAnime = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("AnimeDetailsFragment", "onViewCreated called")

        // Log received arguments
        Log.d("AnimeDetailsFragment", "Arguments: $arguments")
        arguments?.let {
            Log.d("AnimeDetailsFragment", "anime_id: ${it.getString("anime_id")}")
            Log.d("AnimeDetailsFragment", "anime_title: ${it.getString("anime_title")}")
            Log.d("AnimeDetailsFragment", "total_episodes: ${it.getInt("total_episodes", -1)}")
        }

        // Initialize ViewModels and services
        try {
            Log.d("AnimeDetailsFragment", "Initializing ViewModels and services")
            aniListViewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]
            animeViewModel = ViewModelProvider(requireActivity())[AnimeViewModel::class.java]
            storageManager = StorageManager.getInstance(requireContext())
            apiService = RetrofitClient.animeService  // Initialize the API service
            Log.d("AnimeDetailsFragment", "ViewModels and services initialized successfully")
        } catch (e: Exception) {
            Log.e("AnimeDetailsFragment", "Error initializing ViewModels or services", e)
        }

        // Set up toolbar
        setupToolbar()

        // Set up adapters
        setupAdapters()

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Load anime details
        Log.d("AnimeDetailsFragment", "About to load anime details")
        loadAnimeDetails()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupAdapters() {
        // Recommendations adapter
        recommendationsAdapter = AnimeAdapter { anime ->
            // TODO: Navigate to anime details for the clicked recommendation
            showSnackbar("Selected: ${anime.getPreferredTitle()}")
        }

        binding.recommendationsRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recommendationsAdapter
        }

        // Episodes adapter
        episodesAdapter = EpisodeAdapter(
            onEpisodeClick = { episode ->
                // Launch video player with the selected episode
                Log.d("AnimeDetailsFragment", "Streaming episode ${episode.number}, URL: ${episode.url}")

                // Create bundle with needed information
                val bundle = Bundle().apply {
                    putString("anime_id", animeId)
                    putString("anime_title", currentAnime?.getPreferredTitle())
                    putInt("episode_number", episode.number)
                    putString("episode_url", episode.url)

                    // Log the bundle contents for debugging
                    Log.d("AnimeDetailsFragment", "Video player bundle: anime_id=$animeId, title=${currentAnime?.getPreferredTitle()}, episode=${episode.number}")
                }

                // Navigate to video player
                findNavController().navigate(R.id.action_animeDetailsFragment_to_videoPlayerFragment, bundle)
                showSnackbar("Playing episode ${episode.number}")
            },
            onDownloadClick = { episode ->
                // Show quality selection for download
                Log.d("AnimeDetailsFragment", "Initiating download for episode ${episode.number}")
                showQualityDialog(episode)
                showSnackbar("Preparing download for episode ${episode.number}")
            }
        )

        binding.episodesRecycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = episodesAdapter
        }
    }

    private fun setupObservers() {
        // Observe AniList recommendations
        aniListViewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            val animeList = recommendations.map { it.media }
            recommendationsAdapter.submitList(animeList)
        }

        // Observe error messages
        aniListViewModel.errorMessage.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                showSnackbar(it)
                aniListViewModel.clearErrorMessage()
            }
        }

        // Observe episodes list
        animeViewModel.episodes.observe(viewLifecycleOwner) { episodeResponse ->
            // Process the nested map structure to create Episode objects
            val episodesList = mutableListOf<Episode>()

            // Log the episode response structure
            Log.d("AnimeDetailsFragment", "Episode response received: ${episodeResponse.episodes.keys.size} episodes found")

            try {
                // The outer map keys are episode numbers
                val sortedEpisodeNumbers = episodeResponse.episodes.keys.mapNotNull { it.toIntOrNull() }.sorted()
                Log.d("AnimeDetailsFragment", "Sorted episode numbers: $sortedEpisodeNumbers")

                // Get expected total episodes from AniList data or arguments
                val expectedTotalEpisodes = currentAnime?.episodes ?: arguments?.getInt("total_episodes", -1) ?: -1
                Log.d("AnimeDetailsFragment", "Expected total episodes from metadata: $expectedTotalEpisodes")

                // Generate all episode numbers we should have based on both the API response and metadata
                val allEpisodeNumbers = if (expectedTotalEpisodes > 0) {
                    // Generate sequential numbers from 1 to expectedTotalEpisodes
                    (1..expectedTotalEpisodes).toList()
                } else {
                    sortedEpisodeNumbers
                }

                for (episodeNumber in allEpisodeNumbers) {
                    val sources = episodeResponse.episodes[episodeNumber.toString()]

                    if (sources != null) {
                        // Process existing episode from API
                        // Prefer Japanese audio
                        val preferredSource = sources["jpn"] ?: sources.values.firstOrNull() ?: continue

                        // Prefer 1080p or highest available resolution
                        val preferredResolution = preferredSource["1080"] ?: preferredSource["720"]
                            ?: preferredSource.values.firstOrNull() ?: continue

                        if (preferredResolution.isNotEmpty()) {
                            val url = preferredResolution[0] // Use first URL

                            Log.d("AnimeDetailsFragment", "Creating episode: number=$episodeNumber, url=$url")

                            episodesList.add(
                                Episode(
                                    id = episodeNumber.toString(),
                                    number = episodeNumber,
                                    title = "Episode $episodeNumber",
                                    description = "${currentAnime?.getPreferredTitle() ?: ""} - Episode $episodeNumber",
                                    thumbnail = null, // No thumbnail in current structure
                                    url = url,
                                    isDownloaded = storageManager.episodeExists(
                                        currentAnime?.getPreferredTitle() ?: "",
                                        episodeNumber
                                    )
                                )
                            )
                        }
                    } else if (episodeNumber > sortedEpisodeNumbers.lastOrNull() ?: 0) {
                        // This is a missing episode that should exist according to metadata
                        Log.d("AnimeDetailsFragment", "Creating placeholder for missing episode: number=$episodeNumber")

                        // Add placeholder episode with null URL (will be shown as "Coming Soon")
                        episodesList.add(
                            Episode(
                                id = episodeNumber.toString(),
                                number = episodeNumber,
                                title = "Episode $episodeNumber",
                                description = "${currentAnime?.getPreferredTitle() ?: ""} - Episode $episodeNumber (Coming Soon)",
                                thumbnail = null,
                                url = null, // No URL available yet
                                isDownloaded = false,
                                isAvailable = false // Mark as unavailable
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error processing episodes: ${e.message}", e)
                Log.e("AnimeDetailsFragment", "Stack trace: ${e.stackTraceToString()}")
            }

            Log.d("AnimeDetailsFragment", "Final episode count: ${episodesList.size}")

            // Submit processed episodes to adapter
            episodesAdapter.submitList(episodesList)

            // Update progress seekbar max based on actual episode count from API
            // (not from AniList which might be incorrect)
            if (episodesList.isNotEmpty()) {
                val maxEpisodes = episodesList.maxOf { it.number }
                binding.progressSeekBar.max = maxEpisodes
                Log.d("AnimeDetailsFragment", "Set progress seekbar max to $maxEpisodes episodes")

                // Update progress text
                val progressEpisode = currentAnime?.progress ?: 0
                binding.progressText.text = if (progressEpisode > 0) {
                    "Episode $progressEpisode of $maxEpisodes"
                } else {
                    "Not started ($maxEpisodes episodes)"
                }
            }
        }
    }

    private fun setupClickListeners() {
        // FAB play button
        binding.fabPlay.setOnClickListener {
            // Play the first episode or continue where left off
            showSnackbar("Playing first episode")
        }

        // Download button
        binding.downloadButton.setOnClickListener {
            // Show download options dialog
            showDownloadOptionsDialog()
        }

        // Stream button
        binding.streamButton.setOnClickListener {
            // Show stream options dialog
            showStreamOptionsDialog()
        }

        // Update status button
        binding.updateStatusButton.setOnClickListener {
            showStatusUpdateDialog()
        }

        // Progress seekbar
        binding.progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && progress > 0) {
                    binding.progressText.text = "Episode $progress of ${seekBar.max}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // No action needed
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (seekBar.progress > 0) {
                    // Update progress on AniList
                    currentAnime?.let { anime ->
                        aniListViewModel.updateProgress(anime.id, seekBar.progress)
                        showSnackbar("Progress updated to episode ${seekBar.progress}")
                    }
                }
            }
        })
    }

    // In the loadAnimeDetails function, make sure you reset the state before getting new data
    private fun loadAnimeDetails() {
        // Reset state from previous searches to prevent mixing data
        animeSessionId = null

        // Use title for search
        val searchTerm = if (!animeTitle.isNullOrEmpty()) {
            Log.d("AnimeDetailsFragment", "Using anime title for search: $animeTitle")
            animeTitle
        } else {
            // If no title, show error message
            Log.e("AnimeDetailsFragment", "No anime title available in arguments")
            showSnackbar("Anime title not available")
            return
        }

        // First search using the API to get a valid session_id
        lifecycleScope.launch {
            try {
                Log.d("AnimeDetailsFragment", "Making API call to search anime: $searchTerm")

                // First search the anime using the API to get correct session_id
                val searchResponse = apiService.searchAnime(searchTerm)
                Log.d("AnimeDetailsFragment", "Search response received, result count: ${searchResponse.results.size}")

                if (searchResponse.results.isNotEmpty()) {
                    // Found anime in the API
                    val apiAnime = searchResponse.results.first() // Use the first result
                    Log.d("AnimeDetailsFragment", "First result: ${apiAnime.title}, session_id: ${apiAnime.session_id}")

                    // Store the session_id for later use
                    animeSessionId = apiAnime.session_id
                    Log.d("AnimeDetailsFragment", "Stored session_id: $animeSessionId")

                    // Now load episodes using the correct session_id
                    Log.d("AnimeDetailsFragment", "Getting episodes for session_id: ${apiAnime.session_id}")
                    animeViewModel.getEpisodes(apiAnime.session_id, 1, 25)

                    // Show anime info in UI
                    Log.d("AnimeDetailsFragment", "Updating UI with basic anime info")
                    binding.animeTitle.text = apiAnime.title
                    binding.animeStatus.text = "Status: ${apiAnime.status}"

                    // Also load from AniList for more detailed info
                    Log.d("AnimeDetailsFragment", "Searching AniList for more details: ${apiAnime.title}")
                    aniListViewModel.searchAnime(apiAnime.title) { results ->
                        Log.d("AnimeDetailsFragment", "AniList search results received, count: ${results.size}")
                        if (results.isNotEmpty()) {
                            val anime = results.first()
                            Log.d("AnimeDetailsFragment", "AniList anime found: id=${anime.id}, title=${anime.getPreferredTitle()}")
                            updateAnimeDetails(anime)

                            // Load recommendations for this anime
                            Log.d("AnimeDetailsFragment", "Getting recommendations for anime id=${anime.id}")
                            aniListViewModel.searchRecommendations(anime.id)
                        } else {
                            Log.w("AnimeDetailsFragment", "No results found in AniList for: ${apiAnime.title}")
                        }
                    }
                } else {
                    // No anime found, fallback to AniList only
                    Log.w("AnimeDetailsFragment", "No results found in API, falling back to AniList search")
                    fallbackToAniListSearch(searchTerm)
                    showSnackbar("Couldn't find anime in our catalog")
                }

            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error searching anime: ${e.message}", e)
                Log.e("AnimeDetailsFragment", "Stack trace: ${e.stackTraceToString()}")

                fallbackToAniListSearch(searchTerm)
                showSnackbar("Error searching: ${e.message}")
            }
        }
    }

    private fun fallbackToAniListSearch(searchTerm: String) {
        Log.d("AnimeDetailsFragment", "Falling back to AniList search with term: $searchTerm")

        // Search for the anime using title (more reliable than ID search)
        aniListViewModel.searchAnime(searchTerm) { results ->
            Log.d("AnimeDetailsFragment", "AniList fallback search results: ${results.size}")

            if (results.isNotEmpty()) {
                // Found anime in AniList
                val anime = results.first()
                Log.d("AnimeDetailsFragment", "First result from AniList: id=${anime.id}, title=${anime.getPreferredTitle()}")
                updateAnimeDetails(anime)

                // Load recommendations for this anime
                Log.d("AnimeDetailsFragment", "Loading recommendations for anime id=${anime.id}")
                aniListViewModel.searchRecommendations(anime.id)
            } else {
                // Try a more generic search if first attempt fails
                Log.d("AnimeDetailsFragment", "No results found in AniList, trying generic search with 'popular anime'")
                aniListViewModel.searchAnime("popular anime") { popularResults ->
                    Log.d("AnimeDetailsFragment", "Popular anime search results: ${popularResults.size}")

                    if (popularResults.isNotEmpty()) {
                        val anime = popularResults.first()
                        Log.d("AnimeDetailsFragment", "Using popular anime as fallback: id=${anime.id}, title=${anime.getPreferredTitle()}")
                        updateAnimeDetails(anime)
                        showSnackbar("Couldn't find specific anime, showing a popular one instead")

                        // Load recommendations for this anime
                        aniListViewModel.searchRecommendations(anime.id)
                    } else {
                        Log.e("AnimeDetailsFragment", "Could not find any anime, not even popular ones")
                        showSnackbar("Couldn't load any anime details")
                    }
                }
            }
        }
    }

    private fun updateAnimeDetails(anime: AniListMedia) {
        Log.d("AnimeDetailsFragment", "Updating UI with anime details: id=${anime.id}, title=${anime.getPreferredTitle()}")

        currentAnime = anime

        try {
            // Set collapsing toolbar title
            binding.collapsingToolbar.title = anime.getPreferredTitle()
            Log.d("AnimeDetailsFragment", "Set collapsing toolbar title: ${anime.getPreferredTitle()}")

            // Load banner image
            anime.bannerImage?.let { url ->
                Log.d("AnimeDetailsFragment", "Loading banner image: $url")
                binding.animeBanner.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_background)
                }
            } ?: Log.d("AnimeDetailsFragment", "No banner image URL available")

            // Load poster image
            anime.coverImageLarge?.let { url ->
                Log.d("AnimeDetailsFragment", "Loading poster image: $url")
                binding.animePoster.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                }
            } ?: Log.d("AnimeDetailsFragment", "No poster image URL available")

            // Set anime info
            binding.animeTitle.text = anime.getPreferredTitle()
            Log.d("AnimeDetailsFragment", "Set title text: ${anime.getPreferredTitle()}")

            val formatYear = "${anime.format ?: "TV"} • ${anime.seasonYear} • " +
                    if (anime.episodes > 0) "${anime.episodes} episodes" else "? episodes"
            binding.animeFormatYear.text = formatYear
            Log.d("AnimeDetailsFragment", "Set format/year text: $formatYear")

            // Set status
            val statusText = "Status: ${
                when (anime.status) {
                    AniListMediaStatus.FINISHED -> "Finished Airing"
                    AniListMediaStatus.RELEASING -> "Currently Airing"
                    AniListMediaStatus.NOT_YET_RELEASED -> "Not Yet Aired"
                    AniListMediaStatus.CANCELLED -> "Cancelled"
                    AniListMediaStatus.HIATUS -> "On Hiatus"
                    else -> "Unknown"
                }
            }"
            binding.animeStatus.text = statusText
            Log.d("AnimeDetailsFragment", "Set status text: $statusText")

            // Set description
            val description = anime.description?.replace("<br>", "\n") ?: "No description available."
            binding.animeDescription.text = description
            Log.d("AnimeDetailsFragment", "Set description text (length: ${description.length})")

            // Set stats
            binding.animeScore.text = anime.getFormattedScore()
            binding.animePopularity.text = formatPopularity(anime.popularity)
            Log.d("AnimeDetailsFragment", "Set score: ${anime.getFormattedScore()}, popularity: ${formatPopularity(anime.popularity)}")

            // Set progress
            if (anime.progress > 0) {
                binding.progressSeekBar.progress = anime.progress
                binding.progressText.text = "Episode ${anime.progress} of ${anime.episodes}"
                Log.d("AnimeDetailsFragment", "Set progress: ${anime.progress} of ${anime.episodes}")
            } else {
                binding.progressSeekBar.progress = 0
                binding.progressText.text = "Not started"
                Log.d("AnimeDetailsFragment", "Set progress: Not started")
            }

            // Add genre chips
            binding.genreChips.removeAllViews()
            anime.genres.forEach { genre ->
                val chip = Chip(requireContext()).apply {
                    text = genre
                    isCheckable = false
                }
                binding.genreChips.addView(chip)
            }
            Log.d("AnimeDetailsFragment", "Added ${anime.genres.size} genre chips")

            // Show/hide progress tracking based on login status
            val isLoggedIn = aniListViewModel.isLoggedIn()
            binding.progressCard.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
            Log.d("AnimeDetailsFragment", "Progress card visibility set based on login status: $isLoggedIn")

            Log.d("AnimeDetailsFragment", "Successfully completed UI update for anime details")
        } catch (e: Exception) {
            Log.e("AnimeDetailsFragment", "Error updating anime details UI", e)
            Log.e("AnimeDetailsFragment", "Stack trace: ${e.stackTraceToString()}")
        }
    }

    private fun formatPopularity(popularity: Int): String {
        return when {
            popularity >= 1_000_000 -> String.format("%.1fM", popularity / 1_000_000f)
            popularity >= 1_000 -> String.format("%.1fK", popularity / 1_000f)
            else -> popularity.toString()
        }
    }

    private fun showDownloadOptionsDialog() {
        // Get the current episode list
        val episodes = episodesAdapter.currentList

        // If no episodes are available, show a message
        if (episodes.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Episodes Available")
                .setMessage("No episodes are currently available for this anime. Please try another anime or check back later.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // If we don't have a valid session_id, show an error
        if (animeSessionId == null) {
            showSnackbar("Error: Missing anime information. Please try refreshing.")
            return
        }

        // Create array of episode titles for display
        val episodeTitles = episodes.map { "Episode ${it.number}" }.toTypedArray()
        val checkedItems = BooleanArray(episodes.size) { false }

        // Show dialog with episode selection for downloading
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download Episodes")
            .setMultiChoiceItems(episodeTitles, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Download") { _, _ ->
                val selectedEpisodes = episodes.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedEpisodes.isNotEmpty()) {
                    // Start downloading selected episodes
                    selectedEpisodes.forEach { episode ->
                        // Show language/quality options for download
                        showQualityDialog(episode)
                    }

                    showSnackbar("Starting download of ${selectedEpisodes.size} episode(s)...")
                } else {
                    showSnackbar("No episodes selected")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQualityDialog(episode: Episode) {
        val qualities = arrayOf("720p", "1080p")
        val languages = arrayOf("Japanese", "English (if available)")

        // First select quality
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Quality for Episode ${episode.number}")
            .setSingleChoiceItems(qualities, 0) { qualityDialog, qualityIndex ->
                qualityDialog.dismiss()

                // Then select language
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Language for Episode ${episode.number}")
                    .setSingleChoiceItems(languages, 0) { langDialog, langIndex ->
                        langDialog.dismiss()

                        // Get the selected quality and language
                        val quality = if (qualityIndex == 0) 720 else 1080
                        val language = if (langIndex == 0) "jpn" else "eng"

                        // Now get the download link using the correct session_id
                        animeViewModel.getDownloadLink(
                            animeId = animeSessionId!!, // Use the stored session_id instead of animeId
                            episodeNum = episode.number,
                            lang = language,
                            quality = quality,
                            animeTitle = currentAnime?.getPreferredTitle() ?: "Unknown"
                        )

                        // Observe the download info
                        animeViewModel.downloadInfo.observe(viewLifecycleOwner) { downloadResponse ->
                            if (downloadResponse != null) {
                                // Now download the episode using the download response
                                animeViewModel.downloadEpisodeViaStorageManager(requireContext(), downloadResponse)

                                // Remove the observer after initiating download
                                animeViewModel.downloadInfo.removeObservers(viewLifecycleOwner)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStreamOptionsDialog() {
        // Get the current episode list
        val episodes = episodesAdapter.currentList

        // If no episodes are available, show a message
        if (episodes.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Episodes Available")
                .setMessage("No episodes are currently available for this anime. Please try another anime or check back later.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Create array of episode titles for display
        val episodeTitles = episodes.map { "Episode ${it.number}" }.toTypedArray()

        // Show dialog with episode selection for streaming
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stream Episodes")
            .setSingleChoiceItems(episodeTitles, -1) { dialog, which ->
                val selectedEpisode = episodes[which]
                // Navigate to video player with selected episode
                val bundle = Bundle().apply {
                    putString("anime_id", animeId.toString())
                    putString("anime_title", currentAnime?.getPreferredTitle())
                    putInt("episode_number", selectedEpisode.number)
                    putString("episode_url", selectedEpisode.url)
                }
                findNavController().navigate(R.id.action_animeDetailsFragment_to_videoPlayerFragment, bundle)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatusUpdateDialog() {
        // Show dialog to update watch status
        val statuses = arrayOf(
            "Currently Watching",
            "Plan to Watch",
            "Completed",
            "On Hold",
            "Dropped"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Update Status")
            .setItems(statuses) { _, which ->
                val status = when (which) {
                    0 -> AniListUserMediaStatus.CURRENT
                    1 -> AniListUserMediaStatus.PLANNING
                    2 -> AniListUserMediaStatus.COMPLETED
                    3 -> AniListUserMediaStatus.PAUSED
                    4 -> AniListUserMediaStatus.DROPPED
                    else -> AniListUserMediaStatus.PLANNING
                }

                currentAnime?.let { anime ->
                    aniListViewModel.updateStatus(anime.id, status)
                    showSnackbar("Status updated to ${statuses[which]}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Reset state when view is destroyed
        animeSessionId = null
    }
}
