package com.blank.anime.model

data class EpisodeResponse(
    val episodes: Map<String, Map<String, Map<String, List<String>>>>
)