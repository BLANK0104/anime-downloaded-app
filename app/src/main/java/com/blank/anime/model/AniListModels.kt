package com.blank.anime.model

/**
 * Represents an AniList user account
 */
data class AniListUser(
    val id: Int,
    val name: String,
    val avatarUrl: String? = null,
    val animeCount: Int = 0,
    val episodesWatched: Int = 0,
    val minutesWatched: Int = 0
)

/**
 * Represents an anime collection from AniList
 * (sorted by categories like Watching, Completed, etc.)
 */
data class AniListCollection(
    val lists: Map<String, List<AniListMedia>>
) {
    fun getAllMedia(): List<AniListMedia> {
        return lists.values.flatten()
    }

    fun getWatching(): List<AniListMedia> {
        return lists["Watching"] ?: emptyList()
    }

    fun getCompleted(): List<AniListMedia> {
        return lists["Completed"] ?: emptyList()
    }

    fun getPlanToWatch(): List<AniListMedia> {
        return lists["Planning"] ?: emptyList()
    }

    fun getOnHold(): List<AniListMedia> {
        return lists["Paused"] ?: emptyList()
    }

    fun getDropped(): List<AniListMedia> {
        return lists["Dropped"] ?: emptyList()
    }

    fun getRepeating(): List<AniListMedia> {
        return lists["Repeating"] ?: emptyList()
    }

    fun findMediaById(id: Int): AniListMedia? {
        return getAllMedia().find { it.id == id }
    }
}

/**
 * Status of an anime on AniList
 */
enum class AniListMediaStatus {
    FINISHED,
    RELEASING,
    NOT_YET_RELEASED,
    CANCELLED,
    HIATUS,
    UNKNOWN
}

/**
 * Status of a user's anime list entry
 */
enum class AniListUserMediaStatus {
    CURRENT,
    PLANNING,
    COMPLETED,
    DROPPED,
    PAUSED,
    REPEATING
}

/**
 * Represents an anime media item from AniList
 */
data class AniListMedia(
    val id: Int,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    val coverImageLarge: String?,
    val coverImageMedium: String?,
    val bannerImage: String? = null,
    val description: String? = null,
    val episodes: Int = 0,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val status: AniListMediaStatus = AniListMediaStatus.UNKNOWN,
    val averageScore: Int = 0,
    val popularity: Int = 0,
    val seasonYear: Int = 0,
    val progress: Int = 0,
    val userScore: Int = 0
) {
    fun getPreferredTitle(): String {
        return when {
            !titleEnglish.isNullOrBlank() -> titleEnglish
            !titleRomaji.isNullOrBlank() -> titleRomaji
            !titleNative.isNullOrBlank() -> titleNative
            else -> "Unknown Anime"
        }
    }

    fun getFormattedScore(): String {
        return if (averageScore > 0) {
            "${averageScore / 10.0}"
        } else {
            "N/A"
        }
    }

    fun getProgressText(): String {
        return if (episodes > 0) {
            "$progress/$episodes"
        } else {
            "$progress/?"
        }
    }
}

/**
 * Represents an anime recommendation from AniList
 */
data class AniListRecommendation(
    val rating: Int,
    val media: AniListMedia
)
