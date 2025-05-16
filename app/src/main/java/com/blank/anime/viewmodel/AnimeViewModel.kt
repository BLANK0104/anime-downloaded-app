package com.blank.anime.viewmodel

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blank.anime.model.AnimeSearchResult
import com.blank.anime.model.DownloadResponse
import com.blank.anime.model.EpisodeResponse
import com.blank.anime.repository.AnimeRepository
import kotlinx.coroutines.launch

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
}