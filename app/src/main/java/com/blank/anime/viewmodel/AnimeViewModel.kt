package com.blank.anime.viewmodel

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blank.anime.model.AnimeSearchResult
import com.blank.anime.model.DownloadResponse
import com.blank.anime.model.EpisodeResponse
import com.blank.anime.repository.AnimeRepository
import com.blank.anime.utils.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class AnimeViewModel : ViewModel() {
    private val repository = AnimeRepository()

    // Changed to use Result sealed class
    private val _searchResults = MutableLiveData<Result>()
    val searchResults: LiveData<Result> = _searchResults

    private val _episodes = MutableLiveData<EpisodeResponse>()
    val episodes: LiveData<EpisodeResponse> = _episodes

    private val _downloadInfo = MutableLiveData<DownloadResponse>()
    val downloadInfo: LiveData<DownloadResponse> = _downloadInfo

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _downloadProgress = MutableLiveData<DownloadProgress>()
    val downloadProgress: LiveData<DownloadProgress> = _downloadProgress

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    fun searchAnime(query: String) {
        _searchResults.value = Result.Loading
        viewModelScope.launch {
            try {
                val result = repository.searchAnime(query)
                _searchResults.value = Result.Success(result.results)
            } catch (e: Exception) {
                _searchResults.value = Result.Error("Error searching: ${e.message}")
            }
        }
    }

    fun getEpisodes(animeId: String, startEpisode: Int, endEpisode: Int) {
        viewModelScope.launch {
            try {
                _loading.value = true
                val result = repository.getEpisodes(animeId, startEpisode, endEpisode)
                _episodes.value = result
                _loading.value = false
            } catch (e: Exception) {
                _error.value = "Error getting episodes: ${e.message}"
                _loading.value = false
            }
        }
    }

    /**
     * Clears the current episodes data to prepare for batch loading
     */
    fun clearEpisodes() {
        _episodes.value = EpisodeResponse(emptyMap())
    }

    fun getDownloadLink(
        animeId: String,
        episodeNum: Int,
        lang: String,
        quality: Int,
        animeTitle: String
    ) {
        viewModelScope.launch {
            try {
                _loading.value = true
                val result = repository.getDownloadLink(
                    animeId, episodeNum, lang, quality, animeTitle
                )
                _downloadInfo.value = result
                _loading.value = false
            } catch (e: Exception) {
                _error.value = "Error getting download link: ${e.message}"
                _loading.value = false
            }
        }
    }

    fun downloadEpisodeViaStorageManager(context: Context, downloadResponse: DownloadResponse) {
        viewModelScope.launch {
            try {
                _downloadProgress.value = DownloadProgress(
                    animeTitle = downloadResponse.anime_title,
                    episodeNumber = downloadResponse.episode,
                    progress = 0,
                    isComplete = false,
                    url = downloadResponse.download_link
                )

                // Use Android's DownloadManager to handle the download
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val request = DownloadManager.Request(Uri.parse(downloadResponse.download_link))
                    .setTitle("${downloadResponse.anime_title} - Episode ${downloadResponse.episode}")
                    .setDescription("Downloading episode in ${downloadResponse.quality}p")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        "Anime/${downloadResponse.anime_title}/Episode_${downloadResponse.episode}_${downloadResponse.quality}p_${downloadResponse.language}.mp4"
                    )

                downloadManager.enqueue(request)

                // Update download progress status
                _downloadProgress.value = _downloadProgress.value?.copy(isComplete = true, progress = 100)

                // Store download information in StorageManager for tracking
                val storageManager = StorageManager.getInstance(context)
                storageManager.saveEpisode(
                    animeTitle = downloadResponse.anime_title,
                    episodeNumber = downloadResponse.episode,
                    quality = downloadResponse.quality,
                    language = downloadResponse.language,
                    filePath = "Anime/${downloadResponse.anime_title}/Episode_${downloadResponse.episode}_${downloadResponse.quality}p_${downloadResponse.language}.mp4"
                )
            } catch (e: Exception) {
                _error.value = "Download error: ${e.message}"
                _downloadProgress.value = _downloadProgress.value?.copy(isComplete = false)
            }
        }
    }

    // Legacy download method (keep for reference but don't use)
    fun downloadEpisode(context: Context, downloadResponse: DownloadResponse) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

            val request = DownloadManager.Request(Uri.parse(downloadResponse.download_link))
                .setTitle("Downloading ${downloadResponse.anime_title} - Episode ${downloadResponse.episode}")
                .setDescription("Downloading in ${downloadResponse.quality}p, ${downloadResponse.language}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Anime/${downloadResponse.anime_title}/Episode_${downloadResponse.episode}_${downloadResponse.quality}p.mp4"
                )

            downloadManager.enqueue(request)
        } catch (e: Exception) {
            _error.value = "Download failed: ${e.message}"
        }
    }

    // Added Result sealed class to fix the error
    sealed class Result {
        data object Loading : Result()
        data class Success(val data: List<AnimeSearchResult>) : Result()
        data class Error(val message: String) : Result()
    }

    // Add synchronous methods for direct coroutine use in fragments

    /**
     * Synchronous version of getEpisodes that can be called from a coroutine scope
     * Returns the episode response directly instead of using LiveData
     */
    suspend fun getEpisodesSynchronously(
        animeId: String,
        startEpisode: Int,
        endEpisode: Int
    ): EpisodeResponse? {
        return try {
            repository.getEpisodes(animeId, startEpisode, endEpisode)
        } catch (e: Exception) {
            _error.postValue("Error getting episodes: ${e.message}")
            null
        }
    }

    /**
     * Synchronous version of getDownloadLink that can be called from a coroutine scope
     * Returns the download response directly instead of using LiveData
     */
    suspend fun getDownloadLinkSynchronously(
        animeId: String,
        episodeNum: Int,
        lang: String,
        quality: Int,
        animeTitle: String
    ): DownloadResponse? {
        return try {
            repository.getDownloadLink(animeId, episodeNum, lang, quality, animeTitle)
        } catch (e: Exception) {
            _error.postValue("Error getting download link: ${e.message}")
            null
        }
    }

    // Class for tracking download progress
    data class DownloadProgress(
        val animeTitle: String,
        val episodeNumber: Int,
        val progress: Int,
        val isComplete: Boolean,
        val uri: Uri? = null,
        val error: String? = null,
        val url: String? = null
    )

    /**
     * InputStream wrapper to track download progress
     */
    private class ProgressInputStream(
        private val inputStream: InputStream,
        private val contentLength: Int,
        private val onProgressChanged: (Int) -> Unit
    ) : InputStream() {
        private var totalBytesRead: Long = 0

        override fun read(): Int {
            val b = inputStream.read()
            if (b != -1) {
                totalBytesRead++
                updateProgress()
            }
            return b
        }

        override fun read(b: ByteArray): Int {
            val bytesRead = inputStream.read(b)
            if (bytesRead != -1) {
                totalBytesRead += bytesRead
                updateProgress()
            }
            return bytesRead
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val bytesRead = inputStream.read(b, off, len)
            if (bytesRead != -1) {
                totalBytesRead += bytesRead
                updateProgress()
            }
            return bytesRead
        }

        private fun updateProgress() {
            if (contentLength > 0) {
                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                onProgressChanged(progress)
            }
        }

        override fun skip(n: Long): Long {
            val skipped = inputStream.skip(n)
            totalBytesRead += skipped
            updateProgress()
            return skipped
        }

        override fun available(): Int {
            return inputStream.available()
        }

        override fun close() {
            inputStream.close()
        }

        override fun mark(readlimit: Int) {
            inputStream.mark(readlimit)
        }

        override fun reset() {
            inputStream.reset()
        }

        override fun markSupported(): Boolean {
            return inputStream.markSupported()
        }
    }

    /**
     * Adds episodes from a batch to the current list of episodes
     * This is used when fetching episodes in batches
     */
    fun addEpisodesToCurrentList(newEpisodes: Map<String, Map<String, Map<String, List<String>>>>) {
        val currentEpisodes = _episodes.value?.episodes ?: mutableMapOf()
        val updatedEpisodes = currentEpisodes.toMutableMap()

        // Add all episodes from the new batch to the existing map
        updatedEpisodes.putAll(newEpisodes)

        // Log episode numbers before and after merging
        val beforeKeys = currentEpisodes.keys.sorted()
        val afterKeys = updatedEpisodes.keys.sorted()
        Log.d("AnimeViewModel", "Episodes before adding: $beforeKeys (${beforeKeys.size} episodes)")
        Log.d("AnimeViewModel", "Episodes after adding: $afterKeys (${afterKeys.size} episodes)")
        Log.d("AnimeViewModel", "New episodes added: ${newEpisodes.keys}")

        // Create a new EpisodeResponse with the updated episodes map
        val updatedResponse = EpisodeResponse(episodes = updatedEpisodes)

        // Update the LiveData
        _episodes.postValue(updatedResponse)
    }

    /**
     * Ensures all expected episodes are in the episodes map
     * If an episode is missing, it will create a placeholder with sample URLs
     * @param totalExpectedEpisodes The total number of episodes expected for this anime
     * @param placeholderUrl Optional URL to use for placeholders, defaults to a sample URL
     */
    fun ensureAllEpisodesPresent(totalExpectedEpisodes: Int, placeholderUrl: String = "https://placeholder.url/episode") {
        val currentEpisodes = _episodes.value?.episodes?.toMutableMap() ?: mutableMapOf()
        var updated = false

        // Check if all expected episodes are present
        for (i in 1..totalExpectedEpisodes) {
            val episodeNum = i.toString()
            if (!currentEpisodes.containsKey(episodeNum)) {
                Log.d("AnimeViewModel", "Adding placeholder for missing episode: $episodeNum")

                // Create placeholder episode with sample URLs for all qualities and languages
                val placeholderEpisode = mutableMapOf<String, MutableMap<String, List<String>>>()

                // Add placeholder for English and Japanese audio
                val languages = listOf("eng", "jpn")
                val qualities = listOf("360", "720", "1080")

                languages.forEach { lang ->
                    val qualityMap = mutableMapOf<String, List<String>>()
                    qualities.forEach { quality ->
                        qualityMap[quality] = listOf("$placeholderUrl/${episodeNum}_${lang}_${quality}")
                    }
                    placeholderEpisode[lang] = qualityMap
                }

                // Add the placeholder episode to the map
                currentEpisodes[episodeNum] = placeholderEpisode
                updated = true
            }
        }

        if (updated) {
            // Create a new EpisodeResponse with the updated episodes map
            val updatedResponse = EpisodeResponse(episodes = currentEpisodes)

            // Update the LiveData
            _episodes.postValue(updatedResponse)
            Log.d("AnimeViewModel", "Added placeholder episodes, total now: ${currentEpisodes.size}")
        }
    }
}