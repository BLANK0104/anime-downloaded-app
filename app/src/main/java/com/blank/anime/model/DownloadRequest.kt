package com.blank.anime.model

data class DownloadRequest(
    val anime_id: String,
    val episode_num: Int,
    val lang: String,
    val quality: Int,
    val anime_title: String
)