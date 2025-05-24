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
import com.blank.anime.R
import com.blank.anime.databinding.FragmentVideoPlayerBinding
import kotlin.math.abs

@UnstableApi
class VideoPlayerFragment : Fragment(), GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener, View.OnTouchListener {

    private var _binding: FragmentVideoPlayerBinding? = null
    private val binding get() = _binding!!

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

    companion object {
        private const val ARG_VIDEO_URI = "video_uri"
        private const val ARG_VIDEO_TITLE = "video_title"

        fun newInstance(videoUri: Uri, videoTitle: String): VideoPlayerFragment {
            return VideoPlayerFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_VIDEO_URI, videoUri)
                    putString(ARG_VIDEO_TITLE, videoTitle)
                }
            }
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
        }

        // Lock orientation to landscape initially
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

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
            requireActivity().supportFragmentManager.popBackStack()
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

        // Set orientation lock button click listener
        binding.orientationLockButton.setOnClickListener {
            isOrientationLocked = !isOrientationLocked
            if (isOrientationLocked) {
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                showToast("Orientation locked")
            } else {
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                showToast("Orientation unlocked")
            }
        }

        // Set up seekbar listener
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let {
                        val newPosition = progress.toLong() * it.duration / 100
                        binding.currentTime.text = formatTime(newPosition)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                cancelHideControlsTask()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player?.let {
                    val newPosition = seekBar.progress.toLong() * it.duration / 100
                    it.seekTo(newPosition)
                }
                scheduleHideControls()
            }
        })

        // Schedule initial hiding of controls
        scheduleHideControls()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI() // Ensure system UI stays hidden when returning to the fragment
        if (player != null) {
            startProgressUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopProgressUpdates()
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdates()
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

        // Show the bottom navigation again when leaving the player
        activity?.findViewById<View>(R.id.bottom_nav)?.visibility = View.VISIBLE

        // Reset orientation when leaving the player
        try {
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } catch (e: Exception) {
            // Ignore orientation errors
        }
    }

    private fun initializePlayer() {
        player = ExoPlayer.Builder(requireContext())
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer

                // Set up media item
                videoUri?.let { uri ->
                    val mediaItem = MediaItem.fromUri(uri)
                    exoPlayer.setMediaItem(mediaItem)
                }

                // Set playback parameters
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.seekTo(playbackPosition)
                exoPlayer.prepare()

                // Start progress updates
                startProgressUpdates()

                // Add a listener to update play/pause button and controls
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            updateProgressBar()
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

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        player = null
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
        if (_binding == null) return

        if (lastBrightnessY == 0f) {
            lastBrightnessY = event.y
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val deltaY = lastBrightnessY - event.y
        val deltaPercent = deltaY / screenHeight * 0.5f  // Make adjustment less sensitive

        try {
            // Get window brightness instead of system brightness
            val window = requireActivity().window
            var brightness = window.attributes.screenBrightness

            // If brightness is unset (-1), use a default value
            if (brightness < 0) brightness = 0.5f

            // Adjust brightness by the delta (normalize to 0.0 - 1.0 range)
            brightness = (brightness + deltaPercent).coerceIn(0.01f, 1.0f)

            // Set brightness
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness
            window.attributes = layoutParams

            // Show brightness indicator
            val brightnessPercent = (brightness * 100).toInt()
            binding.brightnessIndicator.text = "Brightness: $brightnessPercent%"
            binding.brightnessIndicator.visibility = View.VISIBLE

            // Update reference position
            lastBrightnessY = event.y
        } catch (e: Exception) {
            // Ignore brightness control errors
            e.printStackTrace()
        }
    }

    // Volume control
    private fun handleVolumeChange(event: MotionEvent) {
        if (_binding == null) return

        if (lastVolumeY == 0f) {
            lastVolumeY = event.y
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val deltaY = lastVolumeY - event.y
        val deltaPercent = deltaY / screenHeight

        try {
            // Get current volume
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val delta = (maxVolume * deltaPercent).toInt()

            // Apply new volume
            if (abs(delta) >= 1) {
                val newVolume = (currentVolume + delta).coerceIn(0, maxVolume)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                // Show volume indicator
                val volumePercent = (newVolume * 100) / maxVolume
                binding.volumeIndicator.text = "Volume: $volumePercent%"
                binding.volumeIndicator.visibility = View.VISIBLE

                // Update reference position
                lastVolumeY = event.y
            }
        } catch (e: Exception) {
            // Ignore volume control errors
        }
    }

    // GestureDetector implementation
    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        toggleControls()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (_binding == null) return false

        // Get the X position relative to screen width
        val screenWidth = resources.displayMetrics.widthPixels
        val xPosition = e.x

        player?.let {
            // If double tap on the left side, seek backward
            if (xPosition < screenWidth / 2) {
                val newPosition = (it.currentPosition - SEEK_AMOUNT_DOUBLE_TAP).coerceAtLeast(0)
                it.seekTo(newPosition)
                showToast("⏪ 5 seconds")
            }
            // If double tap on the right side, seek forward
            else {
                val newPosition = (it.currentPosition + SEEK_AMOUNT_DOUBLE_TAP)
                    .coerceAtMost(it.duration)
                it.seekTo(newPosition)
                showToast("⏩ 5 seconds")
            }
        }

        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onDown(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean = false

    override fun onLongPress(e: MotionEvent) {}

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean = false

    private fun showToast(message: String) {
        if (!isAdded) return // Check if fragment is attached
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}