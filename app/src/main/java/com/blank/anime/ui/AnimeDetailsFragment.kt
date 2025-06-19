package com.blank.anime.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.blank.anime.R
import com.blank.anime.adapters.AnimeAdapter
import com.blank.anime.adapters.EpisodeAdapter
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
import java.text.NumberFormat
import java.util.Locale

class AnimeDetailsFragment : Fragment() {

    private var _binding: FragmentAnimeDetailsEnhancedBinding? = null
    private val binding get() = _binding!!

    private lateinit var animeViewModel: AnimeViewModel
    private lateinit var aniListViewModel: AniListViewModel
    private lateinit var storageManager: StorageManager

    private lateinit var recommendationsAdapter: AnimeAdapter
    private lateinit var episodesAdapter: EpisodeAdapter

    // Get animeId from arguments directly instead of using Safe Args
    private val animeId: Int
        get() {
            val idString = arguments?.getString("anime_id")
            return idString?.toIntOrNull() ?: 16498 // Fallback to AOT only if parsing fails
        }

    // Current anime details
    private var currentAnime: AniListMedia? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeDetailsEnhancedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModels and services
        aniListViewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]
        animeViewModel = ViewModelProvider(requireActivity())[AnimeViewModel::class.java]
        storageManager = StorageManager.getInstance(requireContext())

        // Set up toolbar
        setupToolbar()

        // Set up adapters
        setupAdapters()

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Load anime details
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
                // Launch video player
                showSnackbar("Playing episode ${episode.number}")
            },
            onDownloadClick = { episode ->
                // Start download
                showSnackbar("Downloading episode ${episode.number}")
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
            var episodeNumber = 1

            // Extract episodes from the nested map structure
            try {
                episodeResponse.episodes.forEach { (_, sources) ->
                    sources.forEach { (_, resolutions) ->
                        resolutions.forEach { (resolution, urls) ->
                            if (urls.isNotEmpty()) {
                                val url = urls[0]
                                episodesList.add(
                                    Episode(
                                        id = episodeNumber.toString(), // Use episode number as ID
                                        number = episodeNumber,
                                        title = "Episode $episodeNumber",
                                        description = "${currentAnime?.getPreferredTitle() ?: ""} - Episode $episodeNumber ($resolution)",
                                        thumbnail = null, // No thumbnail in current structure
                                        url = url,
                                        isDownloaded = storageManager.episodeExists(
                                            currentAnime?.getPreferredTitle() ?: "",
                                            episodeNumber
                                        )
                                    )
                                )
                                episodeNumber++
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AnimeDetailsFragment", "Error processing episodes: ${e.message}")
            }

            // Submit processed episodes to adapter
            episodesAdapter.submitList(episodesList)

            // Update progress seekbar max
            if (episodesList.isNotEmpty()) {
                binding.progressSeekBar.max = episodesList.size
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

    private fun loadAnimeDetails() {
        // First load episodes directly using the ID
        animeViewModel.getEpisodes(animeId.toString(), 1, 25)

        // Get the anime title if it was passed in the bundle
        val animeTitle = arguments?.getString("anime_title")

        // Use title for search if available, otherwise use ID
        val searchTerm = if (!animeTitle.isNullOrEmpty()) {
            animeTitle
        } else {
            // Fallback to known IDs if we have them
            when (animeId) {
                16498 -> "Attack on Titan"
                11757 -> "Sword Art Online"
                21 -> "One Piece"
                5114 -> "Fullmetal Alchemist: Brotherhood"
                1735 -> "Naruto"
                9253 -> "Steins;Gate"
                else -> "popular anime" // If we don't know the ID, search for popular anime
            }
        }

        // Search for the anime using title (more reliable than ID search)
        aniListViewModel.searchAnime(searchTerm) { results ->
            if (results.isNotEmpty()) {
                // Found anime in AniList
                val anime = results.first()
                updateAnimeDetails(anime)

                // Load recommendations for this anime
                aniListViewModel.searchRecommendations(anime.id)
            } else {
                // Try a more generic search if first attempt fails
                aniListViewModel.searchAnime("popular anime") { popularResults ->
                    if (popularResults.isNotEmpty()) {
                        val anime = popularResults.first()
                        updateAnimeDetails(anime)
                        showSnackbar("Couldn't find specific anime, showing a popular one instead")

                        // Load recommendations for this anime
                        aniListViewModel.searchRecommendations(anime.id)
                    } else {
                        showSnackbar("Couldn't load any anime details")
                    }
                }
            }
        }
    }

    private fun updateAnimeDetails(anime: AniListMedia) {
        currentAnime = anime

        // Set collapsing toolbar title
        binding.collapsingToolbar.title = anime.getPreferredTitle()

        // Load banner image
        anime.bannerImage?.let { url ->
            binding.animeBanner.load(url) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
            }
        }

        // Load poster image
        anime.coverImageLarge?.let { url ->
            binding.animePoster.load(url) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_foreground)
            }
        }

        // Set anime info
        binding.animeTitle.text = anime.getPreferredTitle()

        val formatYear = "${anime.format ?: "TV"} • ${anime.seasonYear} • " +
                if (anime.episodes > 0) "${anime.episodes} episodes" else "? episodes"
        binding.animeFormatYear.text = formatYear

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

        // Set description
        binding.animeDescription.text = anime.description
            ?.replace("<br>", "\n")
            ?: "No description available."

        // Set stats
        binding.animeScore.text = anime.getFormattedScore()
        binding.animePopularity.text = formatPopularity(anime.popularity)

        // Set progress
        if (anime.progress > 0) {
            binding.progressSeekBar.progress = anime.progress
            binding.progressText.text = "Episode ${anime.progress} of ${anime.episodes}"
        } else {
            binding.progressSeekBar.progress = 0
            binding.progressText.text = "Not started"
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

        // Show/hide progress tracking based on login status
        binding.progressCard.visibility =
            if (aniListViewModel.isLoggedIn()) View.VISIBLE else View.GONE
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
                        // First get the download link
                        animeViewModel.getDownloadLink(
                            animeId = animeId.toString(),
                            episodeNum = episode.number,
                            lang = "jpn", // Default language
                            quality = 720, // Default quality
                            animeTitle = currentAnime?.getPreferredTitle() ?: "Unknown"
                        )
                    }

                    // Observe the download info
                    animeViewModel.downloadInfo.observe(viewLifecycleOwner) { downloadResponse ->
                        if (downloadResponse != null) {
                            // Now download the episode using the download response
                            animeViewModel.downloadEpisodeViaStorageManager(requireContext(), downloadResponse)

                            // Remove the observer after initiating download
                            animeViewModel.downloadInfo.removeObserver { it }
                        }
                    }

                    showSnackbar("Starting download of ${selectedEpisodes.size} episode(s)...")
                } else {
                    showSnackbar("No episodes selected")
                }
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
    }
}
