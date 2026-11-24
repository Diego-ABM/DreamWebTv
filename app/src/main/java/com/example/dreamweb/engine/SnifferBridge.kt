package com.example.dreamweb.engine

import android.webkit.JavascriptInterface

/**
 * Bridge JavaScript "DreamWebSniffer" (contracts/video-detection-bridge.md
 * §2). Mantiene el nombre de clase y de interfaz JS vigentes; la firma del
 * mensaje pasa de `(url)` a `(json)` — el reporte estructurado se valida en
 * [VideoReportParser] (frontera no confiable, Principio II) antes de tocar
 * el registry.
 *
 * No guarda referencias a Activity/Context — solo un callback nullable que
 * queda inalcanzable al remover la interfaz del WebView. [release] anula el
 * callback para que invocaciones JS ya encoladas sean no-op incluso antes
 * de removeJavascriptInterface.
 *
 * [currentPageUrl] se actualiza desde el hilo principal en cada navegación
 * de main-frame (el bridge corre en el hilo JavaBridge y no puede leer
 * `webView.url`): es el referer de respaldo cuando el reporte trae una
 * `pageUrl` ausente o no-http.
 */
class SnifferBridge(private var onVideoFound: ((ParsedMessage) -> Unit)?) {

    /** Log opcional de rechazos (asignar en debug, p.ej. Log::d). */
    var logger: ((String) -> Unit)? = null

    @Volatile
    var currentPageUrl: String = ""

    @JavascriptInterface
    fun onVideoFound(json: String) {
        val report = VideoReportParser.parse(json, currentPageUrl)
        if (report == null) {
            logger?.invoke("DreamWebSniffer: rejected video report")
            return
        }
        onVideoFound?.invoke(report)
    }

    fun release() {
        onVideoFound = null
    }
}
