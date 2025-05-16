package com.blank.anime.model

data class AnimeSearchResult(
    val episodes: Int,
    val score: Double,
    val session_id: String,
    val status: String,
    val title: String,
    val type: String,
    val year: Int
)

data class SearchResponse(
    val results: List<AnimeSearchResult>
)