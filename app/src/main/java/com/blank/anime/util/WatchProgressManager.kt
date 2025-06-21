package com.blank.anime.util

import android.content.Context
import java.util.concurrent.TimeUnit

class WatchProgressManager(context: Context) {
    private val prefs = context.getSharedPreferences("watch_progress", Context.MODE_PRIVATE)

    fun saveProgress(animeTitle: String, episodeNumber: Int, position: Long, duration: Long) {
        val key = "${animeTitle}_episode_${episodeNumber}"
        prefs.edit()
            .putLong("${key}_position", position)
            .putLong("${key}_duration", duration)
            .putLong("${key}_timestamp", System.currentTimeMillis())
            .apply()
    }

    fun getPosition(animeTitle: String, episodeNumber: Int): Long {
        val key = "${animeTitle}_episode_${episodeNumber}"
        return prefs.getLong("${key}_position", 0L)
    }

    fun getDuration(animeTitle: String, episodeNumber: Int): Long {
        val key = "${animeTitle}_episode_${episodeNumber}"
        return prefs.getLong("${key}_duration", 0L)
    }

    fun getProgressText(animeTitle: String, episodeNumber: Int): String {
        val position = getPosition(animeTitle, episodeNumber)
        val duration = getDuration(animeTitle, episodeNumber)
        return if (duration > 0) "${formatTime(position)} / ${formatTime(duration)}" else ""
    }

    fun isWatched(animeTitle: String, episodeNumber: Int): Boolean {
        val position = getPosition(animeTitle, episodeNumber)
        val duration = getDuration(animeTitle, episodeNumber)
        // Consider watched if >90% complete
        return duration > 0 && position >= (duration * 0.9)
    }

    fun formatTime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}