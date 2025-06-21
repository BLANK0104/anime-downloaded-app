package com.blank.anime.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.blank.anime.MainActivity
import com.blank.anime.R
import com.blank.anime.databinding.FragmentVideoPlayerBinding
import kotlin.math.abs

@UnstableApi
class VideoPlayerFragment : Fragment(), GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener, View.OnTouchListener {

    // Media3 player state constants
    private val PLAYER_STATE_IDLE = Player.STATE_IDLE
    private val PLAYER_STATE_BUFFERING = Player.STATE_BUFFERING
    private val PLAYER_STATE_READY = Player.STATE_READY
    private val PLAYER_STATE_ENDED = Player.STATE_ENDED
    private val PLAYER_STATE_ERROR = 4 // Explicitly define ERROR state value

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

    private var animeTitle: String? = null
    private var episodeNumber: Int = 0

    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var playbackPosition = 0L

    private var videoUri: Uri? = null
    private var videoTitle: String? = null

    private lateinit var gestureDetector: GestureDetector
    private lateinit var audioManager: AudioManager
    private var maxVolume = 0
    private var originalBrightness = 0f
    private var isOrientationLocked = false
    private var controlsVisible = true
    private val controlsHideHandler = Handler(Looper.getMainLooper())

    // Add preference key for video orientation
    private val PREF_VIDEO_ORIENTATION = "video_player_orientation"
    private val PREF_ORIENTATION_LOCKED = "video_orientation_locked"
    private var currentOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    // Constants for gestures
    private val SEEK_AMOUNT_DOUBLE_TAP = 5000L // 5 seconds
    private val SEEK_AMOUNT_SKIP_BUTTON = 5000L // 5 seconds
    private val SEEK_AMOUNT_LONG_SKIP = 85000L // 85 seconds

    // Variables for brightness and volume control
    private var lastBrightnessY = 0f
    private var lastVolumeY = 0f

    // Progress tracking
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressBar()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // Episode navigation listener
    interface EpisodeNavigationListener {
        fun onNextEpisode(currentAnime: String, currentEpisode: Int)
        fun onPreviousEpisode(currentAnime: String, currentEpisode: Int)
    }

    private var episodeNavigationListener: EpisodeNavigationListener? = null

    // Add method to explicitly set the listener
    fun setEpisodeNavigationListener(listener: EpisodeNavigationListener) {
        episodeNavigationListener = listener
    }

