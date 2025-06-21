package com.blank.anime.api

import android.util.Log
import com.blank.anime.model.AniListMedia
import com.blank.anime.model.AniListCollection
import com.blank.anime.model.AniListUser
import com.blank.anime.model.AniListRecommendation
import com.blank.anime.model.AniListMediaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for interacting with the AniList GraphQL API
 * Documentation: https://anilist.gitbook.io/anilist-apiv2-docs/
 */
class AniListApiClient(private val accessToken: String? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://graphql.anilist.co"
    private val contentType = "application/json; charset=utf-8".toMediaType()

    // Get current user information
    suspend fun getCurrentUser(): AniListUser? {
        if (accessToken == null) return null

        val query = """
            query {
                Viewer {
                    id
                    name
                    avatar {
                        large
                    }
                    statistics {
                        anime {
                            count
                            episodesWatched
                            minutesWatched
                        }
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query)
            val userJson = response?.optJSONObject("Viewer")
            parseUserJson(userJson)
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching user: ${e.message}")
            null
        }
    }

    // Get user's anime collection (Watching, Completed, Planning, etc)
    suspend fun getUserAnimeCollection(userId: Int? = null): AniListCollection? {
        val viewerId = if (userId != null) {
            userId
        } else if (accessToken != null) {
            // Try to get current user ID if no ID provided but we have a token
            getCurrentUser()?.id
        } else {
            return null
        }

        if (viewerId == null) return null

        val query = """
            query {
                MediaListCollection(userId: $viewerId, type: ANIME) {
                    lists {
                        name
                        status
                        entries {
                            id
                            mediaId
                            status
                            progress
                            score
                            media {
                                id
                                title {
                                    romaji
                                    english
                                    native
                                }
                                coverImage {
                                    large
                                    medium
                                }
                                bannerImage
                                description
                                episodes
                                format
                                genres
                                status
                                averageScore
                                popularity
                                season
                                seasonYear
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query)
            val collectionJson = response?.optJSONObject("MediaListCollection")
            parseCollectionJson(collectionJson)
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching collection: ${e.message}")
            null
        }
    }

    // Get anime recommendations based on a specific anime ID
    suspend fun getAnimeRecommendations(animeId: Int): List<AniListRecommendation> {
        val query = """
            query {
                Media(id: $animeId) {
                    recommendations(sort: RATING_DESC) {
                        edges {
                            node {
                                rating
                                mediaRecommendation {
                                    id
                                    title {
                                        romaji
                                        english
                                        native
                                    }
                                    coverImage {
                                        large
                                        medium
                                    }
                                    description
                                    episodes
                                    format
                                    genres
                                    status
                                    averageScore
                                    popularity
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query)
            val mediaJson = response?.optJSONObject("Media")
            val recommendationsJson = mediaJson?.optJSONObject("recommendations")
            val edgesArray = recommendationsJson?.optJSONArray("edges")

            val recommendations = mutableListOf<AniListRecommendation>()

            if (edgesArray != null) {
                for (i in 0 until edgesArray.length()) {
                    val edge = edgesArray.optJSONObject(i)
                    val node = edge?.optJSONObject("node")
                    val rating = node?.optInt("rating", 0) ?: 0
                    val mediaJson = node?.optJSONObject("mediaRecommendation")

                    val media = parseMediaJson(mediaJson)
                    if (media != null) {
                        recommendations.add(AniListRecommendation(rating, media))
                    }
                }
            }

            recommendations
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching recommendations: ${e.message}")
            emptyList()
        }
    }

    // Update anime list entry
    suspend fun updateAnimeEntry(
        mediaId: Int,
        status: AniListMediaStatus? = null,
        progress: Int? = null,
        score: Int? = null
    ): Boolean {
        if (accessToken == null) return false

        val variables = JSONObject().apply {
            put("mediaId", mediaId)
            status?.let { put("status", it.name) }
            progress?.let { put("progress", it) }
            score?.let { put("score", it) }
        }

        val query = """
            mutation(${'$'}mediaId: Int!, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}score: Float) {
                SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, score: ${'$'}score) {
                    id
                    mediaId
                    status
                    progress
                    score
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query, variables.toString())
            response?.has("SaveMediaListEntry") == true
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error updating anime entry: ${e.message}")
            false
        }
    }

    // Search for anime
    suspend fun searchAnime(query: String, page: Int = 1, perPage: Int = 20): List<AniListMedia> {
        val variables = JSONObject().apply {
            put("search", query)
            put("page", page)
            put("perPage", perPage)
        }

        val graphqlQuery = """
            query(${'$'}search: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(search: ${'$'}search, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            large
                            medium
                        }
                        description
                        episodes
                        format
                        genres
                        status
                        averageScore
                        popularity
                        seasonYear
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(graphqlQuery, variables.toString())
            val pageJson = response?.optJSONObject("Page")
            val mediaArray = pageJson?.optJSONArray("media")

            val animeList = mutableListOf<AniListMedia>()

            if (mediaArray != null) {
                for (i in 0 until mediaArray.length()) {
                    val mediaJson = mediaArray.optJSONObject(i)
                    val media = parseMediaJson(mediaJson)
                    if (media != null) {
                        animeList.add(media)
                    }
                }
            }

            animeList
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error searching anime: ${e.message}")
            emptyList()
        }
    }

    // Get trending anime
    suspend fun getTrendingAnime(page: Int = 1, perPage: Int = 20): List<AniListMedia> {
        val variables = JSONObject().apply {
            put("page", page)
            put("perPage", perPage)
        }

        val query = """
            query(${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(sort: TRENDING_DESC, type: ANIME) {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            large
                            medium
                        }
                        description
                        episodes
                        format
                        genres
                        status
                        averageScore
                        popularity
                        seasonYear
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query, variables.toString())
            val pageJson = response?.optJSONObject("Page")
            val mediaArray = pageJson?.optJSONArray("media")

            val animeList = mutableListOf<AniListMedia>()

            if (mediaArray != null) {
                for (i in 0 until mediaArray.length()) {
                    val mediaJson = mediaArray.optJSONObject(i)
                    val media = parseMediaJson(mediaJson)
                    if (media != null) {
                        animeList.add(media)
                    }
                }
            }

            animeList
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching trending anime: ${e.message}")
            emptyList()
        }
    }

    // Get popular anime from the current season
    suspend fun getPopularSeasonalAnime(limit: Int = 20): List<AniListMedia> {
        val currentSeason = getCurrentSeason()
        val currentYear = getCurrentYear()

        val query = """
            query {
                Page(page: 1, perPage: $limit) {
                    media(type: ANIME, season: $currentSeason, seasonYear: $currentYear, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            large
                            medium
                        }
                        format
                        status
                        season
                        seasonYear
                        episodes
                        averageScore
                        popularity
                        genres
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query)
            val mediaArray = response?.optJSONObject("Page")?.optJSONArray("media")
            parseMediaList(mediaArray)
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching popular seasonal anime: ${e.message}")
            emptyList()
        }
    }

    // Get all-time popular anime
    suspend fun getPopularAllTimeAnime(limit: Int = 20): List<AniListMedia> {
        val query = """
            query {
                Page(page: 1, perPage: $limit) {
                    media(type: ANIME, sort: POPULARITY_DESC) {
                        id
                        title {
                            romaji
                            english
                            native
                        }
                        coverImage {
                            large
                            medium
                        }
                        format
                        status
                        season
                        seasonYear
                        episodes
                        averageScore
                        popularity
                        genres
                    }
                }
            }
        """.trimIndent()

        return try {
            val response = executeQuery(query)
            val mediaArray = response?.optJSONObject("Page")?.optJSONArray("media")
            parseMediaList(mediaArray)
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error fetching popular all-time anime: ${e.message}")
            emptyList()
        }
    }

    // Execute a GraphQL query
    private suspend fun executeQuery(query: String, variables: String? = null): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("AniListApiClient", "Executing GraphQL query: ${query.take(100)}...")

                val requestBody = JSONObject().apply {
                    put("query", query)
                    if (variables != null) {
                        put("variables", JSONObject(variables))
                        Log.d("AniListApiClient", "With variables: $variables")
                    }
                }.toString().toRequestBody(contentType)

                val request = Request.Builder()
                    .url(baseUrl)
                    .post(requestBody)
                    .apply {
                        accessToken?.let {
                            header("Authorization", "Bearer $it")
                            Log.d("AniListApiClient", "Request includes Authorization header")
                        }
                    }
                    .build()

                Log.d("AniListApiClient", "Sending request to $baseUrl")
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    Log.e("AniListApiClient", "Request failed: ${response.code}, ${response.message}")
                    return@withContext null
                }

                Log.d("AniListApiClient", "Received successful response with ${responseBody.length} characters")

                val jsonObject = JSONObject(responseBody)

                if (jsonObject.has("errors")) {
                    val errorsArray = jsonObject.getJSONArray("errors")
                    if (errorsArray.length() > 0) {
                        val firstError = errorsArray.getJSONObject(0)
                        val message = firstError.optString("message", "Unknown error")
                        Log.e("AniListApiClient", "GraphQL error: $message")
                        return@withContext null
                    }
                }

                val data = jsonObject.optJSONObject("data")
                Log.d("AniListApiClient", "Response data object present: ${data != null}")

                return@withContext data
            } catch (e: IOException) {
                Log.e("AniListApiClient", "Network error: ${e.message}", e)
                null
            } catch (e: JSONException) {
                Log.e("AniListApiClient", "JSON parsing error: ${e.message}", e)
                null
            }
        }
    }

    // Parse JSON responses to model classes
    private fun parseUserJson(json: JSONObject?): AniListUser? {
        if (json == null) return null

        return try {
            val id = json.getInt("id")
            val name = json.getString("name")

            val avatarJson = json.optJSONObject("avatar")
            val avatarUrl = avatarJson?.optString("large")

            val statsJson = json.optJSONObject("statistics")
            val animeStatsJson = statsJson?.optJSONObject("anime")

            val count = animeStatsJson?.optInt("count", 0) ?: 0
            val episodesWatched = animeStatsJson?.optInt("episodesWatched", 0) ?: 0
            val minutesWatched = animeStatsJson?.optInt("minutesWatched", 0) ?: 0

            AniListUser(
                id = id,
                name = name,
                avatarUrl = avatarUrl,
                animeCount = count,
                episodesWatched = episodesWatched,
                minutesWatched = minutesWatched
            )
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error parsing user JSON: ${e.message}")
            null
        }
    }

    private fun parseCollectionJson(json: JSONObject?): AniListCollection? {
        if (json == null) return null

        return try {
            val lists = mutableMapOf<String, List<AniListMedia>>()

            val listsArray = json.optJSONArray("lists")
            if (listsArray != null) {
                for (i in 0 until listsArray.length()) {
                    val listJson = listsArray.getJSONObject(i)
                    val name = listJson.getString("name")
                    val entriesArray = listJson.getJSONArray("entries")

                    val mediaList = mutableListOf<AniListMedia>()

                    for (j in 0 until entriesArray.length()) {
                        val entryJson = entriesArray.getJSONObject(j)
                        val mediaJson = entryJson.getJSONObject("media")

                        val media = parseMediaJson(mediaJson)
                        if (media != null) {
                            // Add progress and score from the entry
                            val progress = entryJson.optInt("progress", 0)
                            val score = entryJson.optInt("score", 0)

                            mediaList.add(media.copy(progress = progress, userScore = score))
                        }
                    }

                    lists[name] = mediaList
                }
            }

            AniListCollection(lists)
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error parsing collection JSON: ${e.message}")
            null
        }
    }

    private fun parseMediaJson(json: JSONObject?): AniListMedia? {
        if (json == null) return null

        return try {
            val id = json.getInt("id")

            val titleJson = json.getJSONObject("title")
            val titleRomaji = titleJson.optString("romaji")
            val titleEnglish = titleJson.optString("english")
            val titleNative = titleJson.optString("native")

            val coverImageJson = json.getJSONObject("coverImage")
            val coverLarge = coverImageJson.optString("large")
            val coverMedium = coverImageJson.optString("medium")

            val bannerImage = json.optString("bannerImage", null)
            val description = json.optString("description", "").replace("<br>", "\n")
            val episodes = json.optInt("episodes", 0)
            val format = json.optString("format", "TV")

            val genresArray = json.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    genres.add(genresArray.getString(i))
                }
            }

            val statusString = json.optString("status", "UNKNOWN")
            val status = try {
                AniListMediaStatus.valueOf(statusString)
            } catch (e: Exception) {
                AniListMediaStatus.UNKNOWN
            }

            val averageScore = json.optInt("averageScore", 0)
            val popularity = json.optInt("popularity", 0)
            val seasonYear = json.optInt("seasonYear", 0)

            AniListMedia(
                id = id,
                titleRomaji = titleRomaji,
                titleEnglish = titleEnglish,
                titleNative = titleNative,
                coverImageLarge = coverLarge,
                coverImageMedium = coverMedium,
                bannerImage = bannerImage,
                description = description,
                episodes = episodes,
                format = format,
                genres = genres,
                status = status,
                averageScore = averageScore,
                popularity = popularity,
                seasonYear = seasonYear
            )
        } catch (e: Exception) {
            Log.e("AniListApiClient", "Error parsing media JSON: ${e.message}")
            null
        }
    }

    // Helper method to determine current anime season
    private fun getCurrentSeason(): String {
        val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)
        return when (month) {
            in 0..2 -> "WINTER" // Jan-Mar
            in 3..5 -> "SPRING" // Apr-Jun
            in 6..8 -> "SUMMER" // Jul-Sep
            else -> "FALL" // Oct-Dec
        }
    }

    // Helper method to get current year
    private fun getCurrentYear(): Int {
        return java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    }

    // Parse media list from JSON array
    private fun parseMediaList(mediaArray: JSONArray?): List<AniListMedia> {
        val mediaList = mutableListOf<AniListMedia>()

        if (mediaArray != null) {
            for (i in 0 until mediaArray.length()) {
                val mediaJson = mediaArray.optJSONObject(i)
                val media = parseMediaJson(mediaJson)
                if (media != null) {
                    mediaList.add(media)
                }
            }
        }

        return mediaList
    }
}
