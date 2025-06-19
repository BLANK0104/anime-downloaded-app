package com.blank.anime.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import com.blank.anime.R
import com.blank.anime.databinding.DialogStoragePermissionBinding
import com.blank.anime.utils.StorageManager

/**
 * A dialog fragment that requests storage permission from the user
 * It explains why the permission is needed and guides the user through the process
 */
class StoragePermissionDialog : DialogFragment() {

    private var _binding: DialogStoragePermissionBinding? = null
    private val binding get() = _binding!!

    private lateinit var storageManager: StorageManager
    private lateinit var directoryPicker: ActivityResultLauncher<Intent>

    private var onPermissionGrantedListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)

        storageManager = StorageManager.getInstance(requireContext())

        // Initialize directory picker
        directoryPicker = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // Save the directory URI
                    storageManager.setStorageDirectory(uri)
                    onPermissionGrantedListener?.invoke()
                    dismiss()
                } ?: run {
                    // No URI was returned
                    Toast.makeText(
                        requireContext(),
                        "Failed to get storage access. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // User cancelled the picker
                if (!storageManager.hasStorageDirectorySet()) {
                    // If this is first time setup and user cancelled, show warning
                    showStorageRequiredWarning()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogStoragePermissionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up the UI
        binding.storagePermissionTitle.text = if (storageManager.hasStorageDirectorySet())
            "Change Storage Location" else "Storage Access Required"

        binding.storagePermissionDescription.text = if (storageManager.hasStorageDirectorySet())
            "Your anime files are currently stored in a custom location. You can change this location if needed."
            else "This app needs access to storage to save anime downloads. Please select a folder where all your anime will be saved."

        binding.selectFolderButton.setOnClickListener {
            launchDirectoryPicker()
        }

        binding.cancelButton.setOnClickListener {
            if (!storageManager.hasStorageDirectorySet()) {
                showStorageRequiredWarning()
            } else {
                dismiss()
            }
        }

        // If storage is already set and this is just a change request, show the current location
        if (storageManager.hasStorageDirectorySet()) {
            binding.currentStorageLocation.visibility = View.VISIBLE
            binding.currentStorageLocation.text = "Current storage location: ${storageManager.getStorageDirectoryUri()?.lastPathSegment}"
            binding.cancelButton.visibility = View.VISIBLE
        } else {
            binding.currentStorageLocation.visibility = View.GONE
            binding.cancelButton.visibility = View.GONE
        }
    }

    private fun launchDirectoryPicker() {
        val intent = storageManager.createDirectorySelectionIntent()
        directoryPicker.launch(intent)
    }

    private fun showStorageRequiredWarning() {
        AlertDialog.Builder(requireContext())
            .setTitle("Storage Access Required")
            .setMessage("This app needs storage access to function properly. Without it, downloaded anime cannot be saved or accessed.")
            .setPositiveButton("Select Folder") { _, _ -> launchDirectoryPicker() }
            .setNegativeButton("Exit App") { _, _ -> activity?.finish() }
            .setCancelable(false)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setOnPermissionGrantedListener(listener: () -> Unit) {
        onPermissionGrantedListener = listener
    }

    companion object {
        const val TAG = "StoragePermissionDialog"

        fun newInstance() = StoragePermissionDialog()
    }
}