    companion object {
        private const val ARG_VIDEO_URI = "video_uri"
        private const val ARG_VIDEO_TITLE = "video_title"
        private const val ARG_ANIME_TITLE = "anime_title"
        private const val ARG_EPISODE_NUMBER = "episode_number"
        private const val WATCH_PROGRESS_PREFIX = "watch_progress_"

        fun newInstance(videoUri: Uri, videoTitle: String): VideoPlayerFragment {
            return VideoPlayerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_VIDEO_URI, videoUri)
                    putString(ARG_VIDEO_TITLE, videoTitle)
                }
            }
        }

        fun newInstance(videoUri: Uri, videoTitle: String, animeTitle: String, episodeNumber: Int): VideoPlayerFragment {
            return VideoPlayerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_VIDEO_URI, videoUri)
                    putString(ARG_VIDEO_TITLE, videoTitle)
                    putString(ARG_ANIME_TITLE, animeTitle)
                    putInt(ARG_EPISODE_NUMBER, episodeNumber)
                }
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)

        // Try to set the navigation listener automatically if the parent implements it
        if (context is EpisodeNavigationListener) {
            episodeNavigationListener = context
        } else if (parentFragment is EpisodeNavigationListener) {
            episodeNavigationListener = parentFragment as EpisodeNavigationListener
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hide bottom navigation when video player is shown
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.GONE

        arguments?.let {
            videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_VIDEO_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(ARG_VIDEO_URI)
            }
            videoTitle = it.getString(ARG_VIDEO_TITLE)
            animeTitle = it.getString(ARG_ANIME_TITLE)
            episodeNumber = it.getInt(ARG_EPISODE_NUMBER, 0)
        }

        // Load saved watch position if available
        loadWatchProgress()

        // Load saved orientation preferences
        val prefs = requireContext().getSharedPreferences("AnimePrefs", Context.MODE_PRIVATE)
        isOrientationLocked = prefs.getBoolean(PREF_ORIENTATION_LOCKED, false)

        // Force landscape mode for video playback
        (activity as? MainActivity)?.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        // Store current orientation to manage state
        currentOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Get audio service for volume control
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        // Get current brightness
        try {
            originalBrightness = Settings.System.getFloat(
                requireContext().contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ) / 255f  // Normalize to 0-1 range
        } catch (e: Exception) {
            originalBrightness = 0.5f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hide system UI (status and navigation bars)
        hideSystemUI()

        // Set video title
        binding.videoTitle.text = videoTitle

        // Initialize gesture detector
        gestureDetector = GestureDetector(requireContext(), this)
        gestureDetector.setOnDoubleTapListener(this)

        // Set up touch listeners
        binding.playerView.setOnTouchListener(this)
        binding.brightnessControlArea.setOnTouchListener(this)
        binding.volumeControlArea.setOnTouchListener(this)

        // Set back button click listener
        binding.backButton.setOnClickListener {
            // Save watch progress if needed before closing
            saveWatchProgress()

            // Release player resources before dismissing to avoid resource leaks
            releasePlayer()

            // Set orientation to portrait mode before removing the fragment
            try {
                (activity as? MainActivity)?.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            } catch (e: Exception) {
                Log.e("VideoPlayerFragment", "Error setting orientation: ${e.message}", e)
            }

            // Remove this fragment from the container
            requireActivity().supportFragmentManager.beginTransaction()
                .remove(this)
                .commit()
        }

        // Set play/pause button click listener
        binding.playPauseButton.setOnClickListener {
            togglePlayback()
        }

        // Set rewind button click listener
        binding.rewindButton.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition - SEEK_AMOUNT_SKIP_BUTTON).coerceAtLeast(0)
                it.seekTo(newPosition)
                showToast("⏪ 5 seconds")
            }
        }

        // Set skip button click listener
        binding.skipForwardButton.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition + SEEK_AMOUNT_SKIP_BUTTON)
                    .coerceAtMost(it.duration)
                it.seekTo(newPosition)
                showToast("⏩ 5 seconds")
            }
        }

        // Set long skip button (85 seconds) click listener
        binding.longSkipButton.setOnClickListener {
            player?.let {
                val newPosition = (it.currentPosition + SEEK_AMOUNT_LONG_SKIP)
                    .coerceAtMost(it.duration)
                it.seekTo(newPosition)
                showToast("⏩ 85 seconds")
            }
        }

        // Set up episode navigation buttons
        setupEpisodeNavigationButtons()

        // Set orientation lock button click listener with improved functionality
        binding.orientationLockButton.setOnClickListener {
            try {
                when (currentOrientation) {
                    // If in landscape locked mode, switch to portrait locked mode
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> {
                        setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                        isOrientationLocked = true
                        showToast("Switched to portrait mode")
                        Log.d("VideoPlayerFragment", "Switched to PORTRAIT mode")
                    }
                    // If in portrait locked mode, switch to auto-rotate mode
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> {
                        setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
                        isOrientationLocked = false
                        showToast("Auto-rotation enabled")
                        Log.d("VideoPlayerFragment", "Switched to SENSOR (auto-rotate) mode")
                    }
                    // If in landscape sensor mode, switch to landscape locked mode
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> {
                        setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                        isOrientationLocked = true
                        showToast("Locked to landscape mode")
                        Log.d("VideoPlayerFragment", "Switched to fixed LANDSCAPE mode")
                    }
                    // For any other mode (auto-rotate or unspecified), switch to landscape sensor mode
                    else -> {
                        setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                        isOrientationLocked = false
                        showToast("Using landscape auto-rotation")
                        Log.d("VideoPlayerFragment", "Switched to SENSOR_LANDSCAPE mode")
                    }
                }

                // Save orientation lock state
                val prefs = requireContext().getSharedPreferences("AnimePrefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean(PREF_ORIENTATION_LOCKED, isOrientationLocked).apply()

            } catch (e: Exception) {
                Log.e("VideoPlayerFragment", "Failed to change orientation: ${e.message}", e)
                showToast("Failed to change orientation")
            }
        }

        // Set up seekbar listener
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var isSeeking = false

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let {
                        // Calculate position based on the progress percentage
                        val newPosition = progress.toLong() * it.duration / 100
                        binding.currentTime.text = formatTime(newPosition)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeeking = true
                cancelHideControlsTask()
                // Pause updates while user is seeking to avoid jumpy behavior
                stopProgressUpdates()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player?.let {
                    // Calculate position based on the progress percentage
                    val newPosition = seekBar.progress.toLong() * it.duration / 100
                    Log.d("VideoPlayerFragment", "Seeking to position: $newPosition (${seekBar.progress}%)")
                    it.seekTo(newPosition)
                }
                isSeeking = false
                // Resume progress updates
                startProgressUpdates()
                scheduleHideControls()
            }
        })

        // Schedule initial hiding of controls
        scheduleHideControls()
    }

    private fun setupEpisodeNavigationButtons() {
        // Show/hide episode navigation buttons based on whether anime info is available
        if (animeTitle == null || episodeNumber == 0) {
            binding.nextEpisodeButton.visibility = View.GONE
            binding.previousEpisodeButton.visibility = View.GONE
            return
        }

        binding.nextEpisodeButton.visibility = View.VISIBLE
        binding.previousEpisodeButton.visibility = View.VISIBLE

        // Disable previous episode button if we're at episode 1
        binding.previousEpisodeButton.isEnabled = episodeNumber > 1

        // Set next episode button click listener
        binding.nextEpisodeButton.setOnClickListener {
            saveWatchProgress()
            animeTitle?.let { title ->
                // Handle navigation directly in the fragment
                handleNextEpisode(title, episodeNumber)
            }
        }

        // Set previous episode button click listener
        binding.previousEpisodeButton.setOnClickListener {
            saveWatchProgress()
            animeTitle?.let { title ->
                if (episodeNumber > 1) {
                    // Handle navigation directly in the fragment
                    handlePreviousEpisode(title, episodeNumber)
                } else {
                    showToast("This is the first episode")
                }
            }
        }
    }

    // Internal navigation handlers
    private fun handleNextEpisode(currentAnime: String, currentEpisode: Int) {
        Log.d("VideoPlayerFragment", "Handling next episode internally: $currentAnime, ep ${currentEpisode+1}")

        // First try using the listener if available
        if (episodeNavigationListener != null) {
            episodeNavigationListener?.onNextEpisode(currentAnime, currentEpisode)
            return
        }

        // Otherwise, try to navigate internally
        try {
            // Get the parent activity
            val parentActivity = activity
            if (parentActivity != null) {
                // Create an intent or bundle with the next episode info
                val args = Bundle().apply {
                    putString("anime_title", currentAnime)
                    putInt("episode_number", currentEpisode + 1)
                }

                // Notify the parent activity to handle the navigation
                parentActivity.supportFragmentManager.setFragmentResult("next_episode_request", args)

                // Show a confirmation toast
                showToast("Loading next episode...")
            } else {
                showToast("Unable to navigate to next episode")
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerFragment", "Error navigating to next episode", e)
            showToast("Error loading next episode")
        }
    }

    private fun handlePreviousEpisode(currentAnime: String, currentEpisode: Int) {
        Log.d("VideoPlayerFragment", "Handling previous episode internally: $currentAnime, ep ${currentEpisode-1}")

        // First try using the listener if available
        if (episodeNavigationListener != null) {
            episodeNavigationListener?.onPreviousEpisode(currentAnime, currentEpisode)
            return
        }

        // Otherwise, try to navigate internally
        try {
            // Get the parent activity
            val parentActivity = activity
            if (parentActivity != null && currentEpisode > 1) {
                // Create an intent or bundle with the previous episode info
                val args = Bundle().apply {
                    putString("anime_title", currentAnime)
                    putInt("episode_number", currentEpisode - 1)
                }

                // Notify the parent activity to handle the navigation
                parentActivity.supportFragmentManager.setFragmentResult("previous_episode_request", args)

                // Show a confirmation toast
                showToast("Loading previous episode...")
            } else {
                showToast("Unable to navigate to previous episode")
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerFragment", "Error navigating to previous episode", e)
            showToast("Error loading previous episode")
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()

        // Ensure we're in landscape mode even after returning from another app or screen
        (activity as? MainActivity)?.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        hideSystemUI() // Ensure system UI stays hidden when returning to the fragment
        if (player != null) {
            startProgressUpdates()
        }
    }


    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
        saveWatchProgress()
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdates()
        saveWatchProgress()
        releasePlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cancelHideControlsTask() // Cancel pending hide controls tasks
        stopProgressUpdates()
        showSystemUI() // Restore system UI
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHideControlsTask() // Ensure all handlers are cleared

        // Reset to portrait mode when leaving the video player
        try {
            (activity as? MainActivity)?.setScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        } catch (e: Exception) {
            Log.e("VideoPlayerFragment", "Error resetting orientation", e)
        }

        // Show the bottom navigation again when leaving the player
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.VISIBLE
    }

    private fun initializePlayer() {
        Log.d("VideoPlayerFragment", "Initializing player with URI: $videoUri")

        player = ExoPlayer.Builder(requireContext())
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer

                // Set up media item
                videoUri?.let { uri ->
                    // Check if this is a pahe.win URL that needs resolving
                    if (uri.toString().contains("pahe.win")) {
                        // Show loading indicator
                        binding.loadingIndicator.visibility = View.VISIBLE

                        // Start a background thread to resolve the actual video URL
                        Thread {
                            try {
                                Log.d("VideoPlayerFragment", "Resolving redirect URL: $uri")
                                val resolvedUrl = resolveRedirectUrl(uri.toString())
                                Log.d("VideoPlayerFragment", "Resolved URL: $resolvedUrl")

                                // Update the player on the main thread
                                activity?.runOnUiThread {
                                    if (isAdded) {  // Make sure fragment is still attached
                                        val mediaItem = MediaItem.fromUri(resolvedUrl)
                                        exoPlayer.setMediaItem(mediaItem)
                                        exoPlayer.playWhenReady = playWhenReady
                                        exoPlayer.seekTo(playbackPosition)
                                        exoPlayer.prepare()
                                        binding.loadingIndicator.visibility = View.GONE
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("VideoPlayerFragment", "Error resolving URL: ${e.message}", e)
                                activity?.runOnUiThread {
                                    if (isAdded) {
                                        binding.loadingIndicator.visibility = View.GONE
                                        showToast("Error loading video: ${e.message}")
                                    }
                                }
                            }
                        }.start()
                    } else {
                        // Direct URL, no need for resolution
                        val mediaItem = MediaItem.fromUri(uri)
                        exoPlayer.setMediaItem(mediaItem)
                        exoPlayer.playWhenReady = playWhenReady
                        exoPlayer.seekTo(playbackPosition)
                        exoPlayer.prepare()
                    }
                }

                // Start progress updates
                startProgressUpdates()

                // Add a listener to update play/pause button and controls
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == PLAYER_STATE_READY) {
                            updateProgressBar()
                        } else if (state == PLAYER_STATE_ENDED) {
                            Log.d("VideoPlayerFragment", "Playback ended")
                        } else if (state == PLAYER_STATE_ERROR) {
                            Log.e("VideoPlayerFragment", "Player error occurred")
                            showToast("Error playing video")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Update play/pause button icon
                        binding.playPauseButton.setImageResource(
                            if (isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                })
            }
    }

    /**
     * Resolves redirect URLs to get the final video URL
     */
    private fun resolveRedirectUrl(urlString: String): String {
        Log.d("VideoPlayerFragment", "Starting URL resolution for: $urlString")

        // For pahe.win links, we need to follow redirects to get the actual video URL
        val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

        try {
            connection.connect()

            // If we have redirects, follow them
            val responseCode = connection.responseCode
            Log.d("VideoPlayerFragment", "Response code: $responseCode")

            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                // We have the final URL
                val finalUrl = connection.url.toString()
                Log.d("VideoPlayerFragment", "Final URL: $finalUrl")
                return finalUrl
            } else if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                      responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                      responseCode == java.net.HttpURLConnection.HTTP_SEE_OTHER) {

                // Get the redirect URL
                val location = connection.getHeaderField("Location")
                Log.d("VideoPlayerFragment", "Redirected to: $location")
                return resolveRedirectUrl(location)
            } else {
                throw Exception("Unexpected response code: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Sets the screen orientation and manages related preferences
     * @param orientation One of the ActivityInfo.SCREEN_ORIENTATION_* constants
     */
    private fun setScreenOrientation(orientation: Int) {
        try {
            val activity = requireActivity()
            val currentOrientationName = getOrientationName(activity.requestedOrientation)
            val newOrientationName = getOrientationName(orientation)

            Log.d("OrientationDebug", "Changing orientation from $currentOrientationName to $newOrientationName")
            Log.d("OrientationDebug", "Current device rotation: ${getDeviceRotation()}")

            // Force orientation change through main activity for consistency
            if (activity is MainActivity) {
                activity.setScreenOrientation(orientation)
            } else {
                activity.requestedOrientation = orientation
            }

            currentOrientation = orientation

            // Save this orientation preference for future use
            val prefs = requireContext().getSharedPreferences("AnimePrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt(PREF_VIDEO_ORIENTATION, orientation).apply()

            Log.d("VideoPlayerFragment", "Set screen orientation to: $orientation")
            Log.d("OrientationDebug", "Orientation set complete. isOrientationLocked=$isOrientationLocked")
        } catch (e: Exception) {
            Log.e("VideoPlayerFragment", "Error setting orientation", e)
        }
    }

    /**
     * Get the current device rotation in degrees
     */
    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().display
        } else {
            @Suppress("DEPRECATION")
            requireActivity().windowManager.defaultDisplay
        }

        val rotation = display?.rotation ?: 0
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Get a readable name for an orientation constant
     */
    private fun getOrientationName(orientation: Int): String {
        return when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "PORTRAIT"
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "LANDSCAPE"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR -> "SENSOR (Auto)"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "SENSOR_PORTRAIT"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "SENSOR_LANDSCAPE"
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> "REVERSE_PORTRAIT"
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "REVERSE_LANDSCAPE"
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> "UNSPECIFIED"
            ActivityInfo.SCREEN_ORIENTATION_USER -> "USER"
            ActivityInfo.SCREEN_ORIENTATION_BEHIND -> "BEHIND"
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> "FULL_SENSOR"
            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR -> "NOSENSOR"
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> "USER_PORTRAIT"
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> "USER_LANDSCAPE"
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER -> "FULL_USER"
            ActivityInfo.SCREEN_ORIENTATION_LOCKED -> "LOCKED"
            else -> "UNKNOWN($orientation)"
        }
    }

    /**
     * Updates the orientation button icon based on current orientation state
     */
    private fun updateOrientationButtonIcon() {
        if (_binding == null) {
            // Skip updating UI if binding is not initialized yet
            return
        }

        when (currentOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> {
                binding.orientationLockButton.setImageResource(android.R.drawable.ic_menu_always_landscape_portrait)
            }
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> {
                binding.orientationLockButton.setImageResource(android.R.drawable.ic_lock_lock)
            }
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> {
                binding.orientationLockButton.setImageResource(android.R.drawable.ic_menu_rotate)
            }
        }
    }

    // Toggle play/pause
    private fun togglePlayback() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
                binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            } else {
                it.play()
                binding.playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            }
        }
    }


    // Progress tracking methods
    private fun startProgressUpdates() {
        stopProgressUpdates() // Ensure no duplicate runnables
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgressBar() {
        player?.let {
            if (it.duration > 0) {
                val position = it.currentPosition
                val duration = it.duration
                val progress = ((position * 100) / duration).toInt()

                binding.seekBar.progress = progress
                binding.currentTime.text = formatTime(position)
                binding.totalTime.text = formatTime(duration)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val hours = millis / (1000 * 60 * 60)
        val minutes = (millis % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (millis % (1000 * 60)) / 1000

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    // Watch progress saving and loading
    private fun saveWatchProgress() {
        player?.let {
            if (it.duration > 0 && animeTitle != null) {
                val position = it.currentPosition
                val duration = it.duration
                val key = "${WATCH_PROGRESS_PREFIX}${animeTitle}_${episodeNumber}"

                val prefs = requireContext().getSharedPreferences("AnimeWatchProgress", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putLong("${key}_position", position)
                    putLong("${key}_duration", duration)
                    putLong("${key}_timestamp", System.currentTimeMillis())
                    apply()
                }
            }
        }
    }

    private fun loadWatchProgress() {
        if (animeTitle != null) {
            val key = "${WATCH_PROGRESS_PREFIX}${animeTitle}_${episodeNumber}"
            val prefs = requireContext().getSharedPreferences("AnimeWatchProgress", Context.MODE_PRIVATE)
            playbackPosition = prefs.getLong("${key}_position", 0L)
        }
    }

    // System UI visibility control
    private fun hideSystemUI() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep screen on while video is playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSystemUI() {
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, true)

        WindowInsetsControllerCompat(window, binding.root).show(WindowInsetsCompat.Type.systemBars())

        // Remove the keep screen on flag
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Controls visibility management
    private fun showControls() {
        if (_binding == null) return
        binding.controlsContainer.visibility = View.VISIBLE
        binding.seekControls.visibility = View.VISIBLE
        binding.progressContainer.visibility = View.VISIBLE
        binding.playPauseButton.visibility = View.VISIBLE
        controlsVisible = true
    }

    private fun hideControls() {
        if (_binding == null) return
        binding.controlsContainer.visibility = View.GONE
        binding.seekControls.visibility = View.GONE
        binding.progressContainer.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        controlsVisible = false
    }

    private fun toggleControls() {
        if (_binding == null) return
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        cancelHideControlsTask()
        controlsHideHandler.postDelayed({
            // Check if fragment is still attached before hiding controls
            if (isAdded && _binding != null) {
                hideControls()
            }
        }, 3000)
    }

    private fun cancelHideControlsTask() {
        controlsHideHandler.removeCallbacksAndMessages(null)
    }

    // Touch handling
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // Let the gesture detector process the event first
        val gestureResult = gestureDetector.onTouchEvent(event)

        // Handle vertical swipes for brightness and volume
        when (v.id) {
            R.id.brightness_control_area -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastBrightnessY = event.y
                }
                else if (event.action == MotionEvent.ACTION_MOVE) {
                    handleBrightnessChange(event)
                }
                else if (event.action == MotionEvent.ACTION_UP) {
                    lastBrightnessY = 0f
                    binding.brightnessIndicator.visibility = View.GONE
                }
                return true
            }
            R.id.volume_control_area -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastVolumeY = event.y
                }
                else if (event.action == MotionEvent.ACTION_MOVE) {
                    handleVolumeChange(event)
                }
                else if (event.action == MotionEvent.ACTION_UP) {
                    lastVolumeY = 0f
                    binding.volumeIndicator.visibility = View.GONE
                }
                return true
            }
        }

        // For all touch events on player view
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.performClick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                return true
            }
        }

        return gestureResult
    }

    // Brightness control
    private fun handleBrightnessChange(event: MotionEvent) {
        // Implementation unchanged
    }

    // Volume control
    private fun handleVolumeChange(event: MotionEvent) {
        // Implementation unchanged
    }

    // GestureDetector implementation
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        toggleControls()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        return true
        // Implementation unchanged
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onDown(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return false
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        return false
    }

    override fun onLongPress(e: MotionEvent) {
        // No return needed for this method
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        return false
    }

    private fun showToast(message: String) {
        if (!isAdded) return // Check if fragment is attached
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    fun releasePlayer() {
        try {
            Log.d("VideoPlayerFragment", "Releasing player")
            player?.let { exoPlayer ->
                // Save position first
                playbackPosition = exoPlayer.currentPosition
                playWhenReady = exoPlayer.playWhenReady

                // Properly stop playback
                exoPlayer.playWhenReady = false
                exoPlayer.pause()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.release()

                Log.d("VideoPlayerFragment", "Player released successfully")
            }
            player = null

            // Clear any handlers to prevent further updates
            progressHandler.removeCallbacksAndMessages(null)
            controlsHideHandler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e("VideoPlayerFragment", "Error releasing player: ${e.message}")
        }
    }
}
