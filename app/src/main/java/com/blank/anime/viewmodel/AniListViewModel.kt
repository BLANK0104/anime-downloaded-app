package com.blank.anime.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.blank.anime.api.AniListApiClient
import com.blank.anime.auth.AniListAuthManager
import com.blank.anime.model.*
import kotlinx.coroutines.launch

/**
 * ViewModel for AniList data and functionality
 */
class AniListViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AniListViewModel"
    }

    private val authManager = AniListAuthManager.getInstance(getApplication())
    private var apiClient: AniListApiClient? = null

    // LiveData objects
    private val _userProfile = MutableLiveData<AniListUser?>()
    val userProfile: LiveData<AniListUser?> = _userProfile

    private val _animeCollection = MutableLiveData<AniListCollection?>()
    val animeCollection: LiveData<AniListCollection?> = _animeCollection

    private val _watchingAnime = MutableLiveData<List<AniListMedia>>()
    val watchingAnime: LiveData<List<AniListMedia>> = _watchingAnime

    private val _planToWatchAnime = MutableLiveData<List<AniListMedia>>()
    val planToWatchAnime: LiveData<List<AniListMedia>> = _planToWatchAnime

    private val _completedAnime = MutableLiveData<List<AniListMedia>>()
    val completedAnime: LiveData<List<AniListMedia>> = _completedAnime

    private val _trendingAnime = MutableLiveData<List<AniListMedia>>()
    val trendingAnime: LiveData<List<AniListMedia>> = _trendingAnime

    private val _recommendations = MutableLiveData<List<AniListRecommendation>>()
    val recommendations: LiveData<List<AniListRecommendation>> = _recommendations

    // A general LiveData for the current category being viewed
    private val _categoryAnimeList = MutableLiveData<List<AniListMedia>>()
    val categoryAnimeList: LiveData<List<AniListMedia>> = _categoryAnimeList

    // Add these LiveData objects for the new sections
    private val _popularSeasonalAnime = MutableLiveData<List<AniListMedia>>()
    val popularSeasonalAnime: LiveData<List<AniListMedia>> = _popularSeasonalAnime

    private val _popularAllTimeAnime = MutableLiveData<List<AniListMedia>>()
    val popularAllTimeAnime: LiveData<List<AniListMedia>> = _popularAllTimeAnime

    // Loading and error states
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    init {
        // Initialize API client if user is logged in
        if (authManager.isLoggedIn()) {
            apiClient = AniListApiClient(authManager.getAccessToken())
            refreshAllData()
        } else {
            apiClient = AniListApiClient() // Anonymous client for unauthenticated requests
            loadTrendingAnime()
        }
    }

    /**
     * Handle the OAuth redirect after login
     */
    fun handleAuthIntent(intent: Intent) {
        Log.d(TAG, "handleAuthIntent called with data: ${intent.data}")

        viewModelScope.launch {
            _isLoading.value = true

            try {
                Log.d(TAG, "Starting auth response handling in viewModelScope")
                val success = authManager.handleAuthResponse(intent)
                Log.d(TAG, "Auth response handling completed with success: $success")

                if (success) {
                    // Initialize with the new token
                    val token = authManager.getAccessToken()
                    Log.d(TAG, "Retrieved access token (exists: ${token != null})")

                    apiClient = AniListApiClient(token)
                    Log.d(TAG, "Created new AniListApiClient with token")

                    // Check login state again to verify
                    val isLoggedIn = authManager.isLoggedIn()
                    Log.d(TAG, "Current login status: $isLoggedIn")

                    if (isLoggedIn) {
                        Log.d(TAG, "Successfully logged in, fetching user data")
                        // Fetch user data
                        refreshAllData()
                    } else {
                        Log.e(TAG, "Token was retrieved but isLoggedIn() returned false - possible token storage issue")
                        _errorMessage.value = "Login appeared successful but session validation failed"
                    }
                } else {
                    Log.e(TAG, "Authentication failed in handleAuthResponse")
                    _errorMessage.value = "Authentication failed"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling auth intent", e)
                Log.e(TAG, "Exception details: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
                _errorMessage.value = "Error during authentication: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Launch the AniList login flow
     */
    fun login() {
        authManager.launchAuthFlow(getApplication())
    }

    /**
     * Log out from AniList
     */
    fun logout() {
        authManager.logout()
        apiClient = AniListApiClient()
        clearUserData()
        loadTrendingAnime()
    }

    /**
     * Clear user-specific data
     */
    private fun clearUserData() {
        _userProfile.value = null
        _animeCollection.value = null
        _watchingAnime.value = emptyList()
        _planToWatchAnime.value = emptyList()
        _completedAnime.value = emptyList()
        _recommendations.value = emptyList()
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn() = authManager.isLoggedIn()

    /**
     * Refresh all AniList data
     */
    fun refreshAllData() {
        if (!authManager.isLoggedIn()) {
            loadTrendingAnime()
            loadPopularSeasonalAnime()
            loadPopularAllTimeAnime()
            return
        }

        loadUserProfile()
        loadUserCollection()
        loadTrendingAnime()
        loadPopularSeasonalAnime()
        loadPopularAllTimeAnime()
        loadRecommendations()
    }

    /**
     * Load user profile
     */
    private fun loadUserProfile() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val user = apiClient?.getCurrentUser()
                _userProfile.value = user
            } catch (e: Exception) {
                Log.e(TAG, "Error loading user profile", e)
                _errorMessage.value = "Error loading profile: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load user's anime collection
     */
    private fun loadUserCollection() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val collection = apiClient?.getUserAnimeCollection()
                _animeCollection.value = collection

                // Update the categorized lists
                collection?.let {
                    _watchingAnime.value = it.getWatching()
                    _planToWatchAnime.value = it.getPlanToWatch()
                    _completedAnime.value = it.getCompleted()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading anime collection", e)
                _errorMessage.value = "Error loading collection: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load trending anime
     */
    private fun loadTrendingAnime() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val trending = apiClient?.getTrendingAnime()
                _trendingAnime.value = trending ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading trending anime", e)
                _errorMessage.value = "Error loading trending anime: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load popular anime from the current season
     */
    private fun loadPopularSeasonalAnime() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val seasonal = apiClient?.getPopularSeasonalAnime()
                _popularSeasonalAnime.value = seasonal ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading seasonal anime", e)
                _errorMessage.value = "Error loading seasonal anime: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load all-time popular anime
     */
    private fun loadPopularAllTimeAnime() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val popular = apiClient?.getPopularAllTimeAnime()
                _popularAllTimeAnime.value = popular ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading all-time popular anime", e)
                _errorMessage.value = "Error loading popular anime: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load recommendations based on watching list or specific anime
     */
    private fun loadRecommendations(animeId: Int? = null) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                if (animeId != null) {
                    val recommendations = apiClient?.getAnimeRecommendations(animeId)
                    _recommendations.value = recommendations ?: emptyList()
                } else {
                    // If no specific anime ID, use the first from watching if available
                    val watching = _watchingAnime.value
                    if (!watching.isNullOrEmpty()) {
                        val recommendations = apiClient?.getAnimeRecommendations(watching[0].id)
                        _recommendations.value = recommendations ?: emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recommendations", e)
                _errorMessage.value = "Error loading recommendations: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update an anime's progress
     */
    fun updateProgress(mediaId: Int, progress: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val success = apiClient?.updateAnimeEntry(
                    mediaId = mediaId,
                    progress = progress
                ) ?: false

                if (success) {
                    // Refresh the collection data to reflect changes
                    loadUserCollection()
                } else {
                    _errorMessage.value = "Failed to update progress"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating progress", e)
                _errorMessage.value = "Error updating progress: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update an anime's status (e.g., move from Planning to Watching)
     */
    fun updateStatus(mediaId: Int, status: AniListUserMediaStatus) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                val success = apiClient?.updateAnimeEntry(
                    mediaId = mediaId,
                    status = when (status) {
                        AniListUserMediaStatus.CURRENT -> AniListMediaStatus.RELEASING
                        AniListUserMediaStatus.PLANNING -> AniListMediaStatus.NOT_YET_RELEASED
                        AniListUserMediaStatus.COMPLETED -> AniListMediaStatus.FINISHED
                        AniListUserMediaStatus.DROPPED -> AniListMediaStatus.CANCELLED
                        AniListUserMediaStatus.PAUSED -> AniListMediaStatus.HIATUS
                        AniListUserMediaStatus.REPEATING -> AniListMediaStatus.RELEASING
                    }
                ) ?: false

                if (success) {
                    // Refresh the collection data to reflect changes
                    loadUserCollection()
                } else {
                    _errorMessage.value = "Failed to update status"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating status", e)
                _errorMessage.value = "Error updating status: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Search for anime by name
     */
    fun searchAnime(query: String, callback: (List<AniListMedia>) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val results = apiClient?.searchAnime(query) ?: emptyList()
                callback(results)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching anime", e)
                _errorMessage.value = "Error searching anime: ${e.message}"
                callback(emptyList())
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load recommendations for a specific anime ID
     */
    fun searchRecommendations(animeId: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val recommendations = apiClient?.getAnimeRecommendations(animeId) ?: emptyList()
                _recommendations.value = recommendations
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recommendations for anime $animeId", e)
                _errorMessage.value = "Error loading recommendations: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clear any error messages
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * Get anime by status category
     */
    fun getAnimeByStatus(status: AniListUserMediaStatus) {
        if (!authManager.isLoggedIn()) {
            _errorMessage.value = "Login required to view your anime lists"
            _categoryAnimeList.value = emptyList()
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true

                // If we already have the collection loaded, use it
                val collection = _animeCollection.value
                if (collection != null) {
                    when (status) {
                        AniListUserMediaStatus.CURRENT -> _categoryAnimeList.value = collection.getWatching()
                        AniListUserMediaStatus.PLANNING -> _categoryAnimeList.value = collection.getPlanToWatch()
                        AniListUserMediaStatus.COMPLETED -> _categoryAnimeList.value = collection.getCompleted()
                        AniListUserMediaStatus.PAUSED -> _categoryAnimeList.value = collection.getOnHold()
                        AniListUserMediaStatus.DROPPED -> _categoryAnimeList.value = collection.getDropped()
                        AniListUserMediaStatus.REPEATING -> _categoryAnimeList.value = collection.getRepeating()
                    }
                } else {
                    // If collection isn't loaded yet, fetch it first
                    val newCollection = apiClient?.getUserAnimeCollection()
                    _animeCollection.value = newCollection

                    newCollection?.let {
                        when (status) {
                            AniListUserMediaStatus.CURRENT -> _categoryAnimeList.value = it.getWatching()
                            AniListUserMediaStatus.PLANNING -> _categoryAnimeList.value = it.getPlanToWatch()
                            AniListUserMediaStatus.COMPLETED -> _categoryAnimeList.value = it.getCompleted()
                            AniListUserMediaStatus.PAUSED -> _categoryAnimeList.value = it.getOnHold()
                            AniListUserMediaStatus.DROPPED -> _categoryAnimeList.value = it.getDropped()
                            AniListUserMediaStatus.REPEATING -> _categoryAnimeList.value = it.getRepeating()
                        }
                    } ?: run {
                        _categoryAnimeList.value = emptyList()
                        _errorMessage.value = "Failed to load anime collection"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting anime by status", e)
                _errorMessage.value = "Error loading anime list: ${e.message}"
                _categoryAnimeList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
