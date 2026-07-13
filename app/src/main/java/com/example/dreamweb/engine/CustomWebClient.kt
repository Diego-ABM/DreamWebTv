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
        
        // Video Sniffing robusto y preciso (Inspirado en Android-WebCast y Scraping-Tutorial)
        val urlLower = url.lowercase()
        
        // Extensiones y patrones de video comunes
        val isVideoFormat = urlLower.contains(".m3u8") || 
                            urlLower.contains(".mp4") || 
                            urlLower.contains(".mpd") || 
                            urlLower.contains(".mkv") ||
                            urlLower.contains(".webm") ||
                            urlLower.contains("/manifest") ||
                            urlLower.contains(".m3u8?") ||
                            urlLower.contains("playlist.m3u8")

        // Filtrado de segmentos (no queremos capturar cada .ts individual)
        val isSegment = urlLower.contains(".ts") || 
                        urlLower.contains("/segment") || 
                        urlLower.contains("range=")

        // Filtrado de publicidad común en reproductores
        val isAdOrTracker = urlLower.contains("adsystem") || 
                           urlLower.contains("adserver") || 
                           urlLower.contains("analytics") || 
                           urlLower.contains("doubleclick") || 
                           urlLower.contains("pixel") ||
                           urlLower.contains("googlesyndication") ||
                           urlLower.contains("/ads/")

        if (isVideoFormat && !isSegment && !isAdOrTracker) {
            android.util.Log.d("DreamWebSniffer", "Media file detected: $url")
            scope.launch {
                // Capturamos el Referer y el User-Agent actual para el reproductor
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
                
                function notify(src) {
                    if (src && src.startsWith('http') && !src.includes('blob:')) {
                        window.DreamWebSniffer.onVideoFound(src);
                    }
                }

                function checkVideos() {
                    // 1. Escaneo de etiquetas <video>
                    var videos = document.getElementsByTagName('video');
                    for (var i = 0; i < videos.length; i++) {
                        var src = videos[i].src || videos[i].currentSrc;
                        notify(src);
                        
                        var sources = videos[i].getElementsByTagName('source');
                        for (var k = 0; k < sources.length; k++) {
                            notify(sources[k].src);
                        }
                    }
                    
                    // 2. Escaneo de iframes (común en sitios de streaming)
                    var iframes = document.getElementsByTagName('iframe');
                    for (var j = 0; j < iframes.length; j++) {
                        var isrc = iframes[j].src;
                        if (isrc && (isrc.includes('embed') || isrc.includes('player') || isrc.includes('video'))) {
                            // Intentamos detectar si el iframe apunta directamente a un stream
                            if (isrc.includes('.m3u8') || isrc.includes('.mp4')) {
                                notify(isrc);
                            }
                        }
                    }

                    // 3. Variables de reproductores comunes (JWPlayer, VideoJS, etc)
                    try {
                        if (window.jwplayer) {
                            var p = window.jwplayer();
                            if (p && p.getPlaylist) {
                                var item = p.getPlaylist()[0];
                                if (item && item.file) notify(item.file);
                            }
                        }
                        if (window.videojs) {
                            var players = window.videojs.players;
                            for (var p in players) {
                                notify(players[p].currentSrc());
                            }
                        }
                    } catch(e) {}
                }
                
                // Monitorizar cambios en el DOM para nuevos videos
                var observer = new MutationObserver(function(mutations) {
                    checkVideos();
                });
                observer.observe(document.body, { childList: true, subtree: true });
                
                checkVideos();
                setInterval(checkVideos, 10000); // Chequeo periódico fallback
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
