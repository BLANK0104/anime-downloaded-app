package com.blank.anime.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import coil.load
import coil.transform.CircleCropTransformation
import com.blank.anime.R
import com.blank.anime.auth.AniListAuthManager
import com.blank.anime.databinding.FragmentSettingsBinding
import com.blank.anime.utils.StorageManager
import com.blank.anime.viewmodel.AniListViewModel
import com.google.android.material.snackbar.Snackbar

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var authManager: AniListAuthManager
    private lateinit var storageManager: StorageManager
    private lateinit var viewModel: AniListViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize services and ViewModel
        authManager = AniListAuthManager.getInstance(requireContext())
        storageManager = StorageManager.getInstance(requireContext())
        viewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]

        setupUI()
        setupObservers()
    }

    private fun setupUI() {
        // Watch time input setup
        binding.watchTimeInput.editText?.setText(storageManager.getWatchTimeThreshold().toString())

        // Login button setup
        binding.loginButton.setOnClickListener {
            viewModel.login()
            showSnackbar("Launching AniList login...")
        }

        // Logout button setup
        binding.logoutButton.setOnClickListener {
            authManager.logout()
            updateUserProfile()
            showSnackbar("Logged out successfully")
        }

        // Save watch time threshold
        binding.watchTimeInput.editText?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val threshold = binding.watchTimeInput.editText?.text.toString().toIntOrNull() ?: 0
                storageManager.saveWatchTimeThreshold(threshold)
                showSnackbar("Watch time threshold saved")
            }
        }

        updateUserProfile()
    }

    private fun setupObservers() {
        viewModel.userProfile.observe(viewLifecycleOwner) { profile ->
            updateUserProfile()
        }
    }

    private fun updateUserProfile() {
        if (authManager.isLoggedIn()) {
            // User is logged in, show profile info and hide login button
            viewModel.userProfile.value?.let { profile ->
                binding.username.text = profile.name
                binding.userStatus.text = "Logged in"
                binding.userAvatar.load(profile.avatarUrl) {
                    transformations(CircleCropTransformation())
                    crossfade(true)
                }
            }
            binding.loginButton.visibility = View.GONE
            binding.logoutButton.visibility = View.VISIBLE
        } else {
            // User is not logged in, show login prompt and hide logout button
            binding.username.text = "Not logged in"
            binding.userStatus.text = "Please login to AniList"
            binding.userAvatar.setImageDrawable(null)
            binding.loginButton.visibility = View.VISIBLE
            binding.logoutButton.visibility = View.GONE
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
