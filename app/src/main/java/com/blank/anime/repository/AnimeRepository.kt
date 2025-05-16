package com.blank.anime.repository

import com.blank.anime.api.RetrofitClient
import com.blank.anime.model.DownloadRequest
import com.blank.anime.model.DownloadResponse
import com.blank.anime.model.EpisodeResponse
import com.blank.anime.model.SearchResponse

class AnimeRepository {
    private val apiService = RetrofitClient.animeService

    suspend fun searchAnime(query: String): SearchResponse {
        return apiService.searchAnime(query)
    }

    suspend fun getEpisodes(
        animeId: String,
        startEpisode: Int,
        endEpisode: Int
    ): EpisodeResponse {
        return apiService.getEpisodes(animeId, startEpisode, endEpisode)
    }

    suspend fun getDownloadLink(
        animeId: String,
        episodeNum: Int,
        lang: String,
        quality: Int,
        animeTitle: String
    ): DownloadResponse {
        val request = DownloadRequest(
            anime_id = animeId,
            episode_num = episodeNum,
            lang = lang,
            quality = quality,
            anime_title = animeTitle
        )
        return apiService.getDownloadLink(request)
    }
}