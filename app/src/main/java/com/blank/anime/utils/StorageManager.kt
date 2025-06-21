package com.blank.anime.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages storage operations for the app, including directory selection and file operations
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val PREF_NAME = "AnimeStoragePrefs"
        private const val PREF_STORAGE_URI = "storage_directory_uri"
        private const val PREF_WATCH_TIME_THRESHOLD = "watch_time_threshold"
        private const val DEFAULT_WATCH_TIME_THRESHOLD = 5 // 5 minutes default

        // Get singleton instance
        @Volatile
        private var INSTANCE: StorageManager? = null

        fun getInstance(context: Context): StorageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StorageManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Check if the user has selected a storage directory
     */
    fun hasStorageDirectorySet(): Boolean {
        val uriString = prefs.getString(PREF_STORAGE_URI, null)
        if (uriString == null) {
            return false
        }

        // Verify the URI is still valid and accessible
        try {
            val uri = Uri.parse(uriString)
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            return documentFile != null && documentFile.exists() && documentFile.canWrite()
        } catch (e: Exception) {
            Log.e("StorageManager", "Error checking storage directory: ${e.message}")
            return false
        }
    }

    /**
     * Get the URI of the selected storage directory
     */
    fun getStorageDirectoryUri(): Uri? {
        val uriString = prefs.getString(PREF_STORAGE_URI, null)
        return if (uriString != null) Uri.parse(uriString) else null
    }

    /**
     * Save the selected storage directory URI
     */
    fun setStorageDirectory(uri: Uri) {
        // Take persistable permission
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)

        // Save the URI
        prefs.edit().putString(PREF_STORAGE_URI, uri.toString()).apply()
    }

    /**
     * Create an intent to select a directory
     */
    fun createDirectorySelectionIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Optionally configure initial URI
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:Download"))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    /**
     * Creates a folder structure for anime downloads
     * Path: Selected directory/Anime/[anime_title]/
     */
    fun createAnimeFolder(animeTitle: String): DocumentFile? {
        val storageUri = getStorageDirectoryUri() ?: return null
        val rootDir = DocumentFile.fromTreeUri(context, storageUri) ?: return null

        // First check if Anime folder exists, if not create it
        var animeDir = rootDir.findFile("Anime")
        if (animeDir == null || !animeDir.exists()) {
            animeDir = rootDir.createDirectory("Anime")
            if (animeDir == null) {
                Log.e("StorageManager", "Failed to create Anime directory")
                return null
            }
        }

        // Check if specific anime folder exists, if not create it
        val sanitizedTitle = sanitizeFilename(animeTitle)
        var animeSpecificDir = animeDir.findFile(sanitizedTitle)
        if (animeSpecificDir == null || !animeSpecificDir.exists()) {
            animeSpecificDir = animeDir.createDirectory(sanitizedTitle)
            if (animeSpecificDir == null) {
                Log.e("StorageManager", "Failed to create directory for $animeTitle")
                return null
            }
        }

        return animeSpecificDir
    }

    /**
     * Saves a downloaded video file to the specific anime folder
     */
    fun saveEpisodeFile(
        inputStream: InputStream,
        animeTitle: String,
        episodeNumber: Int,
        quality: Int,
        language: String
    ): Uri? {
        try {
            val animeDir = createAnimeFolder(animeTitle) ?: return null

            // Create a proper filename
            val fileName = "Episode_${episodeNumber}_${quality}p_${language}.mp4"

            // Check if file already exists, delete if it does
            val existingFile = animeDir.findFile(fileName)
            if (existingFile != null && existingFile.exists()) {
                existingFile.delete()
            }

            // Create new file
            val newFile = animeDir.createFile("video/mp4", fileName) ?: return null

            // Write the content
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }

            return newFile.uri
        } catch (e: Exception) {
            Log.e("StorageManager", "Error saving episode: ${e.message}")
            return null
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                Log.e("StorageManager", "Error closing input stream: ${e.message}")
            }
        }
    }

    /**
     * Saves information about downloaded episode for later tracking
     */
    fun saveEpisode(
        animeTitle: String,
        episodeNumber: Int,
        quality: Int,
        language: String,
        filePath: String
    ) {
        try {
            // Store episode information in SharedPreferences
            val episodeKey = "anime_${animeTitle.replace(" ", "_")}_ep_$episodeNumber"
            val episodeInfo = mapOf(
                "title" to animeTitle,
                "episode" to episodeNumber.toString(),
                "quality" to quality.toString(),
                "language" to language,
                "path" to filePath,
                "timestamp" to System.currentTimeMillis().toString()
            )

            // Convert map to JSON-like string for storage
            val episodeInfoString = episodeInfo.entries.joinToString(separator = ",") {
                "${it.key}:${it.value}"
            }

            prefs.edit().putString(episodeKey, episodeInfoString).apply()
            Log.d("StorageManager", "Saved episode info: $animeTitle Episode $episodeNumber")
        } catch (e: Exception) {
            Log.e("StorageManager", "Error saving episode info: ${e.message}")
        }
    }

    /**
     * Check if an episode exists
     */
    fun episodeExists(animeTitle: String, episodeNumber: Int): Boolean {
        val episodeKey = "anime_${animeTitle.replace(" ", "_")}_ep_$episodeNumber"
        return prefs.contains(episodeKey)
    }

    /**
     * Gets the file path for a downloaded episode
     */
    fun getEpisodePath(animeTitle: String, episodeNumber: Int): String? {
        val episodeKey = "anime_${animeTitle.replace(" ", "_")}_ep_$episodeNumber"
        val episodeInfoString = prefs.getString(episodeKey, null) ?: return null

        // Parse the info string to get the path
        val pathEntry = episodeInfoString.split(",")
            .find { it.startsWith("path:") }

        return pathEntry?.substringAfter("path:")
    }

    /**
     * Gets all episodes for a specific anime
     */
    fun getAnimeEpisodes(animeTitle: String): List<EpisodeFile> {
        val episodes = mutableListOf<EpisodeFile>()
        try {
            val storageUri = getStorageDirectoryUri() ?: return episodes
            val rootDir = DocumentFile.fromTreeUri(context, storageUri) ?: return episodes

            // Check if root directory is already the "Anime" folder
            val animeSpecificDir = if (rootDir.name == "Anime" || storageUri.toString().endsWith("Anime")) {
                // If so, look for the anime title as a direct subdirectory
                Log.d("StorageManager", "Finding episodes in root/animeTitle: $animeTitle")
                rootDir.listFiles().find { it.isDirectory && it.name == animeTitle }
            } else {
                // Otherwise use the old approach (Anime/animeTitle)
                Log.d("StorageManager", "Finding episodes in root/Anime/animeTitle: $animeTitle")
                val animeDir = rootDir.findFile("Anime") ?: return episodes
                val sanitizedTitle = sanitizeFilename(animeTitle)
                animeDir.findFile(sanitizedTitle)
            }

            if (animeSpecificDir == null) {
                Log.e("StorageManager", "Anime directory for $animeTitle not found")
                return episodes
            }

            Log.d("StorageManager", "Found anime directory: ${animeSpecificDir.name}, looking for episodes")

            // Get all MP4 files in the directory
            val episodeFiles = animeSpecificDir.listFiles().filter {
                it.name?.endsWith(".mp4", ignoreCase = true) == true
            }

            Log.d("StorageManager", "Found ${episodeFiles.size} MP4 files in ${animeSpecificDir.name}")

            // Extract episode information from filenames
            for (file in episodeFiles) {
                val name = file.name ?: continue
                Log.d("StorageManager", "Processing file: $name")

                // Parse the episode number from the filename
                val episodePattern = "Episode_(\\d+)_".toRegex()
                val episodeMatch = episodePattern.find(name)
                val episodeNumber = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

                if (episodeNumber == null) {
                    Log.d("StorageManager", "Could not extract episode number from $name")
                    continue
                }

                // Parse the quality from the filename
                val qualityPattern = "(\\d+)p".toRegex()
                val qualityMatch = qualityPattern.find(name)
                val quality = qualityMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

                episodes.add(EpisodeFile(
                    uri = file.uri,
                    episodeNumber = episodeNumber,
                    quality = quality,
                    fileName = name,
                    lastModified = file.lastModified(),
                    fileSize = file.length()
                ))
                Log.d("StorageManager", "Added episode $episodeNumber from $name")
            }

            // Sort by episode number
            episodes.sortBy { it.episodeNumber }
            Log.d("StorageManager", "Final episode list for $animeTitle: ${episodes.map { it.episodeNumber }}")

        } catch (e: Exception) {
            Log.e("StorageManager", "Error getting anime episodes: ${e.message}", e)
        }

        return episodes
    }

    /**
     * Find a specific episode file
     */
    fun findEpisode(animeTitle: String, episodeNumber: Int): EpisodeFile? {
        return getAnimeEpisodes(animeTitle).firstOrNull { it.episodeNumber == episodeNumber }
    }

    /**
     * Find the next episode file
     */
    fun findNextEpisode(animeTitle: String, currentEpisode: Int): EpisodeFile? {
        val episodes = getAnimeEpisodes(animeTitle)
        return episodes.firstOrNull { it.episodeNumber > currentEpisode }
    }

    /**
     * Find the previous episode file
     */
    fun findPreviousEpisode(animeTitle: String, currentEpisode: Int): EpisodeFile? {
        val episodes = getAnimeEpisodes(animeTitle)
        return episodes.filter { it.episodeNumber < currentEpisode }
            .maxByOrNull { it.episodeNumber }
    }

    /**
     * Delete an episode
     */
    fun deleteEpisode(animeTitle: String, episodeNumber: Int): Boolean {
        try {
            val episode = findEpisode(animeTitle, episodeNumber) ?: return false
            val episodeFile = DocumentFile.fromSingleUri(context, episode.uri) ?: return false
            return episodeFile.delete()
        } catch (e: Exception) {
            Log.e("StorageManager", "Error deleting episode: ${e.message}")
            return false
        }
    }

    /**
     * Get the Anime directory (root folder for all anime)
     */
    fun getAnimeDirectory(): DocumentFile? {
        try {
            val storageUri = getStorageDirectoryUri() ?: return null
            val rootDir = DocumentFile.fromTreeUri(context, storageUri) ?: return null

            // Check if root directory itself is named "Anime"
            if (rootDir.name == "Anime" || storageUri.toString().endsWith("Anime")) {
                Log.d("StorageManager", "Selected directory is already the Anime folder")
                return rootDir
            }

            // Otherwise, look for "Anime" subfolder
            val animeDir = rootDir.findFile("Anime")
            if (animeDir != null) {
                return animeDir
            }

            // If neither condition is met, log an error
            Log.e("StorageManager", "Anime directory not found in storage")
            return null
        } catch (e: Exception) {
            Log.e("StorageManager", "Error getting Anime directory: ${e.message}")
            return null
        }
    }

    /**
     * Delete an episode file by its URI
     */
    fun deleteEpisodeFile(fileUri: Uri): Boolean {
        try {
            val file = DocumentFile.fromSingleUri(context, fileUri)
            return file?.exists() == true && file.delete()
        } catch (e: Exception) {
            Log.e("StorageManager", "Error deleting episode file: ${e.message}")
            return false
        }
    }

    /**
     * Get the watch time threshold in minutes
     */
    fun getWatchTimeThreshold(): Int {
        return prefs.getInt(PREF_WATCH_TIME_THRESHOLD, DEFAULT_WATCH_TIME_THRESHOLD)
    }

    /**
     * Save the watch time threshold in minutes
     */
    fun saveWatchTimeThreshold(minutes: Int) {
        prefs.edit().putInt(PREF_WATCH_TIME_THRESHOLD, minutes).apply()
    }

    /**
     * Sanitize a filename to remove invalid characters
     */
    private fun sanitizeFilename(filename: String): String {
        return filename.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }

    /**
     * Data class to hold episode file information
     */
    data class EpisodeFile(
        val uri: Uri,
        val episodeNumber: Int,
        val quality: Int,
        val fileName: String,
        val lastModified: Long,
        val fileSize: Long
    )

    /**
     * Gets all anime titles with downloaded content
     */
    fun getAllDownloadedAnimeTitles(): List<String> {
        Log.d("StorageManager", "Getting all downloaded anime titles...")

        // Check if storage URI exists
        val storageUri = getStorageDirectoryUri()
        if (storageUri == null) {
            Log.e("StorageManager", "Storage URI is null")
            return emptyList()
        }
        Log.d("StorageManager", "Storage URI: $storageUri")

        // Get the root directory
        val rootDir = DocumentFile.fromTreeUri(context, storageUri)
        if (rootDir == null) {
            Log.e("StorageManager", "Root directory is null")
            return emptyList()
        }
        Log.d("StorageManager", "Root directory exists: ${rootDir.exists()}, name: ${rootDir.name}, isDirectory: ${rootDir.isDirectory}")

        // Check if root directory is already the "Anime" folder
        if (rootDir.name == "Anime" || storageUri.toString().endsWith("Anime")) {
            Log.d("StorageManager", "Selected directory is already the Anime folder - using subdirectories as anime titles")
            val titles = mutableListOf<String>()

            try {
                rootDir.listFiles().forEach { file ->
                    if (file.isDirectory && file.name != null) {
                        Log.d("StorageManager", "Found anime directory: ${file.name}")
                        titles.add(file.name!!)

                        // Check how many episodes this anime directory contains
                        val episodeCount = file.listFiles().count {
                            it.name?.endsWith(".mp4", ignoreCase = true) == true
                        }
                        Log.d("StorageManager", "Anime ${file.name} has $episodeCount episodes")
                    }
                }

                Log.d("StorageManager", "Found anime titles from root: $titles")
                return titles
            } catch (e: Exception) {
                Log.e("StorageManager", "Error getting anime titles from root: ${e.message}", e)
                return emptyList()
            }
        }

        // Otherwise, use the old approach (looking for an "Anime" subfolder)
        val animeDir = rootDir.findFile("Anime")
        if (animeDir == null) {
            Log.e("StorageManager", "Anime directory not found. Checking root content:")
            rootDir.listFiles().forEach {
                Log.d("StorageManager", "Found in root: ${it.name}, isDirectory: ${it.isDirectory}")
            }
            return emptyList()
        }
        Log.d("StorageManager", "Anime directory exists: ${animeDir.exists()}, name: ${animeDir.name}, isDirectory: ${animeDir.isDirectory}")

        val titles = mutableListOf<String>()

        try {
            // Get all subdirectories (anime titles)
            val files = animeDir.listFiles()
            Log.d("StorageManager", "Found ${files.size} items in Anime directory")

            files.forEach { file ->
                Log.d("StorageManager", "Checking item: ${file.name}, isDirectory: ${file.isDirectory}")

                if (file.isDirectory && file.name != null) {
                    titles.add(file.name!!)
                    Log.d("StorageManager", "Added anime title: ${file.name}")

                    // Check for episodes in this folder
                    val episodeFiles = file.listFiles().filter { it.name?.endsWith(".mp4", ignoreCase = true) == true }
                    Log.d("StorageManager", "Found ${episodeFiles.size} episode files in ${file.name}")
                }
            }

            Log.d("StorageManager", "Final list of anime titles: $titles")
        } catch (e: Exception) {
            Log.e("StorageManager", "Error getting anime titles: ${e.message}", e)
        }

        return titles
    }
}
