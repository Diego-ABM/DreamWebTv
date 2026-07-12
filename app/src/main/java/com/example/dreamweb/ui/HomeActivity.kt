package com.example.dreamweb.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.R
import com.example.dreamweb.databinding.ActivityHomeBinding
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
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
        loadBookmarks()
    }

    private fun setupHistoryList() {
        historyAdapter = HistoryAdapter { url ->
            navigateToBrowser(url)
        }
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = historyAdapter
        }
    }

    private fun setupFavoritesList() {
        favoritesAdapter = FavoritesAdapter { url ->
            navigateToBrowser(url)
        }
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

    class HistoryAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        
        private var items = listOf<Pair<String, String>>()

        fun submitList(newList: List<Pair<String, String>>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onClick)
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleText: TextView = view.findViewById(R.id.titleText)
            private val urlText: TextView = view.findViewById(R.id.urlText)

            fun bind(item: Pair<String, String>, onClick: (String) -> Unit) {
                titleText.text = if (item.second.isBlank()) item.first else item.second
                urlText.text = item.first
                itemView.setOnClickListener { onClick(item.first) }
            }
        }
    }

    class FavoritesAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

        private var items = listOf<Pair<String, String>>()

        fun submitList(newList: List<Pair<String, String>>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onClick)
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleText: TextView = view.findViewById(R.id.titleText)

            fun bind(item: Pair<String, String>, onClick: (String) -> Unit) {
                titleText.text = if (item.second.isBlank()) item.first else item.second
                itemView.setOnClickListener { onClick(item.first) }
            }
        }
    }
}
