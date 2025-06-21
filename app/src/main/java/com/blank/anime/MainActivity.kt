package com.blank.anime

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.content.Intent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.blank.anime.ui.StoragePermissionDialog
import com.blank.anime.ui.VideoPlayerFragment
import com.blank.anime.ui.WelcomeDialog
import com.blank.anime.utils.StorageManager
import com.blank.anime.viewmodel.AnimeViewModel
import com.blank.anime.viewmodel.AniListViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import android.os.Handler
import android.os.Looper

@UnstableApi
class MainActivity : AppCompatActivity(), VideoPlayerFragment.EpisodeNavigationListener {

    lateinit var viewModel: AnimeViewModel
    lateinit var aniListViewModel: AniListViewModel
    private lateinit var storageManager: StorageManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Force portrait mode for the app by default
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize ViewModels
        viewModel = ViewModelProvider(this)[AnimeViewModel::class.java]
        aniListViewModel = ViewModelProvider(this)[AniListViewModel::class.java]

        // Initialize Storage Manager
        storageManager = StorageManager.getInstance(this)

        // Check if storage directory is set, if not show permission dialog
        if (!storageManager.hasStorageDirectorySet()) {
            showStoragePermissionDialog()
        }

        // Set up navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navController)

        // Set up fragment result listeners for episode navigation
        setupEpisodeNavigationResultListeners()

        // Handle possible AniList auth callback
        handleIntent(intent)

        // Set up observers
        setupObservers()

