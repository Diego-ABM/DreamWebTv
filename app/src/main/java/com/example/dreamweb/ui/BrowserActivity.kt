package com.example.dreamweb.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.BuildConfig
import com.example.dreamweb.databinding.ActivityBrowserBinding
import com.example.dreamweb.security.TlsExceptionStore
import com.example.dreamweb.engine.CustomWebClient
import com.example.dreamweb.engine.CustomWebChromeClient
import com.example.dreamweb.engine.SnifferBridge
import com.example.dreamweb.managers.VideoPlayerManager
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private var webView: WebView? = null
    private var chromeClient: CustomWebChromeClient? = null
    private var snifferBridge: SnifferBridge? = null
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

        binding.adBlockToggleButton.setOnClickListener {
            toggleAdBlockWhitelist()
        }

        // Contador de bloqueos: visible solo en builds DEBUG (FR-006).
        if (BuildConfig.DEBUG) {
            binding.adBlockCounterText.visibility = View.VISIBLE
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
                app.container.historyManager,
                onVideoDetected = { videoUrl ->
                    onVideoFound(videoUrl)
                },
                onPageFinishedCallback = {
                    updateAdBlockUi()
                },
                onPageStartedCallback = {
                    // Navegación de main-frame → el video vigente se invalida (R3)
                    lastDetectedVideoUrl = null
                    binding.externalPlayerButton.visibility = View.GONE
                }
            )
            
            snifferBridge = SnifferBridge { videoUrl ->
                runOnUiThread {
                    this@BrowserActivity.onVideoFound(videoUrl)
                }
            }.also { bridge ->
                if (com.example.dreamweb.BuildConfig.DEBUG) {
                    bridge.logger = { msg -> android.util.Log.d("DreamWebSniffer", msg) }
                }
                addJavascriptInterface(bridge, "DreamWebSniffer")
            }

            chromeClient = CustomWebChromeClient(
                this@BrowserActivity,
                binding.webViewContainer,
                binding.fullscreenContainer,
                onFullscreenChanged = { isFullscreen ->
                    binding.virtualCursor.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                    binding.favoriteButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
                    binding.adBlockCounterText.visibility =
                        if (isFullscreen || !BuildConfig.DEBUG) View.GONE else View.VISIBLE
                    // Restauración condicionada: solo reaparece si el video
                    // sigue vigente en la página actual (FR-002, R3)
                    binding.externalPlayerButton.visibility =
                        if (isFullscreen || lastDetectedVideoUrl == null) View.GONE else View.VISIBLE
                },
                onReceivedTitleCallback = { titleUrl, title ->
                    // Título tardío (SPA / title post-onPageFinished) → upsert
                    if (!titleUrl.isNullOrBlank() && titleUrl != "about:blank") {
                        lifecycleScope.launch {
                            app.container.historyManager.addOrUpdateLatest(titleUrl, title)
                        }
                    }
                }
            )
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

    /** Exceptúa/rehabilita el dominio de la página actual en AdBlock. */
    private fun toggleAdBlockWhitelist() {
        val app = application as BrowserApp
        val host = TlsExceptionStore.normalize(webView?.url ?: "")
        if (host.isEmpty()) {
            android.widget.Toast.makeText(this, "Sin página cargada", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val store = app.container.adBlockWhitelistStore
        if (store.isExcepted(host)) {
            store.remove(host)
            android.widget.Toast.makeText(this, "Ads bloqueados en $host", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            store.add(host)
            android.widget.Toast.makeText(this, "Ads permitidos en $host", android.widget.Toast.LENGTH_SHORT).show()
        }
        updateAdBlockUi()
        webView?.reload()
    }

    /**
     * Refresca el contador DEBUG y el estado visual del toggle según si
     * el host de la página actual está exceptuado. Llamado desde el
     * callback onPageFinished del WebViewClient (hilo principal).
     */
    private fun updateAdBlockUi() {
        val app = application as BrowserApp
        if (BuildConfig.DEBUG) {
            binding.adBlockCounterText.text =
                "Ads bloqueados: ${app.container.adBlockManager.blockedCount()}"
        }
        val host = TlsExceptionStore.normalize(webView?.url ?: "")
        val excepted = host.isNotEmpty() && app.container.adBlockWhitelistStore.isExcepted(host)
        binding.adBlockToggleButton.alpha = if (excepted) 1.0f else 0.45f
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

    override fun onPause() {
        super.onPause()
        // Pausa de la vista (barato, simétrico). pauseTimers() queda
        // exclusivamente en el offloading al player nativo (FR-007, R7).
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
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
                binding.adBlockToggleButton,
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
            // Anular el callback del bridge ANTES de reciclar el WebView:
            // invocaciones JS ya encoladas se vuelven no-op de inmediato.
            snifferBridge?.release()
            snifferBridge = null
            app.container.webViewPool.releaseWebView(it)
            webView = null
        }
        super.onDestroy()
    }
}
