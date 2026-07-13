package com.example.dreamweb.engine

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.adblock.AdBlockManager
import com.example.dreamweb.managers.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CustomWebClient(
    private val adBlockManager: AdBlockManager,
    private val historyManager: HistoryManager,
    private val onVideoDetected: (String) -> Unit
) : WebViewClient() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        
        if (adBlockManager.shouldBlock(url)) {
            return true // Block the navigation
        }

        return false // Let WebView handle it
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.let {
            val app = it.context.applicationContext as BrowserApp
            app.container.privacyEngine.injectPrivacyScripts(it)
            
            url?.let { currentUrl ->
                if (currentUrl != "about:blank") {
                    scope.launch {
                        historyManager.addHistory(currentUrl, view.title)
                    }
                }
            }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        
        // Video Sniffing robusto y preciso (Inspirado en Android-WebCast)
        val urlLower = url.lowercase()
        val isMediaFile = urlLower.endsWith(".mp4") || urlLower.endsWith(".m3u8") || 
                         urlLower.endsWith(".mpd") || urlLower.endsWith(".mkv") ||
                         urlLower.contains(".m3u8?") || urlLower.contains(".mp4?") || 
                         urlLower.contains("/video.ts") || urlLower.contains(".m3u8#") ||
                         urlLower.contains("/manifest(") || urlLower.contains("playlist.m3u8")
        
        val isAdOrTracker = urlLower.contains("cuid") || urlLower.contains("analytics") || 
                           urlLower.contains("doubleclick") || urlLower.contains("pixel")
        
        if (isMediaFile && !isAdOrTracker) {
            android.util.Log.d("DreamWebSniffer", "Media file detected: $url")
            scope.launch {
                // Pasamos la URL del video y la URL de la página actual como Referer
                val referer = view?.url ?: ""
                onVideoDetected("$url|REFERER|$referer")
            }
        }

        // Block known heavy or redundant resources
        val path = request.url.path ?: ""
        if (path.endsWith(".ttf") || path.endsWith(".woff") || path.endsWith(".otf")) {
            return WebResourceResponse("text/plain", "UTF-8", null)
        }

        if (adBlockManager.shouldBlock(url)) {
            return WebResourceResponse("text/plain", "UTF-8", null)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        android.util.Log.d("DreamWebSniffer", "Page Finished: $url")
        view?.let {
            injectDarkMode(it)
            injectVideoSniffer(it)
        }
    }

    private fun injectVideoSniffer(webView: WebView) {
        android.util.Log.d("DreamWebSniffer", "Injecting JS Sniffer")
        val js = """
            (function() {
                console.log('DreamWeb JS Sniffer Active');
                function checkVideos() {
                    var videos = document.getElementsByTagName('video');
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src || videos[i].currentSrc;
                        if (src && src.indexOf('blob:') !== 0) {
                            window.DreamWebSniffer.onVideoFound(src);
                        }
                        
                        // Try to find the source in children
                        var sources = videos[i].getElementsByTagName('source');
                        for (var k = 0; k < sources.length; k++) {
                            if (sources[k].src && sources[k].src.indexOf('blob:') !== 0) {
                                window.DreamWebSniffer.onVideoFound(sources[k].src);
                            }
                        }
                    }
                    
                    // JW Player specific
                    if (window.jwplayer) {
                        try {
                            var p = window.jwplayer();
                            if (p && p.getPlaylist) {
                                var file = p.getPlaylist()[0].file;
                                if (file) window.DreamWebSniffer.onVideoFound(file);
                            }
                        } catch(e) {}
                    }
                }
                
                checkVideos();
                setInterval(checkVideos, 5000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectDarkMode(webView: WebView) {
        // Implementation for older devices if needed, 
        // but for Fase 6 we focus on modern WebView Force Dark if available
        // which is set in WebSettings.
    }
}
