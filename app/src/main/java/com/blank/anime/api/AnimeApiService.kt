package com.blank.anime.api

import com.blank.anime.model.DownloadRequest
import com.blank.anime.model.DownloadResponse
import com.blank.anime.model.EpisodeResponse
import com.blank.anime.model.SearchResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AnimeApiService {
    @GET("api/search")
    suspend fun searchAnime(@Query("query") query: String): SearchResponse

    @GET("api/episodes")
    suspend fun getEpisodes(
        @Query("anime_id") animeId: String,
        @Query("start_episode") startEpisode: Int,
        @Query("end_episode") endEpisode: Int
    ): EpisodeResponse

    @POST("api/download")
    suspend fun getDownloadLink(@Body request: DownloadRequest): DownloadResponse
}