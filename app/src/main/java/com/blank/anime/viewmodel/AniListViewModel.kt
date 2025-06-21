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
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

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
        Log.d(TAG, "refreshAllData: Starting to refresh all data")

        // First, make sure we have a valid API client
        if (apiClient == null) {
            apiClient = AniListApiClient(authManager.getAccessToken())
            Log.d(TAG, "refreshAllData: Created new API client")
        } else {
            Log.d(TAG, "refreshAllData: Using existing API client")
        }

        viewModelScope.launch {
            _isLoading.value = true
            Log.d(TAG, "refreshAllData: Setting loading state to true")

            try {
                if (!authManager.isLoggedIn()) {
                    // For non-logged-in users, load public data
                    Log.d(TAG, "refreshAllData: User not logged in, loading public data only")

                    Log.d(TAG, "refreshAllData: Loading trending anime")
                    loadTrendingAnime(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading seasonal anime")
                    loadPopularSeasonalAnime(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading all-time popular anime")
                    loadPopularAllTimeAnime(true) // Use detailed logging
                } else {
                    // For logged-in users, load everything
                    Log.d(TAG, "refreshAllData: User logged in, loading all data")

                    Log.d(TAG, "refreshAllData: Loading user profile")
                    loadUserProfile(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading user collection")
                    loadUserCollection(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading trending anime")
                    loadTrendingAnime(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading seasonal anime")
                    loadPopularSeasonalAnime(true) // Use detailed logging

                    Log.d(TAG, "refreshAllData: Loading all-time popular anime")
                    loadPopularAllTimeAnime(true) // Use detailed logging
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in refreshAllData: ${e.message}", e)
                _errorMessage.value = "Error refreshing data: ${e.message}"
            } finally {
                Log.d(TAG, "refreshAllData: Setting loading state to false")
                _isLoading.value = false
            }
        }
    }

    /**
     * Try to load real data from the API, return true if successful
     */
    private suspend fun tryLoadRealData(): Boolean {
        try {
            // Try to load trending anime
            val trending = apiClient?.getTrendingAnime()
            if (trending != null && trending.isNotEmpty()) {
                // Success! Use this data
                _trendingAnime.value = trending

                // Wait a long time before next request (10 seconds)
                kotlinx.coroutines.delay(10000)

                // Try to load seasonal anime
                val seasonal = apiClient?.getPopularSeasonalAnime()
                if (seasonal != null) {
                    _popularSeasonalAnime.value = seasonal
                }

                // Wait again
                kotlinx.coroutines.delay(10000)

                // Try for popular all-time
                val popular = apiClient?.getPopularAllTimeAnime()
                if (popular != null) {
                    _popularAllTimeAnime.value = popular
                }

                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading real data: ${e.message}")
            return false
        }
    }

    /**
     * Load mock anime data when API fails
     */
    private fun loadMockData() {
        Log.d(TAG, "loadMockData: Loading mock anime data")

        // Create and load mock trending anime
        val mockTrending = createMockAnimeList("Trending", 10)
        _trendingAnime.value = mockTrending

        // Mock seasonal anime
        val mockSeasonal = createMockAnimeList("Spring 2025", 10)
        _popularSeasonalAnime.value = mockSeasonal

        // Mock all-time popular
        val mockPopular = createMockAnimeList("Popular", 10)
        _popularAllTimeAnime.value = mockPopular

        // If user is logged in, create some mock user lists
        if (authManager.isLoggedIn()) {
            val mockWatching = createMockAnimeList("Watching", 5)
            _watchingAnime.value = mockWatching

            val mockPlanToWatch = createMockAnimeList("Plan to Watch", 5)
            _planToWatchAnime.value = mockPlanToWatch
        }
    }

    /**
     * Create a list of mock anime for testing
     */
    private fun createMockAnimeList(prefix: String, count: Int): List<AniListMedia> {
        val result = mutableListOf<AniListMedia>()
        for (i in 1..count) {
            result.add(
                AniListMedia(
                    id = i * 1000,
                    titleRomaji = "$prefix Anime $i",
                    titleEnglish = "$prefix Show $i",
                    titleNative = "$prefix アニメ $i",
                    coverImageLarge = "https://via.placeholder.com/225x350?text=Anime+$i",
                    coverImageMedium = "https://via.placeholder.com/112x175?text=Anime+$i",
                    bannerImage = "https://via.placeholder.com/1000x300?text=Banner+$i",
                    description = "This is a mock anime description for testing purposes when the API is unavailable due to rate limiting.",
                    episodes = (i * 3 + 10),
                    format = "TV",
                    genres = listOf("Action", "Adventure", "Fantasy", "Comedy"),
                    status = AniListMediaStatus.RELEASING,
                    averageScore = (65 + i * 2),
                    popularity = 1000 * i,
                    seasonYear = 2025
                )
            )
        }
        return result
    }

    /**
     * Load user collection and then recommendations sequentially
     */
    private suspend fun loadUserCollectionSequentially() {
        try {
            // Load user profile first
            val user = apiClient?.getCurrentUser()
            _userProfile.value = user

            // Wait before next request
            kotlinx.coroutines.delay(1000)

            // Load collection
            val collection = apiClient?.getUserAnimeCollection()
            _animeCollection.value = collection

            // Update the categorized lists
            collection?.let {
                _watchingAnime.value = it.getWatching()
                _planToWatchAnime.value = it.getPlanToWatch()
                _completedAnime.value = it.getCompleted()
            }

            // Wait before next request
            kotlinx.coroutines.delay(2000)

            // Load trending anime
            val trending = apiClient?.getTrendingAnime()
            _trendingAnime.value = trending ?: emptyList()

            // Wait before next request
            kotlinx.coroutines.delay(2000)

            // Load seasonal anime
            val seasonal = apiClient?.getPopularSeasonalAnime()
            _popularSeasonalAnime.value = seasonal ?: emptyList()

            // Wait before next request
            kotlinx.coroutines.delay(2000)

            // Load all-time popular anime
            val popular = apiClient?.getPopularAllTimeAnime()
            _popularAllTimeAnime.value = popular ?: emptyList()

            // Wait before recommendations
            kotlinx.coroutines.delay(1000)

            // Load recommendations if possible
            val watching = _watchingAnime.value
            if (!watching.isNullOrEmpty()) {
                val recommendations = apiClient?.getAnimeRecommendations(watching[0].id)
                _recommendations.value = recommendations ?: emptyList()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user data sequentially", e)
            _errorMessage.value = "Error loading user data: ${e.message}"
        }
    }

    /**
     * Load user profile
     */
    private fun loadUserProfile(detailedLogs: Boolean = false) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                if (detailedLogs) Log.d(TAG, "loadUserProfile: Starting to load user profile")

                val user = apiClient?.getCurrentUser()
                if (detailedLogs) Log.d(TAG, "loadUserProfile: Received user data: ${user != null}")

                _userProfile.value = user
                if (detailedLogs) Log.d(TAG, "loadUserProfile: Updated _userProfile LiveData")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading user profile: ${e.message}", e)
                _errorMessage.value = "Error loading profile: ${e.message}"
            } finally {
                _isLoading.value = false
                if (detailedLogs) Log.d(TAG, "loadUserProfile: Finished loading user profile")
            }
        }
    }

    /**
     * Load user's anime collection
     */
    private fun loadUserCollection(detailedLogs: Boolean = false) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                if (detailedLogs) Log.d(TAG, "loadUserCollection: Starting to load user collection")

                val collection = apiClient?.getUserAnimeCollection()
                if (detailedLogs) Log.d(TAG, "loadUserCollection: Received collection data: ${collection != null}")

                _animeCollection.value = collection
                if (detailedLogs) Log.d(TAG, "loadUserCollection: Updated _animeCollection LiveData")

                // Update the categorized lists
                collection?.let {
                    val watching = it.getWatching()
                    if (detailedLogs) Log.d(TAG, "loadUserCollection: Got ${watching.size} watching anime")
                    _watchingAnime.value = watching

                    val planning = it.getPlanToWatch()
                    if (detailedLogs) Log.d(TAG, "loadUserCollection: Got ${planning.size} plan-to-watch anime")
                    _planToWatchAnime.value = planning

                    val completed = it.getCompleted()
                    if (detailedLogs) Log.d(TAG, "loadUserCollection: Got ${completed.size} completed anime")
                    _completedAnime.value = completed

                    if (detailedLogs) Log.d(TAG, "loadUserCollection: Updated categorized lists in LiveData")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading anime collection: ${e.message}", e)
                _errorMessage.value = "Error loading collection: ${e.message}"
            } finally {
                _isLoading.value = false
                if (detailedLogs) Log.d(TAG, "loadUserCollection: Finished loading user collection")
            }
        }
    }

    /**
     * Load trending anime
     */
    private fun loadTrendingAnime(detailedLogs: Boolean = false) {
        viewModelScope.launch {
            try {
                if (detailedLogs) Log.d(TAG, "loadTrendingAnime: Starting to load trending anime")
                _isLoading.value = true

                if (detailedLogs) Log.d(TAG, "loadTrendingAnime: API client null? ${apiClient == null}")
                val trending = apiClient?.getTrendingAnime()
                if (detailedLogs) Log.d(TAG, "loadTrendingAnime: Received ${trending?.size ?: 0} trending anime items")

                _trendingAnime.value = trending ?: emptyList()
                if (detailedLogs) Log.d(TAG, "loadTrendingAnime: Updated _trendingAnime LiveData with ${trending?.size ?: 0} items")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading trending anime: ${e.message}", e)
                _errorMessage.value = "Error loading trending anime: ${e.message}"
            } finally {
                _isLoading.value = false
                if (detailedLogs) Log.d(TAG, "loadTrendingAnime: Finished loading trending anime")
            }
        }
    }

    /**
     * Load popular anime from the current season
     */
    private fun loadPopularSeasonalAnime(detailedLogs: Boolean = false) {
        viewModelScope.launch {
            try {
                if (detailedLogs) Log.d(TAG, "loadPopularSeasonalAnime: Starting to load seasonal anime")
                _isLoading.value = true

                if (detailedLogs) Log.d(TAG, "loadPopularSeasonalAnime: API client null? ${apiClient == null}")
                val seasonal = apiClient?.getPopularSeasonalAnime()
                if (detailedLogs) Log.d(TAG, "loadPopularSeasonalAnime: Received ${seasonal?.size ?: 0} seasonal anime items")

                _popularSeasonalAnime.value = seasonal ?: emptyList()
                if (detailedLogs) Log.d(TAG, "loadPopularSeasonalAnime: Updated _popularSeasonalAnime LiveData with ${seasonal?.size ?: 0} items")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading seasonal anime: ${e.message}", e)
                _errorMessage.value = "Error loading seasonal anime: ${e.message}"
            } finally {
                _isLoading.value = false
                if (detailedLogs) Log.d(TAG, "loadPopularSeasonalAnime: Finished loading seasonal anime")
            }
        }
    }

    /**
     * Load all-time popular anime
     */
    private fun loadPopularAllTimeAnime(detailedLogs: Boolean = false) {
        viewModelScope.launch {
            try {
                if (detailedLogs) Log.d(TAG, "loadPopularAllTimeAnime: Starting to load all-time popular anime")
                _isLoading.value = true

                if (detailedLogs) Log.d(TAG, "loadPopularAllTimeAnime: API client null? ${apiClient == null}")
                val popular = apiClient?.getPopularAllTimeAnime()
                if (detailedLogs) Log.d(TAG, "loadPopularAllTimeAnime: Received ${popular?.size ?: 0} all-time popular anime items")

                _popularAllTimeAnime.value = popular ?: emptyList()
                if (detailedLogs) Log.d(TAG, "loadPopularAllTimeAnime: Updated _popularAllTimeAnime LiveData with ${popular?.size ?: 0} items")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading all-time popular anime: ${e.message}", e)
                _errorMessage.value = "Error loading popular anime: ${e.message}"
            } finally {
                _isLoading.value = false
                if (detailedLogs) Log.d(TAG, "loadPopularAllTimeAnime: Finished loading all-time popular anime")
            }
        }
    }

    /**
     * Try to refresh the API client and execute the given API call
     */
    private suspend fun <T> refreshAndExecute(
        apiCall: suspend () -> T?,
        onSuccess: (T) -> Unit,
        errorMessage: String = "API call failed",
        maxRetries: Int = 1
    ) {
        var retries = 0
        var success = false

        while (!success && retries <= maxRetries) {
            try {
                // Check if we need to refresh API client
                if (apiClient == null || authManager.tokenWillExpireSoon()) {
                    Log.d(TAG, "API client needs initialization or token refresh")
                    initApiClient()
                }

                // Execute the API call
                val result = apiCall()

                if (result != null) {
                    onSuccess(result)
                    success = true
                } else {
                    Log.e(TAG, "API call returned null result")
                    retries++

                    if (retries <= maxRetries) {
                        Log.d(TAG, "Retrying API call (attempt $retries of $maxRetries)")
                        // Force token refresh on retry
                        authManager.getAccessToken()
                        initApiClient()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in API call: ${e.message}")
                retries++

                if (retries <= maxRetries) {
                    Log.d(TAG, "Retrying API call after error (attempt $retries of $maxRetries)")
                    // Force token refresh on error
                    authManager.getAccessToken()
                    initApiClient()
                } else {
                    _errorMessage.value = "$errorMessage: ${e.message}"
                }
            }
        }

        if (!success) {
            _errorMessage.value = errorMessage
        }
    }

    // Initialize or reinitialize the API client with the current token
    private fun initApiClient() {
        val token = authManager.getAccessToken()
        if (token != null) {
            apiClient = AniListApiClient(token)
            Log.d(TAG, "API client initialized with token")
        } else {
            apiClient = null
            Log.d(TAG, "No token available, API client not initialized")
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

    /**
     * Update an anime's progress
     */
    fun updateProgress(mediaId: Int, progress: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "Updating progress for anime $mediaId to episode $progress")

                // Make sure we have a valid API client
                if (apiClient == null) {
                    apiClient = AniListApiClient(authManager.getAccessToken())
                }

                val success = apiClient?.updateAnimeEntry(
                    mediaId = mediaId,
                    progress = progress
                ) ?: false

                if (success) {
                    Log.d(TAG, "Successfully updated progress for anime $mediaId")
                    // Refresh the collection data to reflect changes
                    loadUserCollection()
                } else {
                    Log.e(TAG, "Failed to update progress for anime $mediaId")
                    _errorMessage.value = "Failed to update progress"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating progress: ${e.message}", e)
                _errorMessage.value = "Error updating progress: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update an anime's status in the user's collection
     */
    fun updateStatus(mediaId: Int, status: AniListUserMediaStatus) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "Updating status for anime $mediaId to $status")

                // Make sure we have a valid API client
                if (apiClient == null) {
                    apiClient = AniListApiClient(authManager.getAccessToken())
                }

                // Convert AniListUserMediaStatus to AniListMediaStatus if needed
                // Note: For now we're assuming they match by name. If they don't, we need to map them.
                val success = apiClient?.updateAnimeEntry(
                    mediaId = mediaId,
                    status = com.blank.anime.model.AniListMediaStatus.valueOf(status.name)
                ) ?: false

                if (success) {
                    Log.d(TAG, "Successfully updated status for anime $mediaId to $status")
                    // Refresh the collection data to reflect changes
                    loadUserCollection()
                } else {
                    Log.e(TAG, "Failed to update status for anime $mediaId")
                    _errorMessage.value = "Failed to update anime status"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating anime status: ${e.message}", e)
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
                Log.d(TAG, "searchAnime: Searching for anime with query: $query")

                // Make sure we have a valid API client
                if (apiClient == null) {
                    apiClient = AniListApiClient(authManager.getAccessToken())
                    Log.d(TAG, "searchAnime: Created new API client")
                }

                val results = apiClient?.searchAnime(query) ?: emptyList()
                Log.d(TAG, "searchAnime: Found ${results.size} results")

                callback(results)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching anime: ${e.message}", e)
                _errorMessage.value = "Error searching anime: ${e.message}"
                callback(emptyList())
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Search for anime recommendations based on the given anime ID
     */
    fun searchRecommendations(animeId: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "searchRecommendations: Starting to load recommendations for anime ID: $animeId")

                val recommendations = apiClient?.getAnimeRecommendations(animeId)
                Log.d(TAG, "searchRecommendations: Received ${recommendations?.size ?: 0} recommendations")

                _recommendations.value = recommendations ?: emptyList()
                Log.d(TAG, "searchRecommendations: Updated _recommendations LiveData with ${recommendations?.size ?: 0} items")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading recommendations: ${e.message}", e)
                _errorMessage.value = "Error loading recommendations: ${e.message}"
            } finally {
                _isLoading.value = false
                Log.d(TAG, "searchRecommendations: Finished loading recommendations")
            }
        }
    }
}
