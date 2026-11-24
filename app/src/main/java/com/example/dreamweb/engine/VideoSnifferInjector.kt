package com.example.dreamweb.engine

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Carga `assets/video_detector.js` una vez y lo instala en el WebView
 * (contracts/video-detection-bridge.md §1, research R1):
 *
 * - Si `WebViewFeature.DOCUMENT_START_SCRIPT` está soportada →
 *   `WebViewCompat.addDocumentStartJavaScript` guardando el [ScriptHandler]
 *   para su `remove()` simétrico en [uninstall] (Principio V).
 * - Si no → el script se evalúa en `onPageFinished` en modo fallback
 *   ([fallbackScript] fija `__DW_FALLBACK__`, que desactiva los hooks de
 *   setters: los players ya existen cuando corre).
 *
 * [documentStartActive] indica a [CustomWebClient] si debe aplicar el
 * fallback; solo hay un WebView del pool, así que el estado es único.
 */
object VideoSnifferInjector {

    private const val ASSET_NAME = "video_detector.js"
    private const val FALLBACK_FLAG = "window.__DW_FALLBACK__ = true;\n"

    private var script: String? = null
    private var scriptHandler: ScriptHandler? = null

    @Volatile
    var documentStartActive: Boolean = false
        private set

    /**
     * Instala el detector. Debe llamarse tras `pool.getWebView()` y ANTES
     * de `loadUrl()` (requisito de la API document-start).
     */
    @Synchronized
    fun install(webView: WebView) {
        scriptHandler = null
        documentStartActive = false
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView, loadScript(webView.context), setOf("*")
            )
            documentStartActive = true
        }
    }

    /** Limpieza simétrica: `ScriptHandler.remove()` si se instaló. */
    @Synchronized
    fun uninstall(webView: WebView) {
        scriptHandler?.remove()
        scriptHandler = null
        documentStartActive = false
    }

    /** El mismo asset precedido del flag que activa el modo fallback. */
    fun fallbackScript(context: Context): String =
        FALLBACK_FLAG + loadScript(context)

    private fun loadScript(context: Context): String =
        script ?: context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .also { script = it }
}
