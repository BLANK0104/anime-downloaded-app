package com.blank.anime.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.blank.anime.R
import com.blank.anime.model.Episode

/**
 * Adapter for anime episodes with download/play functionality
 */
class EpisodeAdapter(
    private val onEpisodeClick: (Episode) -> Unit,
    private val onDownloadClick: (Episode) -> Unit
) : ListAdapter<Episode, EpisodeAdapter.EpisodeViewHolder>(EpisodeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return EpisodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        val episode = getItem(position)
        holder.bind(episode, onEpisodeClick, onDownloadClick)
    }

    class EpisodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.episode_thumbnail)
        private val title: TextView = itemView.findViewById(R.id.episode_title)
        private val number: TextView = itemView.findViewById(R.id.episode_number)
        private val downloadButton: Button = itemView.findViewById(R.id.download_button)

        fun bind(
            episode: Episode,
            onEpisodeClick: (Episode) -> Unit,
            onDownloadClick: (Episode) -> Unit
        ) {
            // Set episode title
            title.text = episode.title

            // Set episode number
            number.text = "Episode ${episode.number}"

            // Load thumbnail
            episode.thumbnail?.let { url ->
                thumbnail.load(url) {
                    crossfade(true)
                    placeholder(R.drawable.ic_launcher_foreground)
                }
            }

            // Handle episode availability
            if (!episode.isAvailable) {
                // If episode isn't available yet, show it as "Coming Soon"
                downloadButton.text = "Coming Soon"
                downloadButton.isEnabled = false
                downloadButton.alpha = 0.5f
                downloadButton.setCompoundDrawablesWithIntrinsicBounds(
                    R.drawable.ic_timer, 0, 0, 0
                )

                // Mark the whole item as partially disabled
                itemView.alpha = 0.7f
                itemView.isClickable = false
            } else {
                // Reset alpha for available episodes
                itemView.alpha = 1.0f
                itemView.isClickable = true
                downloadButton.isEnabled = true
                downloadButton.alpha = 1.0f

                // Set download button text based on download state
                if (episode.isDownloaded) {
                    downloadButton.text = "Play"
                    downloadButton.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_play, 0, 0, 0
                    )
                } else {
                    downloadButton.text = "Download"
                    downloadButton.setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_download, 0, 0, 0
                    )
                }

                // Set click listeners
                itemView.setOnClickListener {
                    onEpisodeClick(episode)
                }

                downloadButton.setOnClickListener {
                    onDownloadClick(episode)
                }
            }
        }
    }

    class EpisodeDiffCallback : DiffUtil.ItemCallback<Episode>() {
        override fun areItemsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Episode, newItem: Episode): Boolean {
            return oldItem == newItem
        }
    }
}
