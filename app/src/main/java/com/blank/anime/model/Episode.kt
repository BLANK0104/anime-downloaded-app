package com.blank.anime.model

/**
 * Represents an anime episode with download status
 */
data class Episode(
    val id: String,
    val number: Int,
    val title: String,
    val description: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val isDownloaded: Boolean = false
)
