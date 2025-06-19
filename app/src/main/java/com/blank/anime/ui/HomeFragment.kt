package com.blank.anime.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.CircleCropTransformation
import com.blank.anime.R
import com.blank.anime.adapters.AnimeAdapter
import com.blank.anime.databinding.FragmentHomeBinding
import com.blank.anime.model.AniListMedia
import com.blank.anime.model.AniListRecommendation
import com.blank.anime.model.AniListUser
import com.blank.anime.viewmodel.AniListViewModel
import com.google.android.material.snackbar.Snackbar
import java.text.NumberFormat
import java.util.Locale

/**
 * HomeFragment displaying AniList data in a beautiful layout with horizontal carousels
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AniListViewModel

    private lateinit var watchingAdapter: AnimeAdapter
    private lateinit var trendingAdapter: AnimeAdapter
    private lateinit var recommendationsAdapter: AnimeAdapter
    private lateinit var planToWatchAdapter: AnimeAdapter
    private lateinit var popularSeasonalAdapter: AnimeAdapter
    private lateinit var popularAllTimeAdapter: AnimeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel
        viewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]

        setupAdapters()
        setupObservers()
        setupListeners()

        // Handle potential OAuth redirect
        handleIntent(requireActivity().intent)
    }

    override fun onResume() {
        super.onResume()
        updateLoginUI()
    }

    private fun setupAdapters() {
        // Set up adapters with click listeners
        watchingAdapter = AnimeAdapter { navigateToAnimeDetails(it) }
        trendingAdapter = AnimeAdapter { navigateToAnimeDetails(it) }
        recommendationsAdapter = AnimeAdapter { navigateToAnimeDetails(it) }
        planToWatchAdapter = AnimeAdapter { navigateToAnimeDetails(it) }
        popularSeasonalAdapter = AnimeAdapter { navigateToAnimeDetails(it) }
        popularAllTimeAdapter = AnimeAdapter { navigateToAnimeDetails(it) }

        // Configure recycler views
        binding.currentlyWatchingRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = watchingAdapter
        }

        binding.trendingRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = trendingAdapter
        }

        binding.recommendationsRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recommendationsAdapter
        }

        binding.planToWatchRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = planToWatchAdapter
        }

        binding.popularThisSeasonRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = popularSeasonalAdapter
        }

        binding.allTimePopularRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = popularAllTimeAdapter
        }
    }

    private fun setupObservers() {
        // Observe user profile
        viewModel.userProfile.observe(viewLifecycleOwner) { user ->
            updateUserProfile(user)
        }

        // Observe watching anime
        viewModel.watchingAnime.observe(viewLifecycleOwner) { animeList ->
            updateWatchingAnime(animeList)
        }

        // Observe plan to watch anime
        viewModel.planToWatchAnime.observe(viewLifecycleOwner) { animeList ->
            updatePlanToWatchAnime(animeList)
        }

        // Observe trending anime
        viewModel.trendingAnime.observe(viewLifecycleOwner) { animeList ->
            updateTrendingAnime(animeList)
        }

        // Observe popular seasonal anime
        viewModel.popularSeasonalAnime.observe(viewLifecycleOwner) { animeList ->
            updatePopularSeasonalAnime(animeList)
        }

        // Observe all-time popular anime
        viewModel.popularAllTimeAnime.observe(viewLifecycleOwner) { animeList ->
            updatePopularAllTimeAnime(animeList)
        }

        // Observe recommendations
        viewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            updateRecommendations(recommendations)
        }

        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // Observe error messages
        viewModel.errorMessage.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                showSnackbar(it)
                viewModel.clearErrorMessage()
            }
        }
    }

    private fun setupListeners() {
        // Login button click
        binding.loginButton.setOnClickListener {
            viewModel.login()
        }

        // Logout button click
        binding.logoutButton.setOnClickListener {
            viewModel.logout()
            updateLoginUI()
        }

        // My Anime Lists button click
        binding.myAnimeListsButton.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_animeListFragment)
        }

        // Settings FAB click
        binding.settingsFab.setOnClickListener {
            navigateToSettings()
        }

        // Search input
        binding.searchInput.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                }
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun updateLoginUI() {
        val isLoggedIn = viewModel.isLoggedIn()
        binding.loggedInLayout.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        binding.loginPromptLayout.visibility = if (isLoggedIn) View.GONE else View.VISIBLE
    }

    private fun updateUserProfile(user: AniListUser?) {
        if (user == null) {
            binding.loggedInLayout.visibility = View.GONE
            binding.loginPromptLayout.visibility = View.VISIBLE
            return
        }

        binding.loggedInLayout.visibility = View.VISIBLE
        binding.loginPromptLayout.visibility = View.GONE

        // Set user name
        binding.username.text = user.name

        // Format and set stats
        val animeCountText = "${user.animeCount} anime • ${user.episodesWatched} episodes"
        binding.animeStats.text = animeCountText

        val minutesFormat = NumberFormat.getNumberInstance(Locale.getDefault())
        val minutesText = "${minutesFormat.format(user.minutesWatched)} minutes watched"
        binding.minutesWatched.text = minutesText

        // Load avatar with Coil
        user.avatarUrl?.let { url ->
            binding.userAvatar.load(url) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_foreground)
                transformations(CircleCropTransformation())
            }
        }
    }

    private fun updateWatchingAnime(animeList: List<AniListMedia>) {
        val isVisible = animeList.isNotEmpty()
        binding.watchingTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.currentlyWatchingRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        watchingAdapter.submitList(animeList)
    }

    private fun updatePlanToWatchAnime(animeList: List<AniListMedia>) {
        val isVisible = animeList.isNotEmpty()
        binding.planToWatchTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.planToWatchRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        planToWatchAdapter.submitList(animeList)
    }

    private fun updateTrendingAnime(animeList: List<AniListMedia>) {
        val isVisible = animeList.isNotEmpty()
        binding.trendingTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.trendingRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        trendingAdapter.submitList(animeList)
    }

    private fun updatePopularSeasonalAnime(animeList: List<AniListMedia>) {
        val isVisible = animeList.isNotEmpty()
        binding.popularThisSeasonTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.popularThisSeasonRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        popularSeasonalAdapter.submitList(animeList)
    }

    private fun updatePopularAllTimeAnime(animeList: List<AniListMedia>) {
        val isVisible = animeList.isNotEmpty()
        binding.allTimePopularTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.allTimePopularRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        popularAllTimeAdapter.submitList(animeList)
    }

    private fun updateRecommendations(recommendations: List<AniListRecommendation>) {
        val animeList = recommendations.map { it.media }
        val isVisible = animeList.isNotEmpty()

        binding.recommendationsTitle.visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.recommendationsRecycler.visibility = if (isVisible) View.VISIBLE else View.GONE

        recommendationsAdapter.submitList(animeList)
    }

    private fun navigateToAnimeDetails(anime: AniListMedia) {
        // TODO: Navigate to anime details using NavController
        // findNavController().navigate(
        //     HomeFragmentDirections.actionHomeFragmentToAnimeDetailsFragment(anime.id)
        // )
        showSnackbar("Viewing anime: ${anime.getPreferredTitle()}")
    }

    private fun navigateToSettings() {
        // TODO: Navigate to settings using NavController
        // findNavController().navigate(
        //     HomeFragmentDirections.actionHomeFragmentToSettingsFragment()
        // )
        showSnackbar("Settings coming soon")
    }

    private fun performSearch(query: String) {
        // TODO: Navigate to search results using NavController
        // findNavController().navigate(
        //     HomeFragmentDirections.actionHomeFragmentToSearchResultsFragment(query)
        // )
        showSnackbar("Searching for: $query")
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.data != null && intent.data.toString().startsWith("com.blank.anime://auth-callback")) {
            viewModel.handleAuthIntent(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
