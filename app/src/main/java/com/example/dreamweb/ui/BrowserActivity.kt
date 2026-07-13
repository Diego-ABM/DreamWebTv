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
import com.example.dreamweb.managers.VideoPlayerManager
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private var webView: WebView? = null
    private var chromeClient: CustomWebChromeClient? = null
    private var lastDetectedVideoUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("URL") ?: "https://www.google.com"
        
        setupWebView(url)

        binding.favoriteButton.setOnClickListener {
            addCurrentPageToFavorites()
        }

        binding.uaToggleButton.setOnClickListener {
            toggleUserAgent()
        }

        binding.externalPlayerButton.setOnClickListener {
            android.util.Log.d("DreamWebUI", "External Player Button Clicked")
            lastDetectedVideoUrl?.let { videoData ->
                android.util.Log.d("DreamWebUI", "Launching player for: $videoData")
                val ua = webView?.settings?.userAgentString ?: ""
                val cookies = android.webkit.CookieManager.getInstance().getCookie(webView?.url) ?: ""
                val videoDataWithExtras = "$videoData|UA|$ua|COOKIES|$cookies"
                VideoPlayerManager.launchPlayer(webView, videoDataWithExtras)
            } ?: run {
                android.util.Log.d("DreamWebUI", "No video URL detected yet")
                android.widget.Toast.makeText(this, "No se ha detectado un video aún", android.widget.Toast.LENGTH_SHORT).show()
            }
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
            webViewClient = CustomWebClient(
                app.container.adBlockManager,
                app.container.historyManager
            ) { videoUrl ->
                onVideoFound(videoUrl)
            }
            
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onVideoFound(url: String) {
                    runOnUiThread {
                        this@BrowserActivity.onVideoFound(url)
                    }
                }
            }, "DreamWebSniffer")

            chromeClient = CustomWebChromeClient(
                this@BrowserActivity,
                binding.webViewContainer,
                binding.fullscreenContainer
            ) { isFullscreen ->
                binding.virtualCursor.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                binding.favoriteButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                if (isFullscreen) binding.externalPlayerButton.visibility = View.GONE
            }
            webChromeClient = chromeClient

            loadUrl(url)
        }
        
        binding.webViewContainer.addView(webView)
    }

    private fun toggleUserAgent() {
        webView?.let { wv ->
            val currentUA = wv.settings.userAgentString
            val nextUA = com.example.dreamweb.engine.UserAgentConfig.getNext(currentUA)
            wv.settings.userAgentString = nextUA
            wv.reload()
            
            val name = com.example.dreamweb.engine.UserAgentConfig.getName(nextUA)
            android.widget.Toast.makeText(this, "Modo: $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun onVideoFound(videoData: String) {
        if (videoData.startsWith("blob:")) return
        
        // Separar URL de Referer si existe
        val parts = videoData.split("|REFERER|")
        val videoUrl = parts[0]
        
        val urlLower = videoUrl.lowercase()
        // Priorizar HLS (.m3u8) sobre MP4 simple
        if (lastDetectedVideoUrl?.contains(".m3u8") == true && urlLower.contains(".mp4")) return

        lastDetectedVideoUrl = videoData // Guardamos toda la cadena con el referer
        runOnUiThread {
            binding.externalPlayerButton.apply {
                visibility = View.VISIBLE
                alpha = 1.0f
                isEnabled = true
                bringToFront()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        VideoPlayerManager.releasePlayer(webView)
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
            val nativeViews = listOf(
                binding.favoriteButton, 
                binding.uaToggleButton,
                binding.externalPlayerButton
            )
            if (app.container.dpadHandler.handleKeyEvent(event, it, binding.virtualCursor, nativeViews)) {
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
