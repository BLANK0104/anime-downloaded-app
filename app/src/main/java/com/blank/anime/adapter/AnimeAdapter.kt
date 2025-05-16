package com.blank.anime.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blank.anime.R
import com.blank.anime.model.AnimeSearchResult

class AnimeAdapter(private val onItemClick: (AnimeSearchResult) -> Unit) :
    ListAdapter<AnimeSearchResult, AnimeAdapter.AnimeViewHolder>(AnimeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnimeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_anime, parent, false)
        return AnimeViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnimeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AnimeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.anime_title)
        private val typeYearTextView: TextView = itemView.findViewById(R.id.anime_type_year)
        private val episodesTextView: TextView = itemView.findViewById(R.id.anime_episodes)
        private val scoreTextView: TextView = itemView.findViewById(R.id.anime_score)
        private val statusTextView: TextView = itemView.findViewById(R.id.anime_status)

        fun bind(anime: AnimeSearchResult) {
            titleTextView.text = anime.title
            typeYearTextView.text = "${anime.type} • ${anime.year}"
            episodesTextView.text = "Episodes: ${anime.episodes}"
            scoreTextView.text = anime.score.toString()
            statusTextView.text = anime.status

            itemView.setOnClickListener { onItemClick(anime) }
        }
    }

    class AnimeDiffCallback : DiffUtil.ItemCallback<AnimeSearchResult>() {
        override fun areItemsTheSame(oldItem: AnimeSearchResult, newItem: AnimeSearchResult): Boolean {
            return oldItem.session_id == newItem.session_id
        }

        override fun areContentsTheSame(oldItem: AnimeSearchResult, newItem: AnimeSearchResult): Boolean {
            return oldItem == newItem
        }
    }
}