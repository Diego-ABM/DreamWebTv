package com.example.dreamweb.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.databinding.ActivityBrowserBinding
import com.example.dreamweb.engine.CustomWebClient
import com.example.dreamweb.engine.CustomWebChromeClient
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private var webView: WebView? = null
    private var chromeClient: CustomWebChromeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("URL") ?: "https://www.google.com"
        
        setupWebView(url)

        binding.favoriteButton.setOnClickListener {
            addCurrentPageToFavorites()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val chrome = chromeClient
                if (chrome != null && chrome.isFullscreen()) {
                    chrome.onHideCustomView()
                } else if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView(url: String) {
        val app = application as BrowserApp
        webView = app.container.webViewPool.getWebView(this).apply {
            webViewClient = CustomWebClient(app.container.adBlockManager, app.container.historyManager)
            
            chromeClient = CustomWebChromeClient(
                this@BrowserActivity,
                binding.webViewContainer,
                binding.fullscreenContainer
            ) { isFullscreen ->
                binding.virtualCursor.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                binding.favoriteButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            }
            webChromeClient = chromeClient

            loadUrl(url)
        }
        
        binding.webViewContainer.addView(webView)
    }

    private fun addCurrentPageToFavorites() {
        val app = application as BrowserApp
        val url = webView?.url
        val title = webView?.title
        if (url != null) {
            lifecycleScope.launch {
                app.container.bookmarkManager.addBookmark(url, title)
                binding.favoriteButton.setImageResource(android.R.drawable.btn_star_big_on)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = application as BrowserApp
        webView?.let {
            if (app.container.dpadHandler.handleKeyEvent(event, it, binding.virtualCursor)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            webView?.let {
                // Low-level memory clearing
                it.clearCache(false)
            }
        }
    }

    override fun onDestroy() {
        webView?.let {
            val app = application as BrowserApp
            binding.webViewContainer.removeView(it)
            app.container.webViewPool.releaseWebView(it)
            webView = null
        }
        super.onDestroy()
    }
}
