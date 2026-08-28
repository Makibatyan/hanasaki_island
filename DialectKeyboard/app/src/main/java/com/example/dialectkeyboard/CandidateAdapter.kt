package com.example.dialectkeyboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CandidateAdapter(
    private val onCandidateClick: (DialectEntry) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {

    private val candidates = mutableListOf<DialectEntry>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textCandidate: TextView = view.findViewById(R.id.tv_word)
        val textRegion: TextView? = view.findViewById(R.id.tv_desc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_candidate, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = candidates[position]

        holder.textCandidate.text = entry.word

        // 説明欄に【意味】＋地域を表示
        holder.textRegion?.text = entry.description

        holder.itemView.setOnClickListener {
            onCandidateClick(entry)
        }
    }

    override fun getItemCount(): Int = candidates.size

    @Suppress("NotifyDataSetChanged")
    fun updateCandidates(newCandidates: List<DialectEntry>) {
        candidates.clear()
        candidates.addAll(newCandidates)
        notifyDataSetChanged()
    }
}