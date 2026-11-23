package com.example.dreamweb.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.example.dreamweb.BrowserApp
import com.example.dreamweb.adblock.AdBlockManager
import com.example.dreamweb.managers.HistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URISyntaxException

class CustomWebClient(
    private val adBlockManager: AdBlockManager,
    private val historyManager: HistoryManager,
    private val onVideoDetected: (String) -> Unit,
    private val onPageFinishedCallback: () -> Unit = {},
    private val onPageStartedCallback: () -> Unit = {}
) : WebViewClient() {
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * URL de la página en carga, capturada en onPageStarted (main thread).
     * shouldInterceptRequest corre en hilos worker de Chromium: llamar a
     * view.url ahí lanza la excepción de thread de WebView (crash fatal
     * observado en validación en dispositivo — bug preexistente).
     */
    @Volatile
    private var lastPageUrl: String? = null

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false

        // El host destino actúa como pageHost: un dominio en whitelist
        // navega libre; uno bloqueado se corta (research R5/decisión 6).
        if (adBlockManager.shouldBlock(url, url)) {
            return true // Block the navigation
        }

        // Esquemas ajenos al web nunca navegan en el WebView (FR-004, R2):
        // o los resuelve una app externa, o se avisa — return true siempre.
        return when (val action = ExternalUrlClassifier.classify(url)) {
            is ExternalAction.WebLoad -> false // Let WebView handle it
            is ExternalAction.External -> {
                handleExternalUrl(view, url, action.scheme)
                true
            }
        }
    }

    /**
     * Glue Android de la resolución de esquemas externos
     * (contracts/external-url-schemes.md §Resolución).
     */
    private fun handleExternalUrl(view: WebView?, url: String, scheme: String) {
        val context = view?.context ?: return
        if (scheme == "intent") {
            val intent = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (e: URISyntaxException) {
                warnNoHandler(context)
                return
            }
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Fallback interno del propio intent:// cuando existe:
                // solo se carga en el WebView si es http(s).
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null &&
                    (fallback.startsWith("http://") || fallback.startsWith("https://"))
                ) {
                    view?.loadUrl(fallback)
                } else {
                    warnNoHandler(context)
                }
            } catch (e: SecurityException) {
                warnNoHandler(context)
            }
        } else {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                warnNoHandler(context)
            } catch (e: SecurityException) {
                warnNoHandler(context)
            }
        }
    }

    private fun warnNoHandler(context: Context) {
        Toast.makeText(context, "Ninguna app puede abrir este enlace", Toast.LENGTH_SHORT).show()
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        lastPageUrl = url
        view?.let {
            val app = it.context.applicationContext as BrowserApp
            app.container.privacyEngine.injectPrivacyScripts(it)
        }
        // Una navegación de main-frame invalida el video vigente (FR-002, R3)
        onPageStartedCallback()
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // Video Sniffing: misma heurística que el bridge JS vía VideoUrlValidator
        if (VideoUrlValidator.isVideoUrl(url)) {
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

        if (adBlockManager.shouldBlock(url, lastPageUrl)) {
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
        // Registro del historial con el título definitivo (FR-001, R1):
        // view.title aquí es el de ESTA página; la dedup consecutiva de
        // addOrUpdateLatest absorbe los re-disparos.
        val historyUrl = url ?: view?.url
        if (!historyUrl.isNullOrBlank() && historyUrl != "about:blank") {
            scope.launch {
                historyManager.addOrUpdateLatest(historyUrl, view?.title)
            }
        }
        // Hook determinista de fin de página: el contador AdBlock se
        // refresca aquí (AtomicLong ya consistente al terminar la carga).
        onPageFinishedCallback()
    }

    private fun injectVideoSniffer(webView: WebView) {
        android.util.Log.d("DreamWebSniffer", "Injecting JS Sniffer")
        val js = """
            (function() {
                console.log('DreamWeb JS Sniffer Active');
                var notified = false;

                function notify(src) {
                    if (src && src.startsWith('http') && !src.includes('blob:')) {
                        if (!notified) {
                            notified = true;
                            clearInterval(pollId); // sondeo apagado tras 1ª detección (FR-006)
                        }
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

                // pollId se declara ANTES del primer checkVideos: una detección
                // inmediata debe poder apagar el sondeo (FR-006).
                var pollId = setInterval(checkVideos, 10000); // Chequeo periódico fallback
                // Backstop: ningún intervalo supera 2 min por documento (FR-006)
                setTimeout(function() { clearInterval(pollId); }, 120000);
                checkVideos();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectDarkMode(webView: WebView) {
        // Implementation for older devices if needed,
        // but for Fase 6 we focus on modern WebView Force Dark if available
        // which is set in WebSettings.
    }

    /** Cancela el scope de corrutinas — llamado por WebViewPool al reciclar. */
    fun release() {
        scope.cancel()
    }
}
