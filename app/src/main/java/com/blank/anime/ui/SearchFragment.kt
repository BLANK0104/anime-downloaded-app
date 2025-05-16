package com.blank.anime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.MainActivity
import com.blank.anime.R
import com.blank.anime.adapter.AnimeAdapter
import com.blank.anime.model.AnimeSearchResult
import com.blank.anime.viewmodel.AnimeViewModel
import com.google.android.material.textfield.TextInputEditText

class SearchFragment : Fragment() {

    private lateinit var viewModel: AnimeViewModel
    private lateinit var searchInput: TextInputEditText
    private lateinit var searchButton: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var animeAdapter: AnimeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        searchInput = view.findViewById(R.id.search_input)
        searchButton = view.findViewById(R.id.search_button)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressBar = view.findViewById(R.id.progress_bar)

        // Initialize ViewModel
        viewModel = ViewModelProvider(requireActivity())[AnimeViewModel::class.java]

        // Setup RecyclerView
        setupRecyclerView()

        // Setup search functionality
        setupSearchActions()

        // Observe anime search results
        observeViewModel()
    }

    private fun setupRecyclerView() {
        animeAdapter = AnimeAdapter { anime ->
            navigateToAnimeDetails(anime)
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = animeAdapter
        }
    }

    private fun setupSearchActions() {
        searchButton.setOnClickListener {
            performSearch()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a search term", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide keyboard
        hideKeyboard()

        // Show loading indicator
        progressBar.visibility = View.VISIBLE

        // Perform search via ViewModel
        viewModel.searchAnime(query)
    }

    private fun hideKeyboard() {
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)
    }

    private fun observeViewModel() {
        viewModel.searchResults.observe(viewLifecycleOwner) { result ->
            progressBar.visibility = View.GONE

            when (result) {
                is AnimeViewModel.Result.Success -> {
                    animeAdapter.submitList(result.data)
                    if (result.data.isEmpty()) {
                        Toast.makeText(requireContext(), "No results found", Toast.LENGTH_SHORT).show()
                    }
                }
                is AnimeViewModel.Result.Error -> {
                    Toast.makeText(requireContext(), "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                }
                is AnimeViewModel.Result.Loading -> {
                    progressBar.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun navigateToAnimeDetails(anime: AnimeSearchResult) {
        // Pass all necessary data to the details fragment
        val bundle = Bundle().apply {
            putString("anime_id", anime.session_id)  // Use session_id as the anime_id
            putString("anime_title", anime.title)    // Pass the actual title
            putInt("total_episodes", anime.episodes) // Pass the actual episode count
        }
        findNavController().navigate(R.id.action_searchFragment_to_animeDetailsFragment, bundle)
    }
}