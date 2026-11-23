package com.example.dreamweb.engine

import android.webkit.JavascriptInterface

/**
 * Bridge JavaScript "DreamWebSniffer" (FR-006/FR-007). Reemplaza al objeto
 * anónimo que capturaba BrowserActivity: no guarda referencias a
 * Activity/Context — solo un callback nullable que queda inalcanzable al
 * remover la interfaz del WebView.
 *
 * Toda entrada se valida con [VideoUrlValidator] — exactamente la misma
 * heurística que el sniffer de red — antes de invocar el callback; cualquier
 * otra cosa se descarta silenciosamente (loggable en debug vía [logger]).
 *
 * release() anula el callback para que invocaciones JS ya encoladas sean
 * no-op incluso antes de removeJavascriptInterface.
 */
class SnifferBridge(private var onVideoFound: ((String) -> Unit)?) {

    /** Log opcional de rechazos (asignar en debug, p.ej. Log::d). */
    var logger: ((String) -> Unit)? = null

    @JavascriptInterface
    fun onVideoFound(url: String) {
        if (!VideoUrlValidator.isVideoUrl(url)) {
            logger?.invoke("DreamWebSniffer: rejected video candidate")
            return
        }
        onVideoFound?.invoke(url)
    }

    fun release() {
        onVideoFound = null
    }
}
