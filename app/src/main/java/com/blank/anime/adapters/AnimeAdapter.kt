package com.blank.anime.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.blank.anime.R
import com.blank.anime.model.AniListMedia

/**
 * Adapter for anime posters with titles, scores, and episode progress
 */
class AnimeAdapter(
    private val onAnimeClick: (AniListMedia) -> Unit
) : ListAdapter<AniListMedia, AnimeAdapter.AnimeViewHolder>(AnimeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_anime_poster, parent, false)
        return AnimeViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        val anime = getItem(position)
        holder.bind(anime, onAnimeClick)
    }

    class AnimeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cover: ImageView = itemView.findViewById(R.id.anime_cover)
        private val title: TextView = itemView.findViewById(R.id.anime_title)
        private val score: TextView = itemView.findViewById(R.id.anime_score)
        private val progress: TextView = itemView.findViewById(R.id.episode_progress)

        fun bind(anime: AniListMedia, onAnimeClick: (AniListMedia) -> Unit) {
            // Set title
            title.text = anime.getPreferredTitle()

            // Set cover image
            anime.coverImageLarge?.let { url ->
                cover.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                }
            } ?: run {
                cover.setImageResource(R.drawable.ic_launcher_foreground)
            }

            // Set score with proper visibility
            if (anime.averageScore > 0) {
                score.visibility = View.VISIBLE
                score.text = anime.getFormattedScore()
            } else {
                score.visibility = View.GONE
            }

            // Set progress with proper visibility
            if (anime.progress > 0) {
                progress.visibility = View.VISIBLE
                progress.text = anime.getProgressText()
            } else {
                progress.visibility = View.GONE
            }

            // Set click listener
            itemView.setOnClickListener {
                onAnimeClick(anime)
            }
        }
    }

    class AnimeDiffCallback : DiffUtil.ItemCallback<AniListMedia>() {
        override fun areItemsTheSame(oldItem: AniListMedia, newItem: AniListMedia): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AniListMedia, newItem: AniListMedia): Boolean {
            return oldItem == newItem
        }
    }
}
