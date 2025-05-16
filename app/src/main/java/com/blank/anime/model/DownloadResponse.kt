package com.blank.anime.model

data class DownloadResponse(
    val message: String,
    val download_link: String,
    val quality: Int,
    val language: String,
    val episode: Int,
    val anime_title: String
)