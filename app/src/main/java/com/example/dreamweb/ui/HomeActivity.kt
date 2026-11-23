package com.example.dreamweb.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.R
import com.example.dreamweb.databinding.ActivityHomeBinding
import com.example.dreamweb.managers.Bookmark
import com.example.dreamweb.managers.HistoryEntry
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var favoritesAdapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHistoryList()
        setupFavoritesList()

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateToBrowser(binding.searchEditText.text.toString())
                true
            } else {
                false
            }
        }

        binding.goButton.setOnClickListener {
            navigateToBrowser(binding.searchEditText.text.toString())
        }

        binding.securityButton.setOnClickListener {
            startActivity(Intent(this, SecuritySettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
        loadBookmarks()
    }

    private fun setupHistoryList() {
        historyAdapter = HistoryAdapter(
            onClick = { url -> navigateToBrowser(url) },
            onLongClick = { entry -> deleteHistoryEntry(entry) }
        )
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = historyAdapter
        }
    }

    private fun setupFavoritesList() {
        favoritesAdapter = FavoritesAdapter(
            onClick = { url -> navigateToBrowser(url) },
            onLongClick = { bookmark -> deleteBookmark(bookmark) }
        )
        binding.favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = favoritesAdapter
        }
    }

    private fun loadHistory() {
        val app = application as BrowserApp
        lifecycleScope.launch {
            val history = app.container.historyManager.getRecentHistory(10)
            historyAdapter.submitList(history)
        }
    }

    private fun loadBookmarks() {
        val app = application as BrowserApp
        lifecycleScope.launch {
            val bookmarks = app.container.bookmarkManager.getAllBookmarks()
            favoritesAdapter.submitList(bookmarks)
        }
    }

    /** Borrado individual por id (FR-010): long-press DPAD_CENTER → deleteById + refresh. */
    private fun deleteHistoryEntry(entry: HistoryEntry) {
        val app = application as BrowserApp
        lifecycleScope.launch {
            app.container.historyManager.deleteById(entry.id)
            Toast.makeText(this@HomeActivity, "Entrada eliminada del historial", Toast.LENGTH_SHORT).show()
            loadHistory()
        }
    }

    private fun deleteBookmark(bookmark: Bookmark) {
        val app = application as BrowserApp
        lifecycleScope.launch {
            app.container.bookmarkManager.deleteById(bookmark.id)
            Toast.makeText(this@HomeActivity, "Favorito eliminado", Toast.LENGTH_SHORT).show()
            loadBookmarks()
        }
    }

    private fun navigateToBrowser(url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotBlank()) {
            val formattedUrl = if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
                if (trimmedUrl.contains(".")) "https://$trimmedUrl" else "https://www.google.com/search?q=$trimmedUrl"
            } else {
                trimmedUrl
            }
            val intent = Intent(this, BrowserActivity::class.java).apply {
                putExtra("URL", formattedUrl)
            }
            startActivity(intent)
        }
    }

    class HistoryAdapter(
        private val onClick: (String) -> Unit,
        private val onLongClick: (HistoryEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private var items = listOf<HistoryEntry>()

        fun submitList(newList: List<HistoryEntry>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onClick, onLongClick)
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleText: TextView = view.findViewById(R.id.titleText)
            private val urlText: TextView = view.findViewById(R.id.urlText)

            fun bind(item: HistoryEntry, onClick: (String) -> Unit, onLongClick: (HistoryEntry) -> Unit) {
                titleText.text = item.title.ifBlank { item.url }
                urlText.text = item.url
                itemView.setOnClickListener { onClick(item.url) }
                // Long-press (DPAD_CENTER mantenido) → borrado individual
                itemView.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }

    class FavoritesAdapter(
        private val onClick: (String) -> Unit,
        private val onLongClick: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

        private var items = listOf<Bookmark>()

        fun submitList(newList: List<Bookmark>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onClick, onLongClick)
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleText: TextView = view.findViewById(R.id.titleText)

            fun bind(item: Bookmark, onClick: (String) -> Unit, onLongClick: (Bookmark) -> Unit) {
                titleText.text = item.title.ifBlank { item.url }
                itemView.setOnClickListener { onClick(item.url) }
                itemView.setOnLongClickListener {
                    onLongClick(item)
                    true
                }
            }
        }
    }
}
