package com.blank.anime.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.blank.anime.R
import com.blank.anime.adapters.AnimeAdapter
import com.blank.anime.databinding.FragmentAnimeListBinding
import com.blank.anime.model.AniListMedia
import com.blank.anime.model.AniListUserMediaStatus
import com.blank.anime.viewmodel.AniListViewModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout

class AnimeListFragment : Fragment() {

    private var _binding: FragmentAnimeListBinding? = null
    private val binding get() = _binding!!

    private lateinit var animeAdapter: AnimeAdapter
    private lateinit var aniListViewModel: AniListViewModel

    private var currentStatus: AniListUserMediaStatus = AniListUserMediaStatus.CURRENT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnimeListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel
        aniListViewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]

        // Set up toolbar
        setupToolbar()

        // Set up recycler view
        setupRecyclerView()

        // Set up tabs
        setupTabs()

        // Set up observers
        setupObservers()

        // Load initial data
        loadAnimeList(currentStatus)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.toolbar.title = "My Anime List"
    }

    private fun setupRecyclerView() {
        animeAdapter = AnimeAdapter { anime ->
            // Add detailed logging
            Log.d("AnimeListFragment", "Anime clicked: id=${anime.id}, title=${anime.getPreferredTitle()}, episodes=${anime.episodes}")

            // Navigate to anime details
            val bundle = Bundle().apply {
                putString("anime_id", anime.id.toString())
                putString("anime_title", anime.getPreferredTitle())
                putInt("total_episodes", anime.episodes)

                // Log bundle contents
                Log.d("AnimeListFragment", "Bundle created: anime_id=${getString("anime_id")}, anime_title=${getString("anime_title")}, total_episodes=${getInt("total_episodes")}")
            }
            Log.d("AnimeListFragment", "About to navigate to AnimeDetailsFragment")
            findNavController().navigate(R.id.action_animeListFragment_to_animeDetailsFragment, bundle)
            Log.d("AnimeListFragment", "Navigation action completed")
        }

        binding.animeRecyclerView.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = animeAdapter
        }
    }

    private fun setupTabs() {
        with(binding.statusTabLayout) {
            addTab(newTab().setText("Watching"))
            addTab(newTab().setText("Planning"))
            addTab(newTab().setText("Completed"))
            addTab(newTab().setText("On Hold"))
            addTab(newTab().setText("Dropped"))
            addTab(newTab().setText("Rewatching"))

            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> loadAnimeList(AniListUserMediaStatus.CURRENT)
                        1 -> loadAnimeList(AniListUserMediaStatus.PLANNING)
                        2 -> loadAnimeList(AniListUserMediaStatus.COMPLETED)
                        3 -> loadAnimeList(AniListUserMediaStatus.PAUSED)
                        4 -> loadAnimeList(AniListUserMediaStatus.DROPPED)
                        5 -> loadAnimeList(AniListUserMediaStatus.REPEATING)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
    }

    private fun setupObservers() {
        // Observe the user's anime list
        aniListViewModel.categoryAnimeList.observe(viewLifecycleOwner) { animeList ->
            animeAdapter.submitList(animeList)
            updateEmptyState(animeList)
        }

        // Observe error messages
        aniListViewModel.errorMessage.observe(viewLifecycleOwner) { errorMessage ->
            errorMessage?.let {
                showSnackbar(it)
                aniListViewModel.clearErrorMessage()
            }
        }

        // Observe loading state
        aniListViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun loadAnimeList(status: AniListUserMediaStatus) {
        currentStatus = status
        binding.toolbar.subtitle = getStatusTitle(status)

        if (!aniListViewModel.isLoggedIn()) {
            showLoginPrompt()
            return
        }

        aniListViewModel.getAnimeByStatus(status)
    }

    private fun getStatusTitle(status: AniListUserMediaStatus): String {
        return when (status) {
            AniListUserMediaStatus.CURRENT -> "Currently Watching"
            AniListUserMediaStatus.PLANNING -> "Plan to Watch"
            AniListUserMediaStatus.COMPLETED -> "Completed"
            AniListUserMediaStatus.PAUSED -> "On Hold"
            AniListUserMediaStatus.DROPPED -> "Dropped"
            AniListUserMediaStatus.REPEATING -> "Rewatching"
        }
    }

    private fun updateEmptyState(animeList: List<AniListMedia>?) {
        if (animeList.isNullOrEmpty()) {
            binding.emptyStateLayout.visibility = View.VISIBLE
            binding.emptyStateText.text = "No anime found in ${getStatusTitle(currentStatus).lowercase()}"
            binding.animeRecyclerView.visibility = View.GONE
        } else {
            binding.emptyStateLayout.visibility = View.GONE
            binding.animeRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun showLoginPrompt() {
        binding.emptyStateLayout.visibility = View.VISIBLE
        binding.emptyStateText.text = "Please login to AniList to view your anime lists"
        binding.animeRecyclerView.visibility = View.GONE

        binding.loginButton.apply {
            visibility = View.VISIBLE
            text = "Login to AniList"
            setOnClickListener {
                findNavController().navigate(R.id.action_animeListFragment_to_homeFragment)
            }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
