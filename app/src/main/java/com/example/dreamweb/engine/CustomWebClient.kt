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
    private val onVideoDetected: (VideoReport) -> Unit,
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

        // Video Sniffing (canal de red): misma frontera VideoUrlValidator +
        // headers reales de la petición sanitizados como los del bridge JS
        // (R3/US2-3). pageUrl = lastPageUrl (jamás view.url en hilo worker).
        if (VideoUrlValidator.isVideoUrl(url)) {
            android.util.Log.d("DreamWebSniffer", "Media file detected: $url")
            val report = VideoReport(
                url = url,
                pageUrl = lastPageUrl ?: "",
                format = streamFormatOf(url, null),
                headers = VideoReportParser.sanitizeHeaders(
                    request.requestHeaders ?: emptyMap()
                ),
                source = DetectionSource.NETWORK
            )
            scope.launch {
                onVideoDetected(report)
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
            injectVideoSnifferFallback(it)
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

    /**
     * Fallback de detección cuando DOCUMENT_START_SCRIPT no está soportado
     * por el WebView del dispositivo (contracts/video-detection-bridge.md §1):
     * evalúa el mismo asset en modo fallback (sin hooks de setters).
     */
    private fun injectVideoSnifferFallback(webView: WebView) {
        if (VideoSnifferInjector.documentStartActive) return
        android.util.Log.d("DreamWebSniffer", "Injecting JS Sniffer (fallback)")
        webView.evaluateJavascript(
            VideoSnifferInjector.fallbackScript(webView.context), null
        )
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
