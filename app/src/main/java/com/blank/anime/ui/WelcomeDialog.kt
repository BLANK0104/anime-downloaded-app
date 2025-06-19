package com.blank.anime.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.blank.anime.R
import com.blank.anime.databinding.DialogWelcomeBinding
import com.blank.anime.viewmodel.AniListViewModel

/**
 * Welcome dialog shown on first app launch that prompts users to log in with AniList
 * or skip for now
 */
class WelcomeDialog : DialogFragment() {

    private var _binding: DialogWelcomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var aniListViewModel: AniListViewModel
    private var onDismissListener: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
        isCancelable = false // Prevent dismissing by tapping outside
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get the AniList ViewModel
        aniListViewModel = ViewModelProvider(requireActivity())[AniListViewModel::class.java]

        // Set up button click listeners
        binding.loginButton.setOnClickListener {
            // Launch AniList login flow
            aniListViewModel.login()
            markFirstLaunchComplete()
            dismiss()
            onDismissListener()
        }

        binding.skipButton.setOnClickListener {
            // Skip login for now
            markFirstLaunchComplete()
            dismiss()
            onDismissListener()
        }
    }

    private fun markFirstLaunchComplete() {
        // Mark that the first launch welcome dialog has been shown
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FIRST_LAUNCH, false)
            .apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    companion object {
        const val TAG = "WelcomeDialog"
        private const val PREFS_NAME = "AppPrefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"

        fun newInstance() = WelcomeDialog()

        /**
         * Check if this is the first app launch
         */
        fun isFirstLaunch(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FIRST_LAUNCH, true)
        }
    }
}