        // Show welcome dialog if first launch
        checkFirstLaunch()
    }

    private fun checkFirstLaunch() {
        // Check if this is the first app launch
        if (WelcomeDialog.isFirstLaunch(this)) {
            // Show welcome dialog with AniList login option
            val welcomeDialog = WelcomeDialog.newInstance()
            welcomeDialog.setOnDismissListener {
                // After welcome dialog is dismissed, check if storage is set
                if (!storageManager.hasStorageDirectorySet()) {
                    showStoragePermissionDialog()
                }
            }
            welcomeDialog.show(supportFragmentManager, WelcomeDialog.TAG)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        Log.d("MainActivity", "onNewIntent called with data: ${intent?.data}")
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Check if the intent contains data from an AniList auth callback
        if (intent?.data != null) {
            Log.d("MainActivity", "Processing intent with data: ${intent.data}")
            if (intent.data.toString().contains("auth") && intent.data.toString().contains("callback")) {
                Log.d("MainActivity", "Detected auth callback")
                // Handle AniList authentication response
                aniListViewModel.handleAuthIntent(intent)
                // Show toast to inform user
                Toast.makeText(this, "Processing AniList login...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupObservers() {
        // Observe error messages from AniListViewModel
        aniListViewModel.errorMessage.observe(this) { errorMessage ->
            errorMessage?.let {
                Snackbar.make(findViewById(R.id.main), it, Snackbar.LENGTH_LONG).show()
                aniListViewModel.clearErrorMessage()
            }
        }
    }

    private fun showStoragePermissionDialog() {
        val dialog = StoragePermissionDialog.newInstance()
        dialog.setOnPermissionGrantedListener {
            // Permission was granted, continue with app initialization
            Log.d("MainActivity", "Storage permission granted")
        }
        dialog.show(supportFragmentManager, StoragePermissionDialog.TAG)
    }

    private fun setupEpisodeNavigationResultListeners() {
        supportFragmentManager.setFragmentResultListener("next_episode_request", this) { _, bundle ->
            val animeTitle = bundle.getString("anime_title") ?: return@setFragmentResultListener
            val episodeNumber = bundle.getInt("episode_number")

            Log.d("MainActivity", "Next episode request received: $animeTitle, ep $episodeNumber")
            loadEpisodeByNumber(animeTitle, episodeNumber)
        }

        supportFragmentManager.setFragmentResultListener("previous_episode_request", this) { _, bundle ->
            val animeTitle = bundle.getString("anime_title") ?: return@setFragmentResultListener
            val episodeNumber = bundle.getInt("episode_number")

            Log.d("MainActivity", "Previous episode request received: $animeTitle, ep $episodeNumber")
            loadEpisodeByNumber(animeTitle, episodeNumber)
        }
    }

    // VideoPlayerFragment.EpisodeNavigationListener implementation
    override fun onNextEpisode(currentAnime: String, currentEpisode: Int) {
        Log.d("MainActivity", "Navigation to next episode: $currentAnime, from ep $currentEpisode")
        loadEpisodeByNumber(currentAnime, currentEpisode + 1)
    }

    override fun onPreviousEpisode(currentAnime: String, currentEpisode: Int) {
        if (currentEpisode <= 1) {
            Log.d("MainActivity", "Cannot navigate to previous episode: already at first episode")
            return
        }

        Log.d("MainActivity", "Navigation to previous episode: $currentAnime, from ep $currentEpisode")
        loadEpisodeByNumber(currentAnime, currentEpisode - 1)
    }

    // Helper method to find and play an episode
    private fun loadEpisodeByNumber(animeTitle: String, episodeNumber: Int, streamingUrl: String? = null) {
        try {
            // Ensure we're not in the middle of another fragment transaction
            if (supportFragmentManager.isStateSaved) {
                Log.d("MainActivity", "Fragment manager state saved, cannot perform transaction")
                return
            }

            // Find the current player fragment and release it
            val currentFragment = supportFragmentManager.findFragmentByTag("video_player")
            if (currentFragment is VideoPlayerFragment) {
                currentFragment.releasePlayer()
            }

            // First check if we have a local copy of the episode
            val episodeFile = storageManager.findEpisode(animeTitle, episodeNumber)
            val episodeTitle = "Episode $episodeNumber"

            // Check if we have a valid source (local file or streaming URL)
            if (episodeFile == null && streamingUrl.isNullOrEmpty()) {
                // No valid source available, show error message
                Log.e("MainActivity", "No local file or streaming URL available for: $animeTitle, Episode $episodeNumber")
                Toast.makeText(this, "Error: No video source available for this episode.", Toast.LENGTH_LONG).show()
                return
            }

            // Create and show the player fragment (either with local URI or streaming URL)
            val playerFragment = if (episodeFile != null) {
                // Use local file if available
                Log.d("MainActivity", "Found local episode: $animeTitle, Episode $episodeNumber")
                VideoPlayerFragment.newInstance(
                    episodeFile.uri,
                    episodeTitle,
                    animeTitle,
                    episodeNumber
                )
            } else {
                // Use streaming URL
                Log.d("MainActivity", "No local episode found, streaming: $animeTitle, Episode $episodeNumber, URL: $streamingUrl")
                val streamingUri = Uri.parse(streamingUrl)
                VideoPlayerFragment.newInstance(
                    streamingUri,
                    episodeTitle,
                    animeTitle,
                    episodeNumber
                )
            }

            // Set the navigation listener explicitly
            playerFragment.setEpisodeNavigationListener(this)

            // Use commit() instead of commitNow() when adding to back stack
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, playerFragment, "video_player")
                .setReorderingAllowed(true)
                .addToBackStack(null)
                .commit()

            // Execute pending transactions to apply the change immediately
            supportFragmentManager.executePendingTransactions()

            Log.d("MainActivity", "Started playing: $animeTitle, Episode $episodeNumber")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading episode: ${e.message}", e)
            Toast.makeText(this, "Error loading episode: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Controls the visibility of the bottom navigation bar
     * @param show true to show the navigation bar, false to hide it
     */
    fun setBottomNavigationVisibility(show: Boolean) {
        Log.d("MainActivity", "Setting bottom navigation visibility: ${if (show) "VISIBLE" else "GONE"}")
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Changes screen orientation for video playback
     * @param orientation the desired orientation, use ActivityInfo constants
     */
    fun setScreenOrientation(orientation: Int) {
        Log.d("MainActivity", "Orientation change requested to: $orientation")

        // Override the default portrait orientation when needed
        if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            Log.d("MainActivity", "Applying landscape orientation for video playback")
        }

        // Force orientation change
        requestedOrientation = orientation

        // Log the orientation change for debugging
        Log.d("MainActivity", "Screen orientation changed to: $orientation")
    }
}