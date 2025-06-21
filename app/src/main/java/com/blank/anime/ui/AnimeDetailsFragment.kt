package com.blank.anime.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.coordinatorlayout.widget.CoordinatorLayout
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
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

    // Custom episode loading properties
    private var selectedQuality = "1080"
    private var selectedLanguage = "jpn" // Default to Japanese
    private var customEpisodesLoaded = false

    // Views for custom episode loading
    private lateinit var startEpisodeInput: EditText
    private lateinit var endEpisodeInput: EditText
    private lateinit var selectQualityButton: Button
    private lateinit var selectLanguageButton: Button
    private lateinit var loadEpisodesButton: Button
    private lateinit var batchDownloadButton: Button
    private lateinit var episodeLoadingCard: CardView

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

        // Create custom episode loading UI
        createCustomEpisodeLoadingUI()

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
                // Show quality and language selection dialog before streaming
                Log.d("AnimeDetailsFragment", "Starting quality/language selection for streaming episode ${episode.number}")
                showStreamingQualityDialog(episode)
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

        // NOTE: The custom episode loading UI elements are now initialized and set up
        // in the createCustomEpisodeLoadingUI() method instead of here
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

                    // Now load episodes in batches using the correct session_id
                    val totalEpisodes = apiAnime.episodes ?: 220 // If episodes count is null, use a reasonable fallback
                    Log.d("AnimeDetailsFragment", "Getting episodes for session_id: ${apiAnime.session_id}, total episodes: $totalEpisodes")

                    // Use batch loading instead of a single request for all episodes
                    fetchEpisodesInBatches(apiAnime.session_id, totalEpisodes)

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
        // Fetch episode details to get available quality options
        lifecycleScope.launch {
            try {
                // Show loading indicator using Snackbar instead of progressBar
                val loadingSnackbar = Snackbar.make(binding.root, "Loading quality options...", Snackbar.LENGTH_INDEFINITE)
                loadingSnackbar.show()

                // Get episodes info to extract available qualities and languages
                val episodesResponse = animeViewModel.getEpisodesSynchronously(
                    animeId = animeSessionId!!,
                    startEpisode = episode.number,
                    endEpisode = episode.number
                )

                // Hide loading indicator
                loadingSnackbar.dismiss()

                if (episodesResponse != null && episodesResponse.episodes.containsKey(episode.number.toString())) {
                    val sources = episodesResponse.episodes[episode.number.toString()]

                    // Extract available languages
                    val availableLanguages = sources?.keys?.toList() ?: listOf("jpn")
                    val languageLabels = availableLanguages.map {
                        if (it == "jpn") "Japanese" else if (it == "eng") "English" else it
                    }.toTypedArray()

                    // Show language selection dialog
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Select Language for Episode ${episode.number}")
                        .setSingleChoiceItems(languageLabels, 0) { langDialog, langIndex ->
                            langDialog.dismiss()

                            val selectedLang = availableLanguages[langIndex]

                            // Get available qualities for the selected language
                            val qualities = sources?.get(selectedLang)?.keys?.toList() ?: listOf("720")
                            val qualityLabels = qualities.map { "${it}p" }.toTypedArray()

                            // Show quality selection dialog
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Select Quality for Episode ${episode.number}")
                                .setSingleChoiceItems(qualityLabels, 0) { qualityDialog, qualityIndex ->
                                    qualityDialog.dismiss()

                                    // Get the selected quality
                                    val selectedQuality = qualities[qualityIndex].toInt()

                                    Log.d("AnimeDetailsFragment", "Selected for download: Quality=${selectedQuality}p, Language=$selectedLang")

                                    // Now get the download link
                                    animeViewModel.getDownloadLink(
                                        animeId = animeSessionId!!,
                                        episodeNum = episode.number,
                                        lang = selectedLang,
                                        quality = selectedQuality,
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
                } else {
                    // Fallback to hardcoded options if we can't get the episode details
                    showDefaultQualityDialog(episode)
                }
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error getting episode qualities: ${e.message}", e)

                // Fallback to hardcoded options if there's an error
                showDefaultQualityDialog(episode)
            }
        }
    }

    private fun showDefaultQualityDialog(episode: Episode) {
        val qualities = arrayOf("720p", "1080p")
        val languages = arrayOf("Japanese", "English (if available)")

        // First select language
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Language for Episode ${episode.number}")
            .setSingleChoiceItems(languages, 0) { langDialog, langIndex ->
                langDialog.dismiss()

                // Then select quality
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Quality for Episode ${episode.number}")
                    .setSingleChoiceItems(qualities, 0) { qualityDialog, qualityIndex ->
                        qualityDialog.dismiss()

                        // Get the selected quality and language
                        val quality = if (qualityIndex == 0) 720 else 1080
                        val language = if (langIndex == 0) "jpn" else "eng"

                        // Now get the download link using the correct session_id
                        animeViewModel.getDownloadLink(
                            animeId = animeSessionId!!,
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
        if (view == null || !isAdded || _binding == null) {
            // Skip showing snackbar if fragment view is destroyed or not attached
            Log.d("AnimeDetailsFragment", "Skipping snackbar: $message (view destroyed or not attached)")
            return
        }
        try {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback in case of any other issue
            Log.e("AnimeDetailsFragment", "Error showing snackbar: ${e.message}")
        }
    }

    /**
     * Shows a dialog for selecting streaming quality and language before playing the episode
     */
    private fun showStreamingQualityDialog(episode: Episode) {
        // Use the new dynamic quality dialog implementation instead
        showDynamicQualityDialog(episode)
    }

    // New function to fetch episodes in batches with improved waiting logic
    private fun fetchEpisodesInBatches(sessionId: String, totalEpisodes: Int) {
        val batchSize = 10 // Number of episodes to fetch in each batch

        // Clear existing episodes before fetching new ones
        animeViewModel.clearEpisodes()

        // Launch a coroutine to handle sequential fetching
        lifecycleScope.launch {
            try {
                // Calculate how many batches we need
                val batchCount = (totalEpisodes + batchSize - 1) / batchSize

                Log.d("AnimeDetailsFragment", "Starting batch episode loading: total episodes=$totalEpisodes, batchSize=$batchSize, batches=$batchCount")

                // Process one batch at a time to ensure proper loading
                for (batchIndex in 0 until batchCount) {
                    val startEpisode = batchIndex * batchSize + 1
                    val endEpisode = minOf((batchIndex + 1) * batchSize, totalEpisodes)

                    Log.d("AnimeDetailsFragment", "Loading batch ${batchIndex + 1}/$batchCount: episodes $startEpisode to $endEpisode")

                    // Request this batch of episodes and wait for completion
                    val episodesResponse = animeViewModel.getEpisodesSynchronously(
                        animeId = sessionId,
                        startEpisode = startEpisode,
                        endEpisode = endEpisode
                    )

                    // Process and add episodes from this batch to the accumulated list
                    episodesResponse?.episodes?.let { episodes ->
                        // Add episodes from this batch to the existing episodes in the ViewModel
                        // Cast with @Suppress to ensure correct type compatibility
                        @Suppress("UNCHECKED_CAST")
                        animeViewModel.addEpisodesToCurrentList(
                            episodes as Map<String, Map<String, Map<String, List<String>>>>
                        )

                        // Log how many episodes were actually received in this batch
                        val receivedEpisodeCount = episodes.size
                        Log.d("AnimeDetailsFragment", "Batch ${batchIndex + 1} complete: received $receivedEpisodeCount episodes")
                    } ?: run {
                        Log.d("AnimeDetailsFragment", "No episodes received in batch ${batchIndex + 1}")
                    }

                    // Add a delay between batches to avoid overwhelming the API
                    delay(800)
                }

                // After all batches are loaded, ensure all expected episodes are present with proper data
                animeViewModel.ensureAllEpisodesPresent(totalEpisodes)

                Log.d("AnimeDetailsFragment", "All batch loading complete")
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error fetching episodes in batches: ${e.message}", e)
                showSnackbar("Error loading all episodes: ${e.message}")
            }
        }
    }

    // Load custom episodes based on user input
    private fun loadCustomEpisodes() {
        // Validate episode range inputs
        val startEpisode = startEpisodeInput.text.toString().toIntOrNull()
        val endEpisode = endEpisodeInput.text.toString().toIntOrNull()

        if (startEpisode == null || endEpisode == null || startEpisode > endEpisode) {
            showSnackbar("Invalid episode range")
            return
        }

        // Show loading indicator (using our programmatically created card)
        episodeLoadingCard.visibility = View.VISIBLE

        // Reset custom episodes loaded flag
        customEpisodesLoaded = false

        // Launch coroutine to load episodes
        lifecycleScope.launch {
            try {
                // Clear existing episodes
                animeViewModel.clearEpisodes()

                // Fetch episodes in the specified range
                val sessionId = animeSessionId ?: return@launch
                animeViewModel.getEpisodes(sessionId, startEpisode, endEpisode)

                // Wait for episodes to be loaded
                delay(1000)

                // Update UI after loading episodes
                updateEpisodesUI()

                // Show success message
                showSnackbar("Loaded episodes $startEpisode to $endEpisode")
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error loading custom episodes: ${e.message}", e)
                showSnackbar("Error loading episodes")
            } finally {
                // Hide loading indicator
                episodeLoadingCard.visibility = View.GONE
                customEpisodesLoaded = true
            }
        }
    }

    // Update episodes RecyclerView and other UI elements after loading custom episodes
    private fun updateEpisodesUI() {
        // Get the loaded episodes
        val loadedEpisodes = animeViewModel.episodes.value?.episodes ?: return

        // Create episode list for adapter
        val episodeList = mutableListOf<Episode>()

        // Add loaded episodes to the list
        for ((episodeNumber, sources) in loadedEpisodes) {
            // Prefer Japanese audio
            val preferredSource = sources["jpn"] ?: sources.values.firstOrNull() ?: continue

            // Prefer 1080p or highest available resolution
            val preferredResolution = preferredSource["1080"] ?: preferredSource["720"]
                ?: preferredSource.values.firstOrNull() ?: continue

            if (preferredResolution.isNotEmpty()) {
                val url = preferredResolution[0] // Use first URL

                episodeList.add(
                    Episode(
                        id = episodeNumber,
                        number = episodeNumber.toInt(),
                        title = "Episode $episodeNumber",
                        description = "${currentAnime?.getPreferredTitle() ?: ""} - Episode $episodeNumber",
                        thumbnail = null, // No thumbnail in current structure
                        url = url,
                        isDownloaded = storageManager.episodeExists(
                            currentAnime?.getPreferredTitle() ?: "",
                            episodeNumber.toInt()
                        )
                    )
                )
            }
        }

        // Submit the new episode list to the adapter
        episodesAdapter.submitList(episodeList)

        // Update progress seekbar max
        if (episodeList.isNotEmpty()) {
            val maxEpisodes = episodeList.maxOf { it.number }
            binding.progressSeekBar.max = maxEpisodes

            // Update progress text
            val progressEpisode = currentAnime?.progress ?: 0
            binding.progressText.text = if (progressEpisode > 0) {
                "Episode $progressEpisode of $maxEpisodes"
            } else {
                "Not started ($maxEpisodes episodes)"
            }
        }
    }

    // Show dialog for batch download options
    private fun showBatchDownloadOptionsDialog() {
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
            .setTitle("Batch Download Episodes")
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

    // Show dialog for selecting quality
    private fun showQualitySelectionDialog() {
        val qualities = arrayOf("360p", "720p", "1080p")
        val currentQualityIndex = when (selectedQuality) {
            "360" -> 0
            "720" -> 1
            else -> 2 // Default to 1080p
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Quality")
            .setSingleChoiceItems(qualities, currentQualityIndex) { dialog, which ->
                selectedQuality = when (which) {
                    0 -> "360"
                    1 -> "720"
                    else -> "1080"
                }
                selectQualityButton.text = "Quality: ${qualities[which]}"
                dialog.dismiss()
                Log.d("AnimeDetailsFragment", "Selected quality: $selectedQuality")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Show dialog for selecting language
    private fun showLanguageSelectionDialog() {
        val languages = arrayOf("Japanese", "English")
        val languageCodes = arrayOf("jpn", "eng")
        val currentLanguageIndex = languageCodes.indexOf(selectedLanguage).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Language")
            .setSingleChoiceItems(languages, currentLanguageIndex) { dialog, which ->
                selectedLanguage = languageCodes[which]
                selectLanguageButton.text = "Language: ${languages[which]}"
                dialog.dismiss()
                Log.d("AnimeDetailsFragment", "Selected language: $selectedLanguage")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Create the custom episode loading UI programmatically
    private fun createCustomEpisodeLoadingUI() {
        // UI removed as requested
        // Core functionality remains intact
        try {
            // Create hidden inputs to maintain functionality without visible UI
            // These will be used by the existing methods that reference them

            // Create invisible EditText fields for input storage
            startEpisodeInput = EditText(requireContext()).apply {
                visibility = View.GONE
                setText("1")
            }

            endEpisodeInput = EditText(requireContext()).apply {
                visibility = View.GONE
                setText("12")
            }

            // Create invisible buttons to maintain references
            selectQualityButton = MaterialButton(requireContext()).apply {
                visibility = View.GONE
                text = "Quality: 1080p"
            }

            selectLanguageButton = MaterialButton(requireContext()).apply {
                visibility = View.GONE
                text = "Language: Japanese"
            }

            loadEpisodesButton = MaterialButton(requireContext()).apply {
                visibility = View.GONE
            }

            batchDownloadButton = MaterialButton(requireContext()).apply {
                visibility = View.GONE
            }

            // Create invisible card to maintain references
            episodeLoadingCard = CardView(requireContext()).apply {
                visibility = View.GONE
            }

            // Add the invisible views to the root to keep them in the view hierarchy
            val container = LinearLayout(requireContext()).apply {
                visibility = View.GONE
                addView(startEpisodeInput)
                addView(endEpisodeInput)
                addView(selectQualityButton)
                addView(selectLanguageButton)
                addView(loadEpisodesButton)
                addView(batchDownloadButton)
            }

            episodeLoadingCard.addView(container)

            // Add to root but keep invisible
            (binding.root as? ViewGroup)?.addView(episodeLoadingCard)

            Log.d("AnimeDetailsFragment", "Custom episode UI references maintained but UI removed")

        } catch (e: Exception) {
            Log.e("AnimeDetailsFragment", "Error setting up invisible references", e)
        }
    }

    // Create a simpler fallback UI if the fancy one fails
    private fun createSimpleFallbackUI() {
        // UI removed as requested
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Reset state when view is destroyed
        animeSessionId = null
    }

    /**
     * Shows a dialog with dynamically available language and quality options for streaming
     * Uses data from the API response to only show available options
     */
    private fun showDynamicQualityDialog(episode: Episode) {
        Log.d("AnimeDetailsFragment", "Starting quality/language selection for streaming episode ${episode.number}")

        // Fetch fresh data for this specific episode to get available qualities and languages
        animeViewModel.getEpisodeDetails(animeId = animeSessionId!!, episodeNumber = episode.number)

        // Observe episode data to get available options
        animeViewModel.episodes.observe(viewLifecycleOwner) { episodeResponse ->
            // Remove the observer to prevent multiple triggers
            animeViewModel.episodes.removeObservers(viewLifecycleOwner)

            try {
                val episodeData = episodeResponse.episodes[episode.number.toString()]

                if (episodeData != null) {
                    // Get available languages from the API response
                    val availableLanguages = episodeData.keys.toList()

                    if (availableLanguages.isEmpty()) {
                        showSnackbar("No language options available for this episode")
                        return@observe
                    }

                    // Create human-readable language names for display
                    val languageNames = availableLanguages.map { langCode ->
                        when (langCode) {
                            "jpn" -> "Japanese"
                            "eng" -> "English"
                            else -> langCode.capitalize()
                        }
                    }.toTypedArray()

                    // Show language selection dialog
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Select Language for Episode ${episode.number}")
                        .setSingleChoiceItems(languageNames, 0) { langDialog, langIndex ->
                            langDialog.dismiss()

                            // Get selected language
                            val selectedLang = availableLanguages[langIndex]

                            // Get available qualities for selected language
                            val availableQualities = episodeData[selectedLang]?.keys?.toList() ?: emptyList()

                            if (availableQualities.isEmpty()) {
                                showSnackbar("No quality options available for ${languageNames[langIndex]}")
                                return@setSingleChoiceItems
                            }

                            // Create human-readable quality names for display (e.g., 720 -> 720p)
                            val qualityNames = availableQualities.map { "${it}p" }.toTypedArray()

                            // Show quality selection dialog
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Select Quality for Episode ${episode.number}")
                                .setSingleChoiceItems(qualityNames, 0) { qualityDialog, qualityIndex ->
                                    qualityDialog.dismiss()

                                    // Get selected quality
                                    val selectedQuality = availableQualities[qualityIndex].toInt()

                                    Log.d("AnimeDetailsFragment", "Selected for streaming: Quality=${qualityNames[qualityIndex]}, Language=$selectedLang")

                                    // Now get the download link for streaming
                                    animeViewModel.getDownloadLink(
                                        animeId = animeSessionId!!,
                                        episodeNum = episode.number,
                                        lang = selectedLang,
                                        quality = selectedQuality,
                                        animeTitle = currentAnime?.getPreferredTitle() ?: "Unknown"
                                    )

                                    // Observe the download info for streaming
                                    animeViewModel.downloadInfo.observe(viewLifecycleOwner) { downloadResponse ->
                                        // Remove the observer to prevent multiple triggers
                                        animeViewModel.downloadInfo.removeObservers(viewLifecycleOwner)

                                        if (downloadResponse != null) {
                                            // Navigate to video player with the stream URL
                                            val bundle = Bundle().apply {
                                                putString("anime_id", animeId.toString())
                                                putString("anime_title", currentAnime?.getPreferredTitle())
                                                putInt("episode_number", episode.number)
                                                putString("episode_url", downloadResponse.download_link)
                                                putInt("selected_quality", selectedQuality)
                                                putString("selected_language", selectedLang)
                                            }
                                            findNavController().navigate(
                                                R.id.action_animeDetailsFragment_to_videoPlayerFragment,
                                                bundle
                                            )
                                        } else {
                                            showSnackbar("Error getting stream URL")
                                        }
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    // Fallback to default dialog if episode data is not available
                    showDefaultQualityDialog(episode)
                }
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error parsing episode data: ${e.message}", e)
                showDefaultQualityDialog(episode)
            }
        }
    }
}
